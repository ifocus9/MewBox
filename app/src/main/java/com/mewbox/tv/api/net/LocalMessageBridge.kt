package com.mewbox.tv.api.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.widget.Toast
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.WebOptions
import com.mewbox.tv.ui.web.WebActivity
import com.mewbox.tv.ui.web.WebViewCookies
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地进程间通信/爬虫桥接服务（完全对齐 FongMi NodeBridge / messageToDart 协议标准）
 *
 * 核心功能：
 * 1. 监听 127.0.0.1 本地回环端口（默认 9977，被占用时自动切换可用端口）。
 * 2. 接收来自 Node.js / CatVod 爬虫脚本发送的 POST /msg 消息请求。
 * 3. 完美支持 openInternalWebview / openWeb 动作，调起内部 Webview 打开指定 URL（如扫码登录、扩展后台等）。
 * 4. 支持 getCookie / setCookie / getUserAgent / toast 等常用宿主能力反向注入。
 */
@Singleton
class LocalMessageBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val DEFAULT_PORT = 9977
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val SUCCESS_JSON = """{"success":true,"code":0}"""
        private const val FAILURE_JSON = """{"success":false,"code":-1}"""
    }

    interface MessageHandler {
        fun onMessage(action: String, opt: JsonElement?): String?
    }

    private val handlers = CopyOnWriteArrayList<MessageHandler>()
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var listeningPort: Int = 0
        private set

    @Volatile
    var token: String? = null

    fun addHandler(handler: MessageHandler) {
        handlers.add(handler)
    }

    fun removeHandler(handler: MessageHandler) {
        handlers.remove(handler)
    }

    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            return listeningPort
        }

        val loopback = InetAddress.getByName("127.0.0.1")
        val socket = try {
            ServerSocket(preferredPort, 50, loopback)
        } catch (_: Exception) {
            Timber.w("端口 $preferredPort 被占用，正在回退绑定任意可用端口...")
            ServerSocket(0, 50, loopback)
        }

        serverSocket = socket
        listeningPort = socket.localPort
        Timber.i("LocalMessageBridge 桥接服务已启动，监听地址: http://127.0.0.1:$listeningPort/msg")

        job = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch {
                        handleClient(client)
                    }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Timber.e(e, "桥接客户端接入异常")
                }
            }
        }

        return listeningPort
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.e(e, "关闭桥接套接字失败")
        }
        serverSocket = null
        job?.cancel()
        job = null
        listeningPort = 0
        Timber.i("LocalMessageBridge 桥接服务已停止")
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val output = s.getOutputStream()

                val requestLine = reader.readLine() ?: return@use
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@use
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                val headers = HashMap<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        headers[key] = value
                    }
                    line = reader.readLine()
                }

                if (method != "POST" || path != "/msg") {
                    writeResponse(output, 404, "Not Found", "application/json", "{}")
                    return@use
                }

                // 鉴权检验（对齐 FongMi X-CatVod-Token）
                val currentToken = token
                if (!currentToken.isNullOrEmpty()) {
                    val headerToken = headers["x-catvod-token"]
                    if (headerToken != currentToken) {
                        writeResponse(output, 403, "Forbidden", "application/json; charset=utf-8", FAILURE_JSON)
                        return@use
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > MAX_BODY_BYTES) {
                    writeResponse(output, 413, "Payload Too Large", "application/json; charset=utf-8", FAILURE_JSON)
                    return@use
                }

                val body = if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(charArray, read, contentLength - read)
                        if (count == -1) break
                        read += count
                    }
                    String(charArray, 0, read)
                } else {
                    ""
                }

                val responseBody = dispatchMessage(body)
                writeResponse(output, 200, "OK", "application/json; charset=utf-8", responseBody)
            } catch (e: Exception) {
                Timber.e(e, "处理桥接客户端请求异常")
            }
        }
    }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    /**
     * 核心调度逻辑（对齐 FongMi NodeRuntime.onMessage）
     */
    fun dispatchMessage(body: String): String {
        val root = ResultParser.parseJson(body) as? JsonObject ?: return FAILURE_JSON
        val action = (root["action"] as? JsonPrimitive)?.content.orEmpty()
        val opt = root["opt"]

        for (handler in handlers) {
            val handled = handler.onMessage(action, opt)
            if (handled != null) return handled
        }

        return when (action) {
            "openInternalWebview", "openWeb" -> {
                handleOpenInternalWebview(opt)
                SUCCESS_JSON
            }
            "toast" -> {
                val optObj = opt as? JsonObject
                val message = (optObj?.get("message") as? JsonPrimitive)?.content.orEmpty()
                if (message.isNotBlank()) {
                    mainHandler.post {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
                SUCCESS_JSON
            }
            "getCookie" -> {
                val optObj = opt as? JsonObject
                val url = (optObj?.get("url") as? JsonPrimitive)?.content.orEmpty()
                val cookie = WebViewCookies.get(url)
                """{"success":true,"code":0,"cookie":"$cookie"}"""
            }
            "setCookie" -> {
                val optObj = opt as? JsonObject
                val url = (optObj?.get("url") as? JsonPrimitive)?.content.orEmpty()
                val cookie = (optObj?.get("cookie") as? JsonPrimitive)?.content.orEmpty()
                val success = WebViewCookies.set(url, cookie)
                if (success) SUCCESS_JSON else FAILURE_JSON
            }
            "getUserAgent" -> {
                val ua = try {
                    WebSettings.getDefaultUserAgent(context)
                } catch (_: Exception) {
                    ""
                }
                """{"success":true,"code":0,"ua":"$ua"}"""
            }
            else -> {
                Timber.w("未知的桥接消息动作: $action")
                FAILURE_JSON
            }
        }
    }

    private fun handleOpenInternalWebview(opt: JsonElement?) {
        val webOptions = WebOptions.fromJsonElement(opt)
        if (!webOptions.isValid()) {
            Timber.w("打开内部 Webview 失败: 无效的选项 $opt")
            return
        }
        Timber.i("收到 messageToDart: 打开内部 Webview -> ${webOptions.url}")
        mainHandler.post {
            WebActivity.open(context, webOptions)
        }
    }
}
