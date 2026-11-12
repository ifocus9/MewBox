package com.mewbox.tv.api.net

import android.net.Uri
import android.util.Base64
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.api.parser.optBoolean
import com.mewbox.tv.api.parser.optLong
import com.mewbox.tv.api.parser.optObj
import com.mewbox.tv.api.parser.optString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import timber.log.Timber
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 本地 `/webResource` 媒体与防盗链资源代理网关（对齐 MewPlayer WebResourceGateway）。
 *
 * 供网页端 `<img src>`, `<audio src>`, `<video src>`, `fetch()` 通过 `window.fm.res(url, options)` 调用，
 * 自动注入 Referer / User-Agent / Cookie、转发 Range 分片请求并附带 CORS 响应头。
 */
@Singleton
class WebResourceGateway @Inject constructor(
    @Named("api") private val apiClient: OkHttpClient,
) {
    companion object {
        const val PATH = "/webResource"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MIN_TIMEOUT_MS = 2_000L
        private const val MAX_TIMEOUT_MS = 120_000L

        private val PASSTHROUGH_REQUEST_HEADERS = listOf(
            "range",
            "if-range",
            "if-none-match",
            "if-modified-since",
            "accept",
        )

        private val PASSTHROUGH_RESPONSE_HEADERS = listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "Cache-Control",
            "ETag",
            "Last-Modified",
            "Content-Disposition",
            "Location",
        )

        fun buildProxyUrl(port: Int, targetUrl: String, options: JsonObject? = null): String {
            require(CookieBridge.isHttpUrl(targetUrl)) { "invalid http/https url" }
            val builder = Uri.Builder()
                .scheme("http")
                .encodedAuthority("127.0.0.1:$port")
                .encodedPath(PATH)
                .appendQueryParameter("url", targetUrl.trim())

            if (options != null) {
                val method = options.optString("method", "").trim()
                if (method.isNotEmpty()) builder.appendQueryParameter("method", method.uppercase(Locale.ROOT))

                val headersObj = options.optObj("headers")
                if (!headersObj.isNullOrEmpty()) {
                    val encodedHeaders = Base64.encodeToString(
                        headersObj.toString().toByteArray(StandardCharsets.UTF_8),
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                    )
                    builder.appendQueryParameter("headers", encodedHeaders)
                }

                if (options.containsKey("withCredentials")) {
                    val cred = if (options.optBoolean("withCredentials", true)) "include" else "omit"
                    builder.appendQueryParameter("credentials", cred)
                } else {
                    val cred = options.optString("credentials", "").trim()
                    if (cred.isNotEmpty()) builder.appendQueryParameter("credentials", cred)
                }

                if (options.containsKey("followRedirects")) {
                    val redirect = if (options.optBoolean("followRedirects", true)) "follow" else "manual"
                    builder.appendQueryParameter("redirect", redirect)
                } else if (options.containsKey("redirect")) {
                    val redirectOpt = options["redirect"] as? JsonPrimitive
                    val redirect = when {
                        redirectOpt == null -> ""
                        redirectOpt.booleanOrNull != null -> if (redirectOpt.booleanOrNull == true) "follow" else "manual"
                        else -> redirectOpt.content.trim()
                    }
                    if (redirect.isNotEmpty()) builder.appendQueryParameter("redirect", redirect)
                }

                val timeout = options.optLong("timeout", 0L)
                if (timeout > 0L) builder.appendQueryParameter("timeout", timeout.toString())

                val contentType = options.optString("contentType", "").trim()
                if (contentType.isNotEmpty()) builder.appendQueryParameter("contentType", contentType)

                val filename = options.optString("filename", "").trim()
                if (filename.isNotEmpty()) builder.appendQueryParameter("filename", filename)
            }
            return builder.build().toString()
        }
    }

    fun handle(
        httpMethod: String,
        rawQuery: String,
        clientHeaders: Map<String, String>,
        requestBodyBytes: ByteArray?,
        output: OutputStream,
    ) {
        if (httpMethod.equals("OPTIONS", ignoreCase = true)) {
            writeCorsPreflight(output)
            return
        }

        val queryParams = parseQuery(rawQuery)
        val targetUrl = queryParams["url"]?.trim().orEmpty()
        if (!CookieBridge.isHttpUrl(targetUrl)) {
            writeSimpleError(output, 400, "Bad Request", "Missing or invalid url")
            return
        }

        val upstreamMethod = queryParams["method"]?.trim()?.ifEmpty { null }?.uppercase(Locale.ROOT)
            ?: httpMethod.uppercase(Locale.ROOT)
        val credentials = queryParams["credentials"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val withCredentials = credentials != "omit"
        val redirectMode = queryParams["redirect"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val followRedirects = redirectMode != "manual" && redirectMode != "error"
        val timeoutMs = (queryParams["timeout"]?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val overrideContentType = queryParams["contentType"]?.trim().orEmpty()
        val filename = queryParams["filename"]?.trim().orEmpty()

        val upstreamHeaders = decodeHeadersParam(queryParams["headers"])
        for (headerKey in PASSTHROUGH_REQUEST_HEADERS) {
            val clientVal = clientHeaders[headerKey] ?: continue
            if (upstreamHeaders.keys.none { it.equals(headerKey, ignoreCase = true) }) {
                CookieBridge.sanitizeHeaderValue(clientVal)?.let { cleanVal ->
                    upstreamHeaders[headerKey] = cleanVal
                }
            }
        }
        if (withCredentials) {
            CookieBridge.attachCookieHeader(upstreamHeaders, targetUrl)
        }

        val body = if (HttpMethod.permitsRequestBody(upstreamMethod)) {
            val ct = upstreamHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                ?: clientHeaders["content-type"]
            val bytes = requestBodyBytes ?: ByteArray(0)
            if (bytes.isNotEmpty() || HttpMethod.requiresRequestBody(upstreamMethod)) {
                bytes.toRequestBody(ct?.toMediaTypeOrNull())
            } else {
                null
            }
        } else {
            null
        }

        val request = Request.Builder()
            .url(targetUrl)
            .headers(
                Headers.Builder().apply {
                    for ((k, v) in upstreamHeaders) {
                        add(k, v)
                    }
                }.build()
            )
            .method(upstreamMethod, body)
            .build()

        val callClient = apiClient.newBuilder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        try {
            callClient.newCall(request).execute().use { response ->
                if (withCredentials) {
                    response.priorResponse?.let { CookieBridge.storeResponseCookies(it, targetUrl) }
                    CookieBridge.storeResponseCookies(response, targetUrl)
                }

                val statusCode = response.code
                val statusMessage = response.message.ifBlank { "OK" }
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(statusMessage).append("\r\n")
                sb.append("Access-Control-Allow-Origin: *\r\n")
                sb.append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n")
                sb.append("Access-Control-Allow-Headers: *\r\n")
                sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type, ETag, Last-Modified, Location\r\n")

                var hasContentType = false
                var hasContentDisposition = false
                for (headerName in PASSTHROUGH_RESPONSE_HEADERS) {
                    val value = response.header(headerName) ?: continue
                    if (headerName.equals("Content-Type", ignoreCase = true)) {
                        hasContentType = true
                        val finalCt = overrideContentType.ifEmpty { value }
                        sb.append("Content-Type: ").append(finalCt).append("\r\n")
                    } else if (headerName.equals("Content-Disposition", ignoreCase = true)) {
                        hasContentDisposition = true
                        sb.append("Content-Disposition: ").append(value).append("\r\n")
                    } else {
                        sb.append(headerName).append(": ").append(value).append("\r\n")
                    }
                }
                if (!hasContentType && overrideContentType.isNotEmpty()) {
                    sb.append("Content-Type: ").append(overrideContentType).append("\r\n")
                }
                if (!hasContentDisposition && filename.isNotEmpty()) {
                    val safeName = filename.replace("\"", "").replace("\r", "").replace("\n", "")
                    sb.append("Content-Disposition: inline; filename=\"").append(safeName).append("\"\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))

                if (!upstreamMethod.equals("HEAD", ignoreCase = true)) {
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                output.flush()
            }
        } catch (e: Exception) {
            Timber.w(e, "WebResourceGateway 请求上游失败: %s", targetUrl)
            runCatching {
                writeSimpleError(output, 502, "Bad Gateway", e.message ?: "Upstream request failed")
            }
        }
    }

    private fun writeCorsPreflight(output: OutputStream) {
        val header = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun writeSimpleError(output: OutputStream, code: Int, status: String, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $code $status\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            } else {
                val k = URLDecoder.decode(pair, "UTF-8")
                map[k] = ""
            }
        }
        return map
    }

    private fun decodeHeadersParam(raw: String?): MutableMap<String, String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return LinkedHashMap()
        val jsonText = if (text.startsWith("{")) {
            text
        } else {
            runCatching {
                String(Base64.decode(text, Base64.URL_SAFE or Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrDefault("")
        }
        if (jsonText.isEmpty()) return LinkedHashMap()
        val result = LinkedHashMap<String, String>()
        val obj = ResultParser.parseJson(jsonText) as? JsonObject
        if (obj != null) {
            for ((k, v) in obj) {
                if (v !is JsonNull) {
                    result[k] = (v as? JsonPrimitive)?.content ?: v.toString()
                }
            }
        }
        return CookieBridge.sanitizeHeaders(result)
    }
}
