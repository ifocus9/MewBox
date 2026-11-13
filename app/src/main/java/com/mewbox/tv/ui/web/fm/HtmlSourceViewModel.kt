package com.mewbox.tv.ui.web.fm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fongmi.nodejs.NodeBundle
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.Config
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.HistoryDao
import com.mewbox.tv.engine.nodejs.NodeRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HtmlSourceUiState(
    val activeConfig: Config? = null,
    val configs: List<Config> = emptyList(),
    val webState: HtmlWebState = HtmlWebState(),
    val isSwitching: Boolean = false,
    val showImportOverlay: Boolean = false,
    val message: String? = null,
) {
    /** 侧边悬浮胶囊展示的订阅名称：优先用户自定义名 -> 当前激活网页动态标题 -> 订阅链接 */
    val displaySubscriptionName: String
        get() {
            val customName = activeConfig?.name.orEmpty()
            if (customName.isNotBlank()) return customName
            val pageTitle = webState.pageTitle.trim()
            if (pageTitle.isNotBlank() && webState.loadedRootUrl == activeConfig?.url?.trim()) {
                return pageTitle
            }
            return activeConfig?.desc.orEmpty().ifBlank { "网页源" }
        }
}

@HiltViewModel
class HtmlSourceViewModel @Inject constructor(
    val webController: HtmlWebController,
    private val vodConfig: VodConfig,
    private val configDao: ConfigDao,
    private val historyDao: HistoryDao,
    private val settings: SettingsRepository,
) : ViewModel() {

    private data class TransientState(
        val isSwitching: Boolean = false,
        val showOverlay: Boolean = false,
        val message: String? = null,
    )

    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<HtmlSourceUiState> = combine(
        vodConfig.observeActiveConfig(),
        configDao.observeAll(),
        webController.state,
        transient,
    ) { active, configs, web, t ->
        HtmlSourceUiState(
            activeConfig = active,
            configs = configs,
            webState = web,
            isSwitching = t.isSwitching,
            showImportOverlay = t.isSwitching && t.showOverlay,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HtmlSourceUiState())

    init {
        // 冷启动直接进入 HTML 源时，后台静默预热底层 Node.js 推送引擎（push_agent），确保点击网盘资源即可秒播
        viewModelScope.launch(Dispatchers.IO) {
            vodConfig.ensurePushBackendReady()
        }

        // 监听当前激活的 HTML 订阅地址，自动驱动 WebView 加载
        viewModelScope.launch {
            vodConfig.observeActiveConfig().collect { config ->
                if (config != null && config.isHtml && config.url.isNotBlank()) {
                    webController.loadSubscriptionUrl(config.url)
                }
            }
        }

        // 若 HTML 订阅尚未命名，且 WebView 动态获取到当前订阅网页标题（如 SPA 渲染后设置 document.title），自动回填为默认名称
        viewModelScope.launch(Dispatchers.IO) {
            combine(vodConfig.observeActiveConfig(), webController.state) { config, web ->
                config to web
            }.collect { (config, web) ->
                val pageTitle = web.pageTitle.trim()
                if (
                    config != null &&
                    config.isHtml &&
                    config.name.isBlank() &&
                    web.loadedRootUrl == config.url.trim() &&
                    !web.isInitialPageLoading &&
                    pageTitle.isNotBlank()
                ) {
                    configDao.updateName(config.url, pageTitle.take(60))
                }
            }
        }
    }

    fun reloadWebPage() {
        webController.reload()
    }

    fun switchSubscription(url: String) {
        if (transient.value.isSwitching) return
        val targetUrl = url.trim()
        if (targetUrl.isEmpty()) return
        val isNode = NodeBundle.isConfig(targetUrl)

        viewModelScope.launch {
            transient.value = TransientState(isSwitching = true, showOverlay = !isNode)
            try {
                val count = vodConfig.import(targetUrl)
                val msg = if (count == 0) {
                    "已切换至网页订阅源"
                } else {
                    "订阅切换成功（共 $count 个站点）"
                }
                transient.value = TransientState(message = msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = if (NodeClient.isInterrupted(e)) "已取消切换" else "订阅切换失败：${NodeRuntime.describe(e)}"
                transient.value = TransientState(message = msg)
            }
        }
    }

    fun saveConfig(url: String, name: String, isEdit: Boolean, oldUrl: String) {
        val targetUrl = url.trim()
        val targetName = name.trim()
        if (targetUrl.isEmpty()) return

        viewModelScope.launch {
            if (isEdit && oldUrl == targetUrl) {
                configDao.updateName(targetUrl, targetName)
                transient.update {
                    it.copy(message = if (targetName.isBlank()) "已清除名称，将使用网页标题或链接" else "订阅名称已更新")
                }
            } else {
                val oldConfig = if (isEdit && oldUrl.isNotEmpty()) configDao.find(oldUrl) else null
                val existing = configDao.find(targetUrl)
                val finalName = when {
                    targetName.isNotBlank() -> targetName
                    else -> existing?.name ?: oldConfig?.name.orEmpty()
                }
                importNewConfig(
                    url = targetUrl,
                    name = finalName,
                    configId = oldConfig?.id.orEmpty(),
                    replaceUrl = if (isEdit && oldUrl != targetUrl) oldUrl else "",
                )
            }
        }
    }

    private fun importNewConfig(
        url: String,
        name: String,
        configId: String = "",
        replaceUrl: String = "",
    ) {
        if (transient.value.isSwitching) return
        val isNode = NodeBundle.isConfig(url)
        viewModelScope.launch {
            transient.value = TransientState(isSwitching = true, showOverlay = !isNode)
            try {
                val count = vodConfig.import(url, name, configId = configId, replaceUrl = replaceUrl)
                if (count == 0) {
                    webController.loadSubscriptionUrl(url, forceReload = true)
                    transient.value = TransientState(message = "网页订阅源导入成功")
                } else {
                    transient.value = TransientState(message = "导入成功：$count 个站点")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = if (NodeClient.isInterrupted(e)) "已取消导入" else "导入失败：${NodeRuntime.describe(e)}"
                transient.value = TransientState(message = msg)
            }
        }
    }

    fun deleteConfig(url: String) {
        viewModelScope.launch {
            if (settings.configUrlFlow.first() == url) {
                transient.update { it.copy(message = "当前激活的订阅不可删除") }
                return@launch
            }
            val config = configDao.find(url)
            configDao.delete(url)
            if (config != null && config.id.isNotBlank()) {
                historyDao.clearWithTracksByConfig(config.id)
            }
        }
    }

    fun consumeMessage() {
        transient.update { it.copy(message = null) }
    }
}

