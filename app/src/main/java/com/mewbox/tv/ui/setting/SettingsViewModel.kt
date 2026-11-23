package com.mewbox.tv.ui.setting

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.SubscriptionCoordinator
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.api.tmdb.TmdbRepository
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.ThemeColor
import com.mewbox.tv.bean.ThemeMode
import com.mewbox.tv.bean.TmdbConfig
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.engine.nodejs.NodeRuntime
import com.mewbox.tv.ui.theme.applyApplicationNightMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class SiteItem(val site: Site, val supported: Boolean)

data class SettingsUiState(
    val sites: List<SiteItem> = emptyList(),
    val configs: List<Config> = emptyList(),
    val savedHost: String = "",
    val savedConfigUrl: String = "",
    val cacheSize: String = "0.0 MB",
    val tmdbConfig: TmdbConfig = TmdbConfig(),
    val doubanUid: String = "",
    val themeMode: ThemeMode = ThemeMode.Auto,
    val themeColorArgb: Int = ThemeColor.DEFAULT_ARGB,
    val importing: Boolean = false,
    /** 是否展示导入遮罩：Node.js 源交给全局 loading，避免两层提示叠加 */
    val showImportOverlay: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    siteDao: SiteDao,
    private val configDao: ConfigDao,
    private val vodConfig: VodConfig,
    private val coordinator: SubscriptionCoordinator,
    private val settings: SettingsRepository,
    private val router: SpiderRouter,
    private val tmdbRepository: TmdbRepository,
) : ViewModel() {

    private data class Transient(
        val importing: Boolean = false,
        val showOverlay: Boolean = false,
        val message: String? = null,
    )

    private val transient = MutableStateFlow(Transient())
    private val cacheSizeFlow = MutableStateFlow("0.0 MB")

    private val _navigateHomeEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val navigateHomeEvents: SharedFlow<Unit> = _navigateHomeEvents.asSharedFlow()

    init {
        loadCacheSize()
        sanitizeConfigNames()
    }

    /** 清理早期版本自动写入的「订阅 N」占位名。 */
    private fun sanitizeConfigNames() {
        viewModelScope.launch(Dispatchers.IO) {
            configDao.getAll()
                .filter { it.name.isNotBlank() && Config.isAutoName(it.name) }
                .forEach { configDao.updateName(it.url, "") }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(siteDao.observeAll(), settings.hostMappingFlow) { sites, host -> sites to host },
        combine(configDao.observeAll(), tmdbRepository.tmdbConfigFlow, settings.doubanUidFlow) { configs, tmdb, uid -> Triple(configs, tmdb, uid) },
        combine(settings.configUrlFlow, settings.themeModeFlow, settings.themeColorArgbFlow) { url, themeMode, themeColorArgb -> Triple(url, themeMode, themeColorArgb) },
        cacheSizeFlow,
        transient,
    ) { (sites, host), (configs, tmdb, uid), (url, themeMode, themeColorArgb), cache, t ->
        SettingsUiState(
            sites = sites.filter { !it.hide }.map { SiteItem(it, router.isSupported(it)) },
            configs = configs,
            savedHost = host,
            savedConfigUrl = url,
            cacheSize = cache,
            tmdbConfig = tmdb,
            doubanUid = uid,
            themeMode = themeMode,
            themeColorArgb = themeColorArgb,
            importing = t.importing,
            showImportOverlay = t.importing && t.showOverlay,
            message = t.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(
            themeMode = settings.currentThemeMode,
            themeColorArgb = settings.currentThemeColorArgb,
        ),
    )

    fun loadCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val size = calculateCacheSize(context)
            cacheSizeFlow.value = byteCountToDisplaySize(size)
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            deleteDirContent(context.cacheDir)
            context.externalCacheDir?.let { deleteDirContent(it) }
            val newSize = calculateCacheSize(context)
            cacheSizeFlow.value = byteCountToDisplaySize(newSize)
            transient.update { it.copy(message = "缓存已清理") }
        }
    }

    fun import(
        input: String,
        name: String = "",
        configId: String = "",
        replaceUrl: String = "",
    ) {
        if (transient.value.importing) return
        val nodeSource = coordinator.isNodeSource(input)
        viewModelScope.launch {
            transient.value = Transient(importing = true, showOverlay = !nodeSource)
            try {
                when (val result = coordinator.switchConfig(input, name, configId, replaceUrl)) {
                    is SubscriptionCoordinator.ActionResult.Imported -> {
                        vodConfig.notifySubscriptionMessage(result.message)
                        _navigateHomeEvents.tryEmit(Unit)
                    }
                    is SubscriptionCoordinator.ActionResult.Renamed -> {
                        vodConfig.notifySubscriptionMessage(result.message)
                    }
                    SubscriptionCoordinator.ActionResult.NoOp -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = if (NodeClient.isInterrupted(e)) "已取消导入" else "导入失败：${NodeRuntime.describe(e)}"
                vodConfig.notifySubscriptionMessage(msg)
            } finally {
                transient.value = Transient()
            }
        }
    }

    fun saveConfig(
        url: String,
        name: String,
        pickedUri: Uri? = null,
        isEdit: Boolean = false,
        oldUrl: String = "",
    ) {
        if (transient.value.importing) return
        if (pickedUri == null && url.trim().isEmpty()) return
        val nodeSource = coordinator.isNodeSource(url, pickedUri)
        val showOverlay = pickedUri == null && !nodeSource && !(isEdit && oldUrl.trim() == url.trim())

        viewModelScope.launch {
            transient.value = Transient(importing = true, showOverlay = showOverlay)
            try {
                when (val result = coordinator.saveConfig(url, name, pickedUri, isEdit, oldUrl)) {
                    is SubscriptionCoordinator.ActionResult.Imported -> {
                        vodConfig.notifySubscriptionMessage(result.message)
                        _navigateHomeEvents.tryEmit(Unit)
                    }
                    is SubscriptionCoordinator.ActionResult.Renamed -> {
                        vodConfig.notifySubscriptionMessage(result.message)
                    }
                    SubscriptionCoordinator.ActionResult.NoOp -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = if (NodeClient.isInterrupted(e)) "已取消导入" else "导入失败：${NodeRuntime.describe(e)}"
                vodConfig.notifySubscriptionMessage(msg)
            } finally {
                transient.value = Transient()
            }
        }
    }

    fun deleteConfig(url: String) {
        viewModelScope.launch {
            coordinator.deleteConfig(url)?.let { err ->
                vodConfig.notifySubscriptionMessage(err)
            }
        }
    }

    fun saveHost(value: String) {
        viewModelScope.launch {
            settings.setHostMapping(value)
            transient.update { it.copy(message = if (value.isBlank()) "已清除地址映射" else "已保存映射：$value") }
        }
    }

    fun toggleSearchable(site: Site) = viewModelScope.launch {
        if (site.searchable != 0) {
            vodConfig.update(site.copy(searchable = if (site.searchable == 1) 2 else 1))
        }
    }

    fun setHome(site: Site) = viewModelScope.launch { vodConfig.setHome(site.key) }

    fun saveDoubanUid(input: String) {
        val uid = SettingsRepository.normalizeDoubanUid(input)
            ?: run {
                transient.update { it.copy(message = "无法识别豆瓣 uid，请输入纯数字或个人主页链接") }
                return
            }
        viewModelScope.launch {
            settings.setDoubanUid(uid)
            transient.update {
                it.copy(message = if (uid.isBlank()) "已清除豆瓣 uid" else "已保存豆瓣 uid：$uid")
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            applyApplicationNightMode(context, mode)
            settings.setThemeMode(mode)
        }
    }

    fun setThemeColor(argb: Int) {
        viewModelScope.launch {
            settings.setThemeColorArgb(argb)
        }
    }

    fun consumeMessage() = transient.update { it.copy(message = null) }
}

private fun calculateCacheSize(context: Context): Long {
    var size = 0L
    size += getDirectorySize(context.cacheDir)
    context.externalCacheDir?.let { size += getDirectorySize(it) }
    return size
}

private fun getDirectorySize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    if (!dir.isDirectory) return dir.length()
    var size = 0L
    val files = dir.listFiles() ?: return 0L
    for (file in files) {
        size += if (file.isDirectory) getDirectorySize(file) else file.length()
    }
    return size
}

private fun deleteDirContent(dir: File?) {
    if (dir == null || !dir.exists() || !dir.isDirectory) return
    val files = dir.listFiles() ?: return
    for (file in files) {
        if (file.isDirectory) {
            deleteDirContent(file)
            file.delete()
        } else {
            file.delete()
        }
    }
}

private fun byteCountToDisplaySize(size: Long): String {
    if (size <= 0) return "0.0 MB"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.size - 1) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
