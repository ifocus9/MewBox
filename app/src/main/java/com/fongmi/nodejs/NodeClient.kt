package com.fongmi.nodejs

import android.content.Context
import com.mewbox.tv.api.net.LocalMessageBridge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

class NodeClient(
    context: Context,
    private val messageBridge: LocalMessageBridge,
) : LocalMessageBridge.MessageHandler {

    /** 运行时加载阶段：仅描述“进程外”的耗时环节，成功/失败由调用方判定 */
    enum class Stage { DOWNLOADING, STARTING }

    /** Node 包加载与 `/config` 初始化结果（支持记录 `.pending` 自动回滚事实） */
    data class LoadResult(
        val configJson: String,
        val rolledBack: Boolean = false,
        val rollbackReason: String = "",
    )

    private val context: Context = context.applicationContext ?: context
    private val processManager = NodeProcessManager(this.context)
    private val profileLock = Any()
    private val calls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val callThreads = ConcurrentHashMap<Call, Thread>()
    private val initTracker = NodeSpiderInitTracker()
    private val pendingClears = AtomicInteger()

    @Volatile private var loadCancellation: AtomicBoolean? = null
    @Volatile private var session: Long = 0
    @Volatile private var bundle: NodeBundle? = null

    companion object {
        private const val SUCCESS_RESPONSE = "{\"success\":true,\"code\":0}"
        private const val FAILURE_RESPONSE = "{\"success\":false,\"code\":-1}"
        private val FAST_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15)
        private val REQUEST_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(45)
        private val PLAY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60)
        private const val MAX_CONFIG_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val MAX_JSON_RESPONSE_BYTES = 16L * 1024 * 1024
        private const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024
        private val LEGACY_LOOPBACK = Pattern.compile("http://(?:127\\.0\\.0\\.1|localhost):(?:9978|9988)(?=[/?#|$\\s\"']|$)")
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val CLIENT = client(REQUEST_TIMEOUT_MILLIS)
        private val FAST_CLIENT = client(FAST_TIMEOUT_MILLIS)
        private val PLAY_CLIENT = client(PLAY_TIMEOUT_MILLIS)

        private fun client(timeout: Long): OkHttpClient {
            return OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()
        }

        private fun clientFor(route: String): OkHttpClient {
            val query = route.indexOf('?')
            val path = if (query < 0) route else route.substring(0, query)
            if (path.endsWith("/init") || path.endsWith("/support") || path.endsWith("/action")) return FAST_CLIENT
            if (path.endsWith("/play")) return PLAY_CLIENT
            return CLIENT
        }

        fun isInterrupted(error: Throwable?): Boolean {
            val timedOut = hasCause(error, SocketTimeoutException::class.java)
            var current = error
            while (current != null) {
                if (current is InterruptedException) return true
                if (current is InterruptedIOException && !timedOut) return true
                if (current is IOException && "Canceled".equals(current.message, ignoreCase = true)) return true
                current = current.cause
            }
            return Thread.currentThread().isInterrupted
        }

        fun isTransportFailure(error: Exception): Boolean {
            return error !is NodeHttpException && error !is NodeResponseException && hasCause(error, IOException::class.java)
        }

        private fun hasCause(error: Throwable?, type: Class<out Throwable>): Boolean {
            var current = error
            while (current != null) {
                if (type.isInstance(current)) return true
                current = current.cause
            }
            return false
        }

        fun rewriteLoopback(value: String?, address: String?): String? {
            if (value == null || address.isNullOrEmpty()) return value
            return LEGACY_LOOPBACK.matcher(value).replaceAll(Matcher.quoteReplacement(address))
        }

        @Throws(IOException::class)
        fun readResponse(body: ResponseBody, limit: Long): String {
            val length = body.contentLength()
            if (length > limit) throw NodeResponseException("Node response is too large")
            body.byteStream().use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        total += count
                        if (total > limit) throw NodeResponseException("Node response is too large")
                        output.write(buffer, 0, count)
                    }
                    return output.toString(StandardCharsets.UTF_8.name())
                }
            }
        }

        internal fun extractErrorMessage(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return try {
                val obj = Json.parseToJsonElement(trimmed) as? JsonObject ?: return ""
                val candidates = listOf(obj["message"], obj["msg"], obj["error"])
                candidates.firstNotNullOfOrNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                }.orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }

    init {
        this.messageBridge.addHandler(this)
        removeLegacyActiveSourceMarker()
    }

    private fun removeLegacyActiveSourceMarker() {
        try {
            val legacy = File(File(context.filesDir, "nodejs"), "active_source")
            if (legacy.isFile) legacy.delete()
        } catch (_: Exception) {
        }
    }

    val addressFlow: StateFlow<String>
        get() = processManager.addressFlow

    val foregroundActiveFlow: StateFlow<Boolean>
        get() = processManager.foregroundActiveFlow

    fun isRunning(): Boolean = processManager.isRunning()

    fun isForegroundShareActive(): Boolean = processManager.isForegroundActive()

    fun startForegroundShare() = processManager.startForegroundShare()

    fun stopForegroundShare() = processManager.stopForegroundShare()

    fun isSameVersion(other: NodeBundle?): Boolean = bundle?.isSameVersion(other) == true

    fun accept() {
        bundle?.accept()
    }

    fun hasCachedBundle(source: String? = null): Boolean {
        return try {
            restoreBundle(source) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreBundle(source: String? = null): NodeBundle? {
        if (!source.isNullOrEmpty()) {
            NodeBundle.restore(context, source)?.let { return it }
        }
        return NodeBundle.restoreLatest(context)
    }

    @Synchronized
    @Throws(Exception::class)
    fun fetchConfigJson(source: String? = null): String {
        if (!isRunning()) {
            ensureStarted(source)
        }
        return NodeConfigMapper.transform(get("/config"))
    }

    @Synchronized
    @Throws(Exception::class)
    fun ensureStarted(source: String? = null, onStage: ((Stage) -> Unit)? = null): Boolean {
        if (isRunning()) return true
        val current = bundle ?: restoreBundle(source)
        if (current != null) {
            onStage?.invoke(Stage.STARTING)
            startBundleWithPendingRollback(current, onStage = onStage)
            return true
        }
        if (!source.isNullOrEmpty()) {
            val prepared = NodeBundle.prepare(context, source) { onStage?.invoke(Stage.DOWNLOADING) }
            onStage?.invoke(Stage.STARTING)
            startBundleWithPendingRollback(prepared, onStage = onStage)
            return true
        }
        return false
    }

    @Synchronized
    @Throws(Exception::class)
    fun ensureRunning() {
        if (isRunning()) return
        val current = bundle ?: restoreBundle()
        if (current != null) {
            startBundleWithPendingRollback(current, onStage = null)
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun load(source: String, onStage: ((Stage) -> Unit)? = null): String {
        return loadWithResult(source, onStage).configJson
    }

    @Synchronized
    @Throws(Exception::class)
    fun loadWithResult(source: String, onStage: ((Stage) -> Unit)? = null): LoadResult {
        return executeLoad(onStage) {
            NodeBundle.prepare(context, source) { onStage?.invoke(Stage.DOWNLOADING) }
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun loadFromStream(
        localSourceUrl: String,
        streamOpener: () -> InputStream,
        onStage: ((Stage) -> Unit)? = null,
    ): LoadResult {
        return executeLoad(onStage) {
            NodeBundle.prepareFromStream(context, localSourceUrl, streamOpener) {
                onStage?.invoke(Stage.DOWNLOADING)
            }
        }
    }

    private inline fun executeLoad(
        noinline onStage: ((Stage) -> Unit)?,
        prepareBlock: () -> NodeBundle,
    ): LoadResult {
        checkLoadCanceled()
        loadCancellation = AtomicBoolean()
        try {
            val prepared = prepareBlock()
            checkLoadCanceled()
            if (canReuse(prepared)) {
                val result = NodeConfigMapper.transform(get("/config"))
                checkLoadCanceled()
                return LoadResult(result)
            }
            return try {
                session++
                initTracker.clear()
                startAndFetchConfigWithRollback(prepared, onStage)
            } catch (error: Throwable) {
                clearPreservingFailure(error)
                throw error
            }
        } finally {
            loadCancellation = null
        }
    }

    private fun startBundleWithPendingRollback(
        target: NodeBundle,
        onStage: ((Stage) -> Unit)?,
    ) {
        try {
            start(target)
        } catch (error: Exception) {
            if (isInterrupted(error)) throw error
            val fallback = if (target.isPending) NodeBundle.rollbackPending(target.root, target.data) else null
            if (fallback == null) throw error
            Timber.w(error, "检测到未验收新包启动异常，已自动回滚至上一稳定版本")
            onStage?.invoke(Stage.STARTING)
            start(fallback)
        }
    }

    private fun startAndFetchConfigWithRollback(
        prepared: NodeBundle,
        onStage: ((Stage) -> Unit)?,
    ): LoadResult {
        return try {
            onStage?.invoke(Stage.STARTING)
            start(prepared)
            val result = fetchAndValidateConfig()
            checkLoadCanceled()
            LoadResult(result)
        } catch (error: Exception) {
            if (isInterrupted(error)) throw error
            val fallback = if (prepared.isPending) NodeBundle.rollbackPending(prepared.root, prepared.data) else null
            if (fallback == null) throw error
            Timber.w(error, "新 Node 包启动或解析 /config 失败，已回滚至上一版本")
            onStage?.invoke(Stage.STARTING)
            start(fallback)
            val fallbackConfig = fetchAndValidateConfig()
            checkLoadCanceled()
            LoadResult(
                configJson = fallbackConfig,
                rolledBack = true,
                rollbackReason = error.message.orEmpty(),
            )
        }
    }

    private fun fetchAndValidateConfig(): String {
        val transformed = NodeConfigMapper.transform(get("/config"))
        val obj = Json.parseToJsonElement(transformed) as? JsonObject
        val sites = obj?.get("sites") as? JsonArray
        if (sites == null || sites.isEmpty()) {
            throw NodeResponseException("Node /config returned no active sites")
        }
        return transformed
    }

    private fun canReuse(prepared: NodeBundle): Boolean {
        return bundle?.isSameVersion(prepared) == true && !processManager.address.isNullOrEmpty()
    }

    private fun start(prepared: NodeBundle) {
        checkLoadCanceled()
        cancelRequests()
        val restoreForeground = processManager.isForegroundActive()
        processManager.stopService()
        bundle = prepared
        val sessionToken = UUID.randomUUID().toString()
        processManager.beginSession(sessionToken)
        messageBridge.token = sessionToken
        val bridgePort = messageBridge.start(LocalMessageBridge.DEFAULT_PORT)
        processManager.bindAndAwait(prepared, bridgePort, sessionToken) { checkLoadCanceled() }
        if (restoreForeground) {
            processManager.startForegroundShare()
        }
    }

    fun clear() {
        pendingClears.incrementAndGet()
        try {
            cancelLoad()
            cancelRequests()
            clearRuntime()
        } finally {
            pendingClears.decrementAndGet()
        }
    }

    @Synchronized
    private fun clearRuntime() {
        session++
        cancelRequests()
        initTracker.clear()
        processManager.stopService()
        bundle = null
        loadCancellation = null
        processManager.resetState()
    }

    private fun clearPreservingFailure(failure: Throwable?) {
        try {
            clear()
        } catch (error: Throwable) {
            if (failure != null && failure !== error) failure.addSuppressed(error)
        }
    }

    private fun cancelRequests() {
        for (call in calls) {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        calls.clear()
        callThreads.clear()
    }

    fun cancelCallsForThread(thread: Thread) {
        for ((call, owner) in callThreads) {
            if (owner === thread) {
                try {
                    call.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun cancelLoad() {
        loadCancellation?.set(true)
        NodeBundle.cancelLoad()
    }

    @Throws(InterruptedIOException::class)
    private fun checkLoadCanceled() {
        val cancellation = loadCancellation
        if (pendingClears.get() > 0 || Thread.currentThread().isInterrupted || (cancellation != null && cancellation.get())) {
            throw InterruptedIOException("Node config load canceled")
        }
    }

    override fun onMessage(action: String, opt: JsonElement?): String? {
        val optObj = opt as? JsonObject ?: JsonObject(emptyMap())
        return when (action) {
            "serverStarted" -> {
                processManager.onStarted(optObj)
                SUCCESS_RESPONSE
            }
            "nodeError" -> {
                processManager.onError(optObj)
                SUCCESS_RESPONSE
            }
            "saveProfile" -> saveProfile(optObj)
            else -> null
        }
    }

    fun getProfileFile(): File? {
        val current = bundle ?: return null
        val parent = current.data.parentFile ?: current.root.parentFile
        return File(parent, "profile.json")
    }

    private fun saveProfile(profile: JsonObject): String {
        synchronized(profileLock) {
            val file = getProfileFile() ?: return FAILURE_RESPONSE
            return try {
                Path.writeAtomically(file, profile.toString().toByteArray(StandardCharsets.UTF_8))
                SUCCESS_RESPONSE
            } catch (e: IOException) {
                Timber.e(e, "保存 profile.json 失败")
                FAILURE_RESPONSE
            }
        }
    }

    @Throws(Exception::class)
    fun initialize(api: String, body: JsonObject) {
        request { state ->
            try {
                executePost(state, NodeRoute.append(api, "/init"), body)
            } catch (e: NodeHttpException) {
                if (e.code != 404) throw e
            }
            initTracker.markInitialized(state.generation, api)
        }
    }

    @Throws(Exception::class)
    fun post(api: String, body: JsonObject): String {
        return request { state -> executePost(state, api, body) }
    }

    /**
     * 向指定爬虫路由发起请求，自动按当前 `:nodejs` 进程代际保证该站点已执行过一次 `/init`
     *（即使站点 `ext` 为空或 `:nodejs` 后台重建后，也不会带着未初始化状态直接访问 `/detail`、`/play` 等端点）。
     */
    @Throws(Exception::class)
    fun postForSpider(baseApi: String, initBody: JsonObject, route: String, body: JsonObject): String {
        val targetApi = NodeRoute.append(baseApi, route)
        return request(SpiderInit(baseApi, initBody)) { state -> executePost(state, targetApi, body) }
    }

    @Throws(Exception::class)
    fun postOptionalForSpider(baseApi: String, initBody: JsonObject, route: String, body: JsonObject): String {
        return postOrNullForSpider(baseApi, initBody, route, body).orEmpty()
    }

    @Throws(Exception::class)
    fun postOrNullForSpider(baseApi: String, initBody: JsonObject, route: String, body: JsonObject): String? {
        return try {
            postForSpider(baseApi, initBody, route, body)
        } catch (e: NodeHttpException) {
            if (e.code == 404) null else throw e
        }
    }

    @Throws(Exception::class)
    fun postOptional(api: String, body: JsonObject): String {
        return postOrNull(api, body).orEmpty()
    }

    @Throws(Exception::class)
    fun postOrNull(api: String, body: JsonObject): String? {
        return try {
            post(api, body)
        } catch (e: NodeHttpException) {
            if (e.code == 404) null else throw e
        }
    }

    @Throws(IOException::class)
    private fun get(route: String): String {
        val state = snapshot()
        val base = state.address
        if (base.isNullOrEmpty()) throw IOException("Node runtime is not running")
        val request = Request.Builder().url(base + route).get().build()
        val call = registerCall(state, CLIENT, request)
        return try {
            call.execute().use { response ->
                readResponse(response, state, MAX_CONFIG_RESPONSE_BYTES)
            }
        } finally {
            unregisterCall(call)
        }
    }

    @Throws(Exception::class)
    private fun executePost(state: RequestState, api: String, body: JsonObject): String {
        val route = NodeRoute.path(api)
        val base = state.address
        if (base.isNullOrEmpty()) throw IOException("Node runtime is not running")
        val request = Request.Builder()
            .url(base + route)
            .post(body.toString().toByteArray(StandardCharsets.UTF_8).toRequestBody(JSON))
            .build()
        val call = registerCall(state, clientFor(route), request)
        return try {
            call.execute().use { response ->
                readResponse(response, state, MAX_JSON_RESPONSE_BYTES)
            }
        } finally {
            unregisterCall(call)
        }
    }

    @Throws(IOException::class)
    private fun readResponse(response: Response, state: RequestState, limit: Long): String {
        val body = response.body
        if (!response.isSuccessful) {
            val serverMessage = try {
                extractErrorMessage(readResponse(body, MAX_ERROR_RESPONSE_BYTES))
            } catch (_: Exception) {
                ""
            }
            throw NodeHttpException(response.code, serverMessage)
        }
        val result = rewriteLoopback(readResponse(body, limit), state.address)
            ?: throw IOException("Empty rewritten response")
        checkCurrent(state)
        return result
    }

    @Throws(Exception::class)
    private fun <T> request(
        spiderInit: SpiderInit? = null,
        operation: (RequestState) -> T,
    ): T {
        if (!isRunning()) {
            ensureRunning()
        }
        var state = snapshot()
        return try {
            if (spiderInit != null) {
                state = ensureSpiderInitialized(state, spiderInit)
            }
            operation(state)
        } catch (first: Exception) {
            checkSession(state)
            if (!isTransportFailure(first) || isInterrupted(first)) throw first
            if (hasCause(first, SocketTimeoutException::class.java)) {
                throw first
            }
            try {
                state = recover(state)
                if (spiderInit != null) {
                    state = ensureSpiderInitialized(state, spiderInit)
                }
                operation(state)
            } catch (second: Exception) {
                checkSession(state)
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun ensureSpiderInitialized(
        observed: RequestState,
        spiderInit: SpiderInit,
    ): RequestState {
        if (initTracker.isInitialized(observed.generation, spiderInit.baseApi)) {
            return observed
        }
        synchronized(initTracker.lockFor(spiderInit.baseApi)) {
            var active = snapshot()
            checkSession(active)
            if (active.address.isNullOrEmpty()) {
                ensureRunning()
                active = snapshot()
            }
            if (initTracker.isInitialized(active.generation, spiderInit.baseApi)) {
                return active
            }
            try {
                executePost(active, NodeRoute.append(spiderInit.baseApi, "/init"), spiderInit.body)
                initTracker.markInitialized(active.generation, spiderInit.baseApi)
            } catch (e: NodeHttpException) {
                if (e.code == 404) {
                    // 兼容未注册 /init 路由的爬虫（如 czsapp.js），本进程代不再重复探测
                    initTracker.markInitialized(active.generation, spiderInit.baseApi)
                } else {
                    Timber.w(e, "Node 站点 /init 返回 HTTP %d，继续尝试主请求: %s", e.code, spiderInit.baseApi)
                }
            } catch (e: Exception) {
                checkSession(active)
                if (isInterrupted(e)) throw e
                if (isTransportFailure(e) && !hasCause(e, SocketTimeoutException::class.java)) {
                    throw e
                }
                Timber.w(e, "Node 站点 /init 异常，继续尝试主请求: %s", spiderInit.baseApi)
            }
            return active
        }
    }

    @Synchronized
    @Throws(Exception::class)
    private fun recover(observed: RequestState): RequestState {
        checkSession(observed)
        if (processManager.generation != observed.generation && processManager.isRunning()) {
            return snapshot()
        }
        val current = bundle ?: restoreBundle() ?: throw IOException("Node runtime has no bundle to restart")
        try {
            start(current)
        } catch (e: Exception) {
            invalidate(snapshot())
            throw e
        }
        return snapshot()
    }

    @Synchronized
    private fun invalidate(observed: RequestState) {
        if (session != observed.session || processManager.generation != observed.generation) return
        cancelRequests()
        processManager.stopService()
        processManager.invalidateAddressAndBumpGeneration()
    }

    @Throws(IOException::class)
    private fun registerCall(state: RequestState, client: OkHttpClient, request: Request): Call {
        checkCurrent(state)
        val call = client.newCall(request.newBuilder().tag(AtomicBoolean::class.java, state.cancellation).build())
        calls.add(call)
        callThreads[call] = Thread.currentThread()
        return call
    }

    private fun unregisterCall(call: Call) {
        calls.remove(call)
        callThreads.remove(call)
    }

    @Synchronized
    private fun snapshot(): RequestState {
        return RequestState(session, processManager.generation, processManager.address, loadCancellation)
    }

    @Synchronized
    @Throws(InterruptedIOException::class)
    private fun checkSession(state: RequestState) {
        if (session != state.session) throw InterruptedIOException("Node config load was superseded")
        if (pendingClears.get() > 0 || Thread.currentThread().isInterrupted || (state.cancellation != null && state.cancellation.get())) {
            throw InterruptedIOException("Node request canceled")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun checkCurrent(state: RequestState) {
        checkSession(state)
        // 同 session 下仅代际变化说明 :nodejs 被重启，抛出可恢复传输异常而非取消异常，便于外层自动重试到新进程
        if (processManager.generation != state.generation) throw NodeRestartedException()
    }

    fun getAddress(): String = processManager.address.orEmpty()

    fun normalizeWeb(options: JsonObject): JsonObject {
        val addr = processManager.address
        val result = options.toMutableMap()
        normalizeUrl(result, addr)
        val cookie = result["cookie"] as? JsonArray
        if (cookie != null) {
            val newCookies = cookie.map { group ->
                if (group is JsonObject) {
                    val groupMap = group.toMutableMap()
                    normalizeUrl(groupMap, addr)
                    JsonObject(groupMap)
                } else {
                    group
                }
            }
            result["cookie"] = JsonArray(newCookies)
        }
        return JsonObject(result)
    }

    private fun normalizeUrl(options: MutableMap<String, JsonElement>, addr: String?) {
        val url = options["url"]?.jsonPrimitive?.content
        if (!url.isNullOrEmpty()) {
            val normalized = normalizeInternalUrl(url, addr)
            if (normalized != null) {
                options["url"] = JsonPrimitive(normalized)
            }
        }
    }

    private fun normalizeInternalUrl(value: String?, addr: String?): String? {
        if (value.isNullOrEmpty() || addr.isNullOrEmpty()) return value
        val target = value.toHttpUrlOrNull() ?: return value
        val runtime = addr.toHttpUrlOrNull() ?: return value
        if (target.scheme != "http" || target.port != runtime.port) return value
        return target.newBuilder().scheme(runtime.scheme).host(runtime.host).port(runtime.port).build().toString()
    }

    private data class SpiderInit(
        val baseApi: String,
        val body: JsonObject,
    )

    private data class RequestState(
        val session: Long,
        val generation: Long,
        val address: String?,
        val cancellation: AtomicBoolean?,
    )

    private class NodeRestartedException : IOException("Node runtime was restarted")

    class NodeHttpException(
        val code: Int,
        val serverMessage: String = "",
    ) : IOException(if (serverMessage.isNotBlank()) "Node HTTP $code: $serverMessage" else "Node HTTP $code")

    class NodeResponseException(message: String) : IOException(message)
}
