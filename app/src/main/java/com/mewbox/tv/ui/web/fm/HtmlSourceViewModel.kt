package com.mewbox.tv.ui.web.fm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.api.config.SubscriptionCoordinator
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.Config
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.engine.nodejs.NodeRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val coordinator: SubscriptionCoordinator,
    private val configDao: ConfigDao,
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
        viewModelScope.launch(Dispatchers.IO) {
            vodConfig.ensurePushBackendReady()
        }

        viewModelScope.launch {
            vodConfig.observeActiveConfig().collect { config ->
                if (config != null && config.isHtml && config.url.isNotBlank()) {
                    webController.loadSubscriptionUrl(config.url)
                }
            }
        }

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
        val isNode = coordinator.isNodeSource(targetUrl)

        viewModelScope.launch {
            transient.value = TransientState(isSwitching = true, showOverlay = !isNode)
            try {
                when (val result = coordinator.switchConfig(targetUrl)) {
                    is SubscriptionCoordinator.ActionResult.Imported -> {
                        val msg = if (result.isHtml) "已切换至网页订阅源" else result.message.replace("导入成功：", "订阅切换成功（共 ").let { if (it != result.message) "$it）" else result.message }
                        transient.value = TransientState(message = msg)
                    }
                    is SubscriptionCoordinator.ActionResult.Renamed -> {
                        transient.value = TransientState(message = result.message)
                    }
                    SubscriptionCoordinator.ActionResult.NoOp -> transient.value = TransientState()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = if (NodeClient.isInterrupted(e)) "已取消切换" else "订阅切换失败：${NodeRuntime.describe(e)}"
                transient.value = TransientState(message = msg)
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
        if (transient.value.isSwitching) return
        if (pickedUri == null && url.trim().isEmpty()) return
        val isNode = coordinator.isNodeSource(url, pickedUri)
        val showOverlay = pickedUri == null && !isNode && !(isEdit && oldUrl.trim() == url.trim())

        viewModelScope.launch {
            transient.value = TransientState(isSwitching = true, showOverlay = showOverlay)
            try {
                when (val result = coordinator.saveConfig(url, name, pickedUri, isEdit, oldUrl)) {
                    is SubscriptionCoordinator.ActionResult.Imported -> {
                        if (result.isHtml && result.loadedUrl.isNotBlank()) {
                            webController.loadSubscriptionUrl(result.loadedUrl, forceReload = true)
                        }
                        transient.value = TransientState(message = result.message)
                    }
                    is SubscriptionCoordinator.ActionResult.Renamed -> {
                        val msg = if (result.message == "已清除名称，将使用订阅链接") {
                            "已清除名称，将使用网页标题或链接"
                        } else {
                            result.message
                        }
                        transient.value = TransientState(message = msg)
                    }
                    SubscriptionCoordinator.ActionResult.NoOp -> transient.value = TransientState()
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
            coordinator.deleteConfig(url)?.let { err ->
                transient.update { it.copy(message = err) }
            }
        }
    }

    fun consumeMessage() {
        transient.update { it.copy(message = null) }
    }
}
