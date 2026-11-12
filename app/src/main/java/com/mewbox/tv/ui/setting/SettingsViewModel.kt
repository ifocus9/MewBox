package com.mewbox.tv.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.Site
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.SiteDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Locale

import com.mewbox.tv.bean.Config
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.HistoryDao
import kotlinx.coroutines.flow.first

import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.bean.TmdbConfig
import com.mewbox.tv.api.tmdb.TmdbRepository
import com.fongmi.nodejs.NodeBundle
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.engine.nodejs.NodeRuntime

data class SiteItem(val site: Site, val supported: Boolean)

data class SettingsUiState(
    val sites: List<SiteItem> = emptyList(),
    val configs: List<Config> = emptyList(),
    val savedHost: String = "",
    val savedConfigUrl: String = "",
    val cacheSize: String = "0.0 MB",
    val tmdbConfig: TmdbConfig = TmdbConfig(),
    val doubanUid: String = "",
    val importing: Boolean = false,
    /** 是否展示导入遮罩：Node.js 源交给全局 loading，避免两层提示叠加 */
    val showImportOverlay: Boolean = false,
    /** 导入成功（含 Node.js 启动完成）后跳回首页的待消费标记 */
    val pendingHomeNav: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    siteDao: SiteDao,
    private val configDao: ConfigDao,
    private val historyDao: HistoryDao,
    private val vodConfig: VodConfig,
    private val settings: SettingsRepository,
    private val router: SpiderRouter,
    private val tmdbRepository: TmdbRepository,
    private val tmdbClient: TmdbClient,
) : ViewModel() {

    private data class Transient(
        val importing: Boolean = false,
        val showOverlay: Boolean = false,
        val pendingHomeNav: Boolean = false,
        val message: String? = null,
    )

    private val transient = MutableStateFlow(Transient())
    private val cacheSizeFlow = MutableStateFlow("0.0 MB")

    init {
        loadCacheSize()
        sanitizeConfigNames()
    }

    /**
     * 清理早期版本自动写入的「订阅 N」占位名。
     * 名称留空即可，展示层会自动以订阅链接作为订阅名称。
     */
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
        settings.configUrlFlow,
        cacheSizeFlow,
        transient,
    ) { (sites, host), (configs, tmdb, uid), url, cache, t ->
        SettingsUiState(
            sites = sites.filter { !it.hide }.map { SiteItem(it, router.isSupported(it)) },
            configs = configs,
            savedHost = host,
            savedConfigUrl = url,
            cacheSize = cache,
            tmdbConfig = tmdb,
            doubanUid = uid,
            importing = t.importing,
            showImportOverlay = t.importing && t.showOverlay,
            pendingHomeNav = t.pendingHomeNav,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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

    fun import(input: String, name: String = "") {
        if (transient.value.importing) return
        // Node.js 源（订阅以 index.js.md5 结尾）的下载与启动由全局 loading 统一提示，这里不再叠遮罩
        val nodeSource = NodeBundle.isConfig(input)
        viewModelScope.launch {
            transient.value = Transient(importing = true, showOverlay = !nodeSource)
            transient.value = try {
                // import 正常返回即 Node.js 已启动成功（client.load 阻塞到服务就绪），此时才允许跳首页
                val count = vodConfig.import(input, name)
                val msg = if (count == 0) "已导入网页订阅源" else "导入成功：$count 个站点"
                Transient(
                    message = msg,
                    pendingHomeNav = true,
                )
            } catch (e: Exception) {
                // 返回键手动打断不算失败：提示取消，留在设置页方便换一个订阅源重试
                val msg = if (NodeClient.isInterrupted(e)) "已取消导入" else "导入失败：${NodeRuntime.describe(e)}"
                Transient(message = msg)
            }
        }
    }

    fun saveConfig(url: String, name: String, isEdit: Boolean, oldUrl: String) {
        val targetUrl = url.trim()
        val targetName = name.trim()
        if (targetUrl.isEmpty()) return

        viewModelScope.launch {
            if (isEdit && oldUrl == targetUrl) {
                // 编辑同一地址时以输入为准：留空即清除名称，展示回退到订阅链接
                configDao.updateName(targetUrl, targetName)
                transient.update {
                    it.copy(message = if (targetName.isBlank()) "已清除名称，将使用订阅链接" else "订阅名称已更新")
                }
            } else {
                if (isEdit && oldUrl.isNotEmpty() && oldUrl != targetUrl) {
                    configDao.delete(oldUrl)
                }
                val existing = configDao.find(targetUrl)
                val finalName = when {
                    targetName.isNotBlank() -> targetName
                    else -> existing?.name.orEmpty()
                }
                import(targetUrl, finalName)
            }
        }
    }

    fun deleteConfig(url: String) {
        viewModelScope.launch {
            // 当前激活订阅不可删除（历史弹窗已过滤展示，这里兜底防误删）
            if (settings.configUrlFlow.first() == url) {
                transient.update { it.copy(message = "当前激活的订阅不可删除") }
                return@launch
            }
            configDao.delete(url)
            historyDao.clearByConfig(url)
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

    fun saveTmdbConfig(config: TmdbConfig) = viewModelScope.launch {
        tmdbRepository.save(config)
        transient.update { it.copy(message = "TMDB 设置已保存") }
    }

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

    suspend fun testTmdbApi(apiKey: String, apiBase: String): Result<String> =
        tmdbClient.testConnectivity(apiKey, apiBase)

    suspend fun testTmdbImage(imageBase: String): Result<String> =
        tmdbClient.testImageCdn(imageBase)

    fun consumeMessage() = transient.update { it.copy(message = null) }

    /** 跳回首页后由 UI 消费，避免重复触发 */
    fun consumePendingHomeNav() = transient.update { it.copy(pendingHomeNav = false) }
}

private fun calculateCacheSize(context: Context): Long {
    var size = 0L
    size += getDirectorySize(context.cacheDir)
    context.externalCacheDir?.let {
        size += getDirectorySize(it)
    }
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
