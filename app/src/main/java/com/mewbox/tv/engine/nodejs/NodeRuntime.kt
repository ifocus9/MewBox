package com.mewbox.tv.engine.nodejs

import android.content.Context
import com.fongmi.nodejs.NodeBundle
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.LocalMessageBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
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

    fun hasCachedBundle(source: String? = null): Boolean = client.hasCachedBundle(source)

    private val _loading = MutableStateFlow<String?>(
        if (isConfig(settings.currentConfigUrl)) {
            initialText(settings.currentConfigUrl)
        } else {
            null
        },
    )

    val loading: StateFlow<String?> = _loading.asStateFlow()

    private val resultChannel = Channel<NodeBootEvent>(Channel.BUFFERED)
    val results: Flow<NodeBootEvent> = resultChannel.receiveAsFlow()

    val serverAddress: StateFlow<String>
        get() = client.addressFlow

    val t4ShareActive: StateFlow<Boolean>
        get() = client.foregroundActiveFlow

    fun isConfig(url: String): Boolean = NodeBundle.isConfig(url)

    fun isLocalNodeSource(url: String): Boolean = NodeBundle.isLocalNodeSource(url)

    fun isRunning(): Boolean = client.isRunning()

    fun shouldKeepAliveOnExit(): Boolean = client.isForegroundShareActive()

    suspend fun enableT4Share(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetSource = settings.currentConfigUrl.takeIf { isConfig(it) }
            val started = ensureStarted(source = targetSource, notify = false, showLoading = false)
            if (!started) {
                return@withContext Result.failure(IllegalStateException("请先配置 Node.js 订阅源"))
            }
            client.startForegroundShare()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disableT4Share() = client.stopForegroundShare()

    suspend fun ensureStarted(
        source: String? = null,
        notify: Boolean = true,
        showLoading: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        if (client.isRunning()) return@withContext true
        val id = if (showLoading) sequence.incrementAndGet() else 0
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
     * 在当前订阅源为 Node 或本地已存在 Node 缓存包时后台拉起，不弹出遮罩与通知。
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

    suspend fun loadConfig(source: String): String = loadConfigResult(source).configJson

    suspend fun loadConfigResult(source: String): NodeClient.LoadResult = withContext(Dispatchers.IO) {
        val id = sequence.incrementAndGet()
        setLoading(id, initialText(source))
        try {
            client.loadWithResult(source) { stage -> onStage(id, stage) }
        } catch (e: Exception) {
            if (!NodeClient.isInterrupted(e)) Timber.w(e, "Node.js 运行时启动失败")
            throw e
        } finally {
            clearLoading(id)
        }
    }

    suspend fun loadFromStream(
        localSourceUrl: String,
        streamOpener: () -> InputStream,
    ): NodeClient.LoadResult = withContext(Dispatchers.IO) {
        val id = sequence.incrementAndGet()
        setLoading(id, "Node.js 资源解压启动中…")
        try {
            client.loadFromStream(localSourceUrl, streamOpener) { stage -> onStage(id, stage) }
        } catch (e: Exception) {
            if (!NodeClient.isInterrupted(e)) Timber.w(e, "本地 Node.js 压缩包启动失败")
            throw e
        } finally {
            clearLoading(id)
        }
    }

    fun accept() = client.accept()

    fun clear() = client.clear()

    fun clearLoadingForce() {
        sequence.incrementAndGet()
        _loading.value = null
    }

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
        if (NodeClient.isInterrupted(error)) return
        Timber.w(error, "Node.js 运行时启动失败")
        notifyResult(NodeBootStage.FAILED, "启动失败：${describe(error)}")
    }

    companion object {
        fun describe(error: Throwable): String {
            val raw = error.message?.takeIf { it.isNotBlank() } ?: return error.javaClass.simpleName
            val text = when {
                raw.contains("Node startup timed out") -> "启动超时"
                raw.contains("Node config load canceled") -> "已手动取消"
                raw.contains("Node bundle loading canceled") -> "已手动取消"
                raw.contains("Unable to bind Node service") -> "无法绑定 Node 服务"
                raw.contains("Node server did not report an address") -> "Node 服务未返回监听地址"
                raw.contains("Node config must end with") -> "订阅地址必须以 index.js.md5、index.js 或 .zip 结尾"
                raw.contains("压缩包内缺少 index.js") -> "压缩包内缺少 index.js"
                raw.contains("压缩包内缺少 index.config.js") -> "压缩包内缺少 index.config.js"
                raw.contains("Node zip bundle exceeds size limit") -> "压缩包解压体积超出限制"
                raw.contains("无法读取本地压缩包文件") -> "无法读取本地压缩包文件"
                raw.contains("Unable to create Node staging directory") -> "无法创建资源包目录"
                raw.contains("Node bundle file is too large") -> "资源包体积超出限制"
                raw.contains("Empty Node bundle file") -> "资源包内容为空"
                raw.contains("Invalid MD5 sidecar") -> "MD5 校验文件格式错误"
                raw.contains("Signed Node bundle cannot downgrade to unsigned") -> "签名校验不通过"
                raw.contains("MD5 mismatch") -> "资源包 MD5 校验失败"
                raw.contains("Node runtime is not running") -> "Node 服务未运行"
                raw.contains("returned no active sites") -> "节点服务未返回有效站点"
                raw.startsWith("Download failed HTTP") -> raw.replaceFirst("Download failed HTTP", "下载失败 HTTP")
                raw.startsWith("HTTP ") -> raw.replace(" for ", " 地址：")
                else -> raw
            }
            return if (text.length > 80) text.take(80) + "…" else text
        }
    }
}
