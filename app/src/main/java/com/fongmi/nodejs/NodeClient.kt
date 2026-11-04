package com.fongmi.nodejs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.mewbox.tv.api.net.LocalMessageBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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

class NodeClient(
    context: Context,
    private val messageBridge: LocalMessageBridge,
) : LocalMessageBridge.MessageHandler {

    /** 运行时加载阶段：仅描述“进程外”的耗时环节，成功/失败由调用方判定 */
    enum class Stage { DOWNLOADING, STARTING }

    private val context: Context = context.applicationContext ?: context
    private val profileLock = Any()
    private val calls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val initializations = LinkedHashMap<String, String>()
    private val pendingClears = AtomicInteger()

    @Volatile private var loadCancellation: AtomicBoolean? = null
    @Volatile private var session: Long = 0
    @Volatile private var startLatch: CountDownLatch? = null
    @Volatile private var startError: Exception? = null
    @Volatile private var address: String? = null
    @Volatile private var token: String? = null
    @Volatile private var bundle: NodeBundle? = null
    @Volatile private var generation: Long = 0
    @Volatile private var startedAt: Long = 0
    @Volatile private var nodePid: Int = 0
    @Volatile private var nodeVersion: String = ""
    @Volatile private var nodeArch: String = ""
    @Volatile private var currentPlayFlag: String = ""
    @Volatile private var currentPlayId: String = ""
    @Volatile private var connection: NodeConnection? = null

    companion object {
        private const val SUCCESS_RESPONSE = "{\"success\":true,\"code\":0}"
        private const val FAILURE_RESPONSE = "{\"success\":false,\"code\":-1}"
        private const val START_TIMEOUT_SECONDS = 45L
        private const val STOP_TIMEOUT_SECONDS = 5L
        private const val BIND_TIMEOUT_SECONDS = 1L
        private val FAST_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15)
        private val REQUEST_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(45)
        private val PLAY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60)
        private const val MAX_CONFIG_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val MAX_JSON_RESPONSE_BYTES = 16L * 1024 * 1024
        private val LEGACY_LOOPBACK = Pattern.compile("http://(?:127\\.0\\.0\\.1|localhost):9978(?=[/?#|$\\s\"']|$)")
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
            if (path.endsWith("/play") || path.endsWith("/proxy")) return PLAY_CLIENT
            return CLIENT
        }

        fun isInterrupted(error: Throwable?): Boolean {
            val timedOut = hasCause(error, SocketTimeoutException::class.java)
            var current = error
            while (current != null) {
                if (current is InterruptedException) return true
                if (current is InterruptedIOException && !timedOut) return true
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
    }

    init {
        this.messageBridge.addHandler(this)
    }

    fun isRunning(): Boolean = !address.isNullOrEmpty() && connection != null

    /** 当前运行中的 bundle 与 [other] 是否同一版本（用于后台比对，避免无谓重启） */
    fun isSameVersion(other: NodeBundle?): Boolean = bundle?.isSameVersion(other) == true

    fun accept() {
        bundle?.accept()
    }

    private fun saveActiveSource(source: String) {
        try {
            val dir = File(context.filesDir, "nodejs")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "active_source").writeText(source, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "保存 Node active_source 失败")
        }
    }

    private fun readActiveSource(): String? {
        return try {
            val file = File(File(context.filesDir, "nodejs"), "active_source")
            if (file.isFile) file.readText(Charsets.UTF_8).trim().ifEmpty { null } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreBundle(source: String? = null): NodeBundle? {
        if (!source.isNullOrEmpty()) {
            val target = NodeBundle.restore(context, source)
            if (target != null) return target
        }
        val activeSource = readActiveSource()
        if (!activeSource.isNullOrEmpty()) {
            val target = NodeBundle.restore(context, activeSource)
            if (target != null) return target
        }
        return NodeBundle.restoreLatest(context)
    }

    @Synchronized
    @Throws(Exception::class)
    fun ensureStarted(source: String? = null, onStage: ((Stage) -> Unit)? = null): Boolean {
        if (isRunning()) return true
        if (!source.isNullOrEmpty()) {
            saveActiveSource(source)
        }
        val current = bundle ?: restoreBundle(source)
        if (current != null) {
            onStage?.invoke(Stage.STARTING)
            start(current)
            replayInitializations()
            return true
        }
        if (!source.isNullOrEmpty()) {
            val prepared = NodeBundle.prepare(context, source) { onStage?.invoke(Stage.DOWNLOADING) }
            onStage?.invoke(Stage.STARTING)
            start(prepared)
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
            start(current)
            replayInitializations()
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun load(source: String, onStage: ((Stage) -> Unit)? = null): String {
        checkLoadCanceled()
        saveActiveSource(source)
        loadCancellation = AtomicBoolean()
        return try {
            val prepared = NodeBundle.prepare(context, source) { onStage?.invoke(Stage.DOWNLOADING) }
            checkLoadCanceled()
            if (canReuse(prepared)) {
                // 版本一致：运行时原样保留，只重取 /config 刷新站点列表。
                // 刻意不 bump session、不 cancelRequests —— 那会把用户正在进行的
                // 搜索 / 详情 / 播放解析全部判成 superseded 而打断，但实际上
                // 运行时根本没变，没有任何理由打断它们
                val result = NodeConfigMapper.transform(get("/config"))
                checkLoadCanceled()
                return result
            }
            // 确实要换包才打断：清掉旧的站点初始化记录，再重启运行时
            session++
            initializations.clear()
            onStage?.invoke(Stage.STARTING)
            start(prepared)
            val result = NodeConfigMapper.transform(get("/config"))
            checkLoadCanceled()
            result
        } catch (error: Throwable) {
            clearPreservingFailure(error)
            throw error
        } finally {
            loadCancellation = null
        }
    }

    private fun canReuse(prepared: NodeBundle): Boolean {
        return bundle?.isSameVersion(prepared) == true && !address.isNullOrEmpty()
    }

    private fun start(prepared: NodeBundle) {
        checkLoadCanceled()
        cancelRequests()
        stopService()
        generation++
        token = UUID.randomUUID().toString()
        messageBridge.token = token
        val bridgePort = messageBridge.start(LocalMessageBridge.DEFAULT_PORT)
        bindProcess(prepared, bridgePort)
        awaitStarted()
    }

    private fun bindProcess(prepared: NodeBundle, bridgePort: Int) {
        bundle = prepared
        address = null
        startError = null
        startLatch = CountDownLatch(1)
        val data = prepared.data
        val intent = Intent(context, NodeService::class.java)
            .setAction(NodeService.ACTION_START)
            .putExtra(NodeService.EXTRA_INDEX, prepared.index.absolutePath)
            .putExtra(NodeService.EXTRA_CONFIG, prepared.config.absolutePath)
            .putExtra(NodeService.EXTRA_DATA, data.absolutePath)
            .putExtra(NodeService.EXTRA_BRIDGE_PORT, bridgePort)
            .putExtra(NodeService.EXTRA_TOKEN, token)
        val next = NodeConnection(generation)
        connection = next
        next.bound = context.bindService(intent, next, Context.BIND_AUTO_CREATE)
        if (!next.bound) throw IOException("Unable to bind Node service")
    }

    private fun awaitStarted() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
        val latch = checkNotNull(startLatch)
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            checkLoadCanceled()
            if (System.nanoTime() >= deadline) throw IOException("Node startup timed out")
        }
        checkLoadCanceled()
        val error = startError
        if (error != null) throw error
        if (address.isNullOrEmpty()) throw IOException("Node server did not report an address")
        startedAt = System.currentTimeMillis()
        Timber.i("Node.js 运行时已启动: %s (PID: %d)", address, nodePid)
    }

    private fun stopService() {
        val current = connection
        if (current != null) {
            synchronized(current) {
                connection = null
            }
            current.stop()
            return
        }
        try {
            context.stopService(Intent(context, NodeService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun onServiceLost(message: String) {
        address = null
        if (startError == null) startError = IOException(message)
        startLatch?.countDown()
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
        initializations.clear()
        stopService()
        resetRuntimeState()
    }

    private fun resetRuntimeState() {
        bundle = null
        address = null
        token = null
        loadCancellation = null
        startLatch = null
        startError = null
        generation++
        startedAt = 0
        nodePid = 0
        nodeVersion = ""
        nodeArch = ""
        currentPlayFlag = ""
        currentPlayId = ""
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

    override fun onMessage(action: String, opt: kotlinx.serialization.json.JsonElement?): String? {
        val optObj = opt as? JsonObject ?: JsonObject(emptyMap())
        return dispatchMessage(action, optObj)
    }

    private fun dispatchMessage(action: String, opt: JsonObject): String? {
        return when (action) {
            "serverStarted" -> onStarted(opt)
            "nodeError" -> onError(opt)
            "queryProfile" -> readProfile().toString()
            "saveProfile" -> saveProfile(opt)
            "getPlayInfo" -> getPlayInfo()
            else -> null
        }
    }

    private fun onStarted(opt: JsonObject): String {
        val current = connection
        if (current == null || !tokenMatches(opt)) return SUCCESS_RESPONSE
        synchronized(current) {
            if (connection !== current || current.stopped.count == 0L || !tokenMatches(opt)) return SUCCESS_RESPONSE
            val value = opt["address"]?.jsonPrimitive?.content.orEmpty()
            if (value.startsWith("http://") || value.startsWith("https://")) {
                address = value.replace("://localhost:", "://127.0.0.1:")
                nodePid = opt["pid"]?.jsonPrimitive?.intOrNull ?: 0
                nodeVersion = opt["version"]?.jsonPrimitive?.content.orEmpty()
                nodeArch = opt["arch"]?.jsonPrimitive?.content.orEmpty()
                startLatch?.countDown()
            }
        }
        return SUCCESS_RESPONSE
    }

    private fun onError(opt: JsonObject): String {
        if (!tokenMatches(opt)) return SUCCESS_RESPONSE
        val message = opt["message"]?.jsonPrimitive?.content.orEmpty()
        startError = if (message.isEmpty()) null else IOException(message)
        startLatch?.countDown()
        return SUCCESS_RESPONSE
    }

    private fun tokenMatches(opt: JsonObject): Boolean {
        val curToken = token
        return !curToken.isNullOrEmpty() && curToken == opt["token"]?.jsonPrimitive?.content.orEmpty()
    }

    fun getProfileFile(): File? {
        val current = bundle ?: return null
        return File(current.root.parentFile, "profile.json")
    }

    private fun readProfile(): JsonElement {
        synchronized(profileLock) {
            val file = getProfileFile()
            if (file == null || !file.isFile) return JsonObject(emptyMap())
            return try {
                Json.parseToJsonElement(Path.read(file))
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
        }
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

    fun setPlayInfo(flag: String?, id: String?) {
        currentPlayFlag = flag.orEmpty()
        currentPlayId = id.orEmpty()
    }

    private fun getPlayInfo(): String {
        return buildJsonObject {
            put("title", "")
            put("episodeName", "")
            put("fileName", "")
            put("flag", currentPlayFlag)
            put("id", currentPlayId)
            put("url", "")
            put("key", "")
            put("position", 0)
            put("duration", 0)
        }.toString()
    }

    @Synchronized
    @Throws(Exception::class)
    fun initialize(api: String, body: JsonObject) {
        post(NodeRoute.append(api, "/init"), body)
        initializations[api] = body.toString()
    }

    @Throws(Exception::class)
    fun post(api: String, body: JsonObject): String {
        return request(api) { state -> executePost(state, api, body) }
    }

    @Throws(Exception::class)
    fun postOptional(api: String, body: JsonObject): String {
        val result = postOrNull(api, body)
        return result.orEmpty()
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
            calls.remove(call)
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
            calls.remove(call)
        }
    }

    @Throws(IOException::class)
    private fun readResponse(response: Response, state: RequestState, limit: Long): String {
        val body = response.body
        if (!response.isSuccessful) throw NodeHttpException(response.code)
        val result = rewriteLoopback(readResponse(body, limit), state.address)
            ?: throw IOException("Empty rewritten response")
        checkCurrent(state)
        return result
    }

    @Throws(Exception::class)
    private fun <T> request(endpoint: String, operation: (RequestState) -> T): T {
        if (!isRunning()) {
            ensureRunning()
        }
        var state = snapshot()
        return try {
            operation(state)
        } catch (first: Exception) {
            checkSession(state)
            if (!isTransportFailure(first) || isInterrupted(first)) throw first
            if (hasCause(first, SocketTimeoutException::class.java)) {
                invalidate(state, first)
                throw first
            }
            try {
                state = recover(state)
                operation(state)
            } catch (second: Exception) {
                checkSession(state)
                second.addSuppressed(first)
                throw second
            }
        }
    }

    @Synchronized
    @Throws(Exception::class)
    private fun recover(observed: RequestState): RequestState {
        checkSession(observed)
        if (generation != observed.generation && !address.isNullOrEmpty()) return snapshot()
        val current = bundle ?: restoreBundle() ?: throw IOException("Node runtime has no bundle to restart")
        try {
            start(current)
            replayInitializations()
        } catch (e: Exception) {
            invalidate(snapshot(), e)
            throw e
        }
        return snapshot()
    }

    @Throws(Exception::class)
    private fun replayInitializations() {
        for ((key, value) in initializations) {
            val body = Json.parseToJsonElement(value) as? JsonObject ?: continue
            executePost(snapshot(), NodeRoute.append(key, "/init"), body)
        }
    }

    @Synchronized
    private fun invalidate(observed: RequestState, error: Exception) {
        if (session != observed.session || generation != observed.generation) return
        cancelRequests()
        stopService()
        address = null
        generation++
    }

    @Throws(IOException::class)
    private fun registerCall(state: RequestState, client: OkHttpClient, request: Request): Call {
        checkCurrent(state)
        val call = client.newCall(request.newBuilder().tag(AtomicBoolean::class.java, state.cancellation).build())
        calls.add(call)
        return call
    }

    @Synchronized
    private fun snapshot(): RequestState {
        return RequestState(session, generation, address, loadCancellation)
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
    @Throws(InterruptedIOException::class)
    private fun checkCurrent(state: RequestState) {
        checkSession(state)
        if (generation != state.generation) throw InterruptedIOException("Node runtime was restarted")
    }

    fun getAddress(): String = address.orEmpty()

    fun normalizeWeb(options: JsonObject): JsonObject {
        val addr = this.address
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

    @Throws(Exception::class)
    fun proxy(api: String, params: Map<String, String>): Array<Any> {
        val path = params["path"] ?: "/proxy"
        return request(path) { state -> executeProxy(state, api, params) }
    }

    @Throws(Exception::class)
    private fun executeProxy(state: RequestState, api: String, params: Map<String, String>): Array<Any> {
        val base = state.address
        if (base.isNullOrEmpty()) throw IOException("Node runtime is not running")
        val call = registerCall(state, PLAY_CLIENT, proxyRequest(api, params, base))
        var response: Response? = null
        try {
            response = call.execute()
            checkCurrent(state)
        } catch (e: Exception) {
            response?.close()
            calls.remove(call)
            throw e
        }
        val body = response.body
        val headers = LinkedHashMap<String, String>()
        for (name in response.headers.names()) {
            headers[name] = response.header(name).orEmpty()
        }
        val type = response.header("Content-Type") ?: "application/octet-stream"
        return arrayOf(response.code, type, ResponseStream(body.byteStream(), response, call), headers)
    }

    @Throws(IOException::class)
    private fun proxyRequest(api: String, params: Map<String, String>, base: String): Request {
        val path = params["path"]
        val target = if (path.isNullOrEmpty()) {
            NodeRoute.append(api, "/proxy")
        } else if (path.startsWith("/")) {
            "node:$path"
        } else {
            NodeRoute.append(api, path)
        }
        val parsed = (base + NodeRoute.path(target)).toHttpUrlOrNull()
            ?: throw IOException("Invalid Node proxy route")
        val url = parsed.newBuilder()
        for ((key, value) in params) {
            if (isProxyControl(key)) continue
            url.addQueryParameter(key, value)
        }
        val request = Request.Builder().url(url.build())
        copyProxyHeader(params, request, "range")
        copyProxyHeader(params, request, "user-agent")
        copyProxyHeader(params, request, "referer")
        copyProxyHeader(params, request, "origin")
        return request.build()
    }

    private fun isProxyControl(key: String): Boolean {
        return "siteKey".equals(key, ignoreCase = true) ||
            "path".equals(key, ignoreCase = true) ||
            "remote-addr".equals(key, ignoreCase = true) ||
            "range".equals(key, ignoreCase = true) ||
            "user-agent".equals(key, ignoreCase = true) ||
            "referer".equals(key, ignoreCase = true) ||
            "origin".equals(key, ignoreCase = true)
    }

    private fun copyProxyHeader(params: Map<String, String>, request: Request.Builder, name: String) {
        for ((key, value) in params) {
            if (name.equals(key, ignoreCase = true)) request.header(name, value)
        }
    }

    private data class RequestState(
        val session: Long,
        val generation: Long,
        val address: String?,
        val cancellation: AtomicBoolean?,
    )

    private inner class NodeConnection(private val session: Long) : ServiceConnection, IBinder.DeathRecipient {
        val connected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        @Volatile var binder: IBinder? = null
        @Volatile var bound: Boolean = false

        @Synchronized
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            try {
                service?.linkToDeath(this, 0)
            } catch (_: RemoteException) {
                stopped.countDown()
                notifyLost("Node service died while binding")
            } finally {
                connected.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected.countDown()
            stopped.countDown()
            notifyLost("Node service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            connected.countDown()
            stopped.countDown()
            notifyLost("Node service binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            connected.countDown()
            stopped.countDown()
            notifyLost("Node service returned no binder")
        }

        @Synchronized
        override fun binderDied() {
            stopped.countDown()
            notifyLost("Node service process died")
        }

        private fun notifyLost(message: String) {
            if (connection !== this || generation != session) return
            unbind()
            onServiceLost(message)
        }

        @Synchronized
        fun unbind() {
            if (!bound) return
            bound = false
            try {
                context.unbindService(this)
            } catch (_: Exception) {
            }
        }

        fun stop(): Boolean {
            awaitConnection()
            val target = binder
            unbind()
            requestStop()
            awaitStopped(target)
            binder = null
            return target != null && !target.isBinderAlive
        }

        private fun requestStop() {
            try {
                context.stopService(Intent(context, NodeService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun awaitConnection() {
            if (bound && binder == null) {
                try {
                    connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun awaitStopped(target: IBinder?) {
            if (target != null && target.isBinderAlive) {
                try {
                    stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private inner class ResponseStream(
        `in`: InputStream,
        private val response: Response,
        private val call: Call,
    ) : FilterInputStream(`in`) {
        @Throws(IOException::class)
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
                calls.remove(call)
            }
        }
    }

    class NodeHttpException(val code: Int) : IOException("Node HTTP $code")

    class NodeResponseException(message: String) : IOException(message)
}
