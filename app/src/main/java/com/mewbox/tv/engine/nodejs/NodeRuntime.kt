package com.mewbox.tv.engine.nodejs

import android.content.Context
import com.fongmi.nodejs.NodeBundle
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.LocalMessageBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Node.js 运行时加载的终态（进度态走 [NodeRuntime.loading]） */
enum class NodeBootStage { SUCCESS, FAILED }

/** 终态提示事件，[message] 为可直接展示的文案 */
data class NodeBootEvent(val stage: NodeBootStage, val message: String)

@Singleton
class NodeRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageBridge: LocalMessageBridge,
    private val settings: SettingsRepository,
) {
    val client: NodeClient by lazy {
        NodeClient(context, messageBridge)
    }

    /** 加载批次号：并发的旧任务收尾时不能清掉新任务的 loading */
    private val sequence = AtomicInteger()

    /** 本地已有缓存时无需下载，直接从“启动中”开始，避免文案先跳一次“下载中” */
    fun initialText(source: String?): String {
        return if (hasCachedBundle(source)) "Node.js 服务启动中…" else "Node.js 下载中…"
    }

    /** 本地是否已有校验过的资源包（纯本地判断，不触发任何网络请求） */
    fun hasCachedBundle(source: String? = null): Boolean {
        return client.hasCachedBundle(source)
    }

    private val _loading = MutableStateFlow<String?>(
        if (isConfig(settings.currentConfigUrl)) {
            initialText(settings.currentConfigUrl)
        } else {
            null
        }
    )

    /**
     * 当前加载进度文案，null 表示没有加载。
     * 整个加载过程（下载 → 启动）只复用一个全局 loading，靠原地换文案避免提示闪动。
     */
    val loading: StateFlow<String?> = _loading.asStateFlow()

    /**
     * 终态结果流（成功 / 失败），由 MainActivity 统一收集后弹 Toast。
     * 首页与设置页同处一个 Pager 内同时存活，集中消费可避免重复提示。
     */
    private val resultChannel = Channel<NodeBootEvent>(Channel.BUFFERED)
    val results: Flow<NodeBootEvent> = resultChannel.receiveAsFlow()

    fun isConfig(url: String): Boolean = NodeBundle.isConfig(url)

    fun isRunning(): Boolean = client.isRunning()

    suspend fun ensureStarted(
        source: String? = null,
        notify: Boolean = true,
        showLoading: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        // 已经在跑（例如进程存活时重复进入 App）无需再打扰用户
        if (client.isRunning()) return@withContext true
        val id = sequence.incrementAndGet()
        // 有缓存也要先给出 loading，避免“没反应”的错觉
        if (showLoading) {
            setLoading(id, initialText(source))
        }
        try {
            val started = client.ensureStarted(source) { stage ->
                if (showLoading) onStage(id, stage)
            }
            if (notify) {
                if (started) {
                    notifyResult(NodeBootStage.SUCCESS, "Node.js 服务启动成功")
                } else {
                    notifyResult(NodeBootStage.FAILED, "启动失败：未找到可用的 Node.js 资源包")
                }
            }
            started
        } catch (e: Exception) {
            if (notify) reportFailure(e)
            throw e
        } finally {
            if (showLoading) {
                clearLoading(id)
            }
        }
    }

    /**
     * 切回前台或必要时的静默预热：
     * 在当前订阅源为 Node 或本地已存在 Node 缓存包（例如前端处于 HTML 网页源但底层需复用 Node 推送源）且服务未运行时在后台拉起，
     * 不弹出阻断 UI 的遮罩浮层，不弹通知提示。
     */
    suspend fun warmUpIfNecessary(): Boolean = withContext(Dispatchers.IO) {
        if (client.isRunning()) return@withContext true
        val currentUrl = settings.currentConfigUrl
        val targetSource = if (isConfig(currentUrl)) currentUrl else null
        if (targetSource != null || hasCachedBundle(null)) {
            try {
                Timber.i("检测到 Node 服务未运行，切回前台静默预热拉起: %s", targetSource ?: "cached_bundle")
                ensureStarted(source = targetSource, notify = false, showLoading = false)
            } catch (e: Exception) {
                Timber.w(e, "静默预热拉起 Node.js 运行时失败: %s", targetSource ?: "cached_bundle")
                false
            }
        } else {
            true
        }
    }

    /**
     * 静默拉起本地或指定 Node 服务并读取其 `/config` 转换后的站点配置 JSON。
     */
    suspend fun fetchConfigJson(source: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val started = ensureStarted(source = source, notify = false, showLoading = false)
            if (!started) return@withContext null
            client.fetchConfigJson(source)
        } catch (e: Exception) {
            Timber.w(e, "静默获取 Node.js /config 失败")
            null
        }
    }

    suspend fun loadConfig(source: String): String = withContext(Dispatchers.IO) {
        val id = sequence.incrementAndGet()
        setLoading(id, initialText(source))
        try {
            // 切订阅的最终结果由调用方（设置页 / 首页）给一条提示，这里不再重复发
            client.load(source) { stage -> onStage(id, stage) }
        } catch (e: Exception) {
            if (!NodeClient.isInterrupted(e)) Timber.w(e, "Node.js 运行时启动失败")
            throw e
        } finally {
            clearLoading(id)
        }
    }

    fun accept() {
        client.accept()
    }

    fun clear() {
        client.clear()
    }

    /** 强制清除 loading 状态（用于异常中断或容错分支） */
    fun clearLoadingForce() {
        sequence.incrementAndGet()
        _loading.value = null
    }

    /**
     * 用户手动打断加载：取消远端订阅下载与加载请求，并立即清掉 loading 浮层。
     * 后台任务会在下一个取消检查点抛 InterruptedIOException 收尾（sequence 已 bump，不会再复活浮层）。
     * 场景：远端订阅源卡住时，返回键打断后可切换其他订阅源。
     */
    fun cancelLoading() {
        clearLoadingForce()
        client.cancelLoad()
    }

    private fun onStage(id: Int, stage: NodeClient.Stage) {
        when (stage) {
            NodeClient.Stage.DOWNLOADING -> setLoading(id, "Node.js 下载中…")
            NodeClient.Stage.STARTING -> setLoading(id, "Node.js 服务启动中…")
        }
    }

    private fun setLoading(id: Int, text: String) {
        if (sequence.get() == id) _loading.value = text
    }

    private fun clearLoading(id: Int) {
        if (sequence.get() == id) _loading.value = null
    }

    private fun notifyResult(stage: NodeBootStage, message: String) {
        resultChannel.trySend(NodeBootEvent(stage, message))
    }

    private fun reportFailure(error: Throwable) {
        // 切换订阅/主动取消造成的打断不算失败，静默忽略
        if (NodeClient.isInterrupted(error)) return
        Timber.w(error, "Node.js 运行时启动失败")
        notifyResult(NodeBootStage.FAILED, "启动失败：${describe(error)}")
    }

    companion object {
        /** 把常见底层异常翻成中文提示，未命中的保留原文并做长度截断（UI 侧提示也复用这段文案） */
        fun describe(error: Throwable): String {
            val raw = error.message?.takeIf { it.isNotBlank() } ?: return error.javaClass.simpleName
            val text = when {
                raw.contains("Node startup timed out") -> "启动超时"
                raw.contains("Node config load canceled") -> "已手动取消"
                raw.contains("Node bundle loading canceled") -> "已手动取消"
                raw.contains("Unable to bind Node service") -> "无法绑定 Node 服务"
                raw.contains("Node server did not report an address") -> "Node 服务未返回监听地址"
                raw.contains("Node config must end with index.js.md5") -> "订阅地址必须以 index.js.md5 结尾"
                raw.contains("Unable to create Node staging directory") -> "无法创建资源包目录"
                raw.contains("Node bundle file is too large") -> "资源包体积超出限制"
                raw.contains("Empty Node bundle file") -> "资源包内容为空"
                raw.contains("Invalid MD5 sidecar") -> "MD5 校验文件格式错误"
                raw.contains("Signed Node bundle cannot downgrade to unsigned") -> "签名校验不通过"
                raw.contains("MD5 mismatch") -> "资源包 MD5 校验失败"
                raw.contains("Node runtime is not running") -> "Node 服务未运行"
                raw.startsWith("Download failed HTTP") -> raw.replaceFirst("Download failed HTTP", "下载失败 HTTP")
                raw.startsWith("HTTP ") -> raw.replace(" for ", " 地址：")
                else -> raw
            }
            return if (text.length > 80) text.take(80) + "…" else text
        }
    }
}
