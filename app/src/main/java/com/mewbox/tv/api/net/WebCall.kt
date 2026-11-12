package com.mewbox.tv.api.net

import android.util.Base64
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.api.parser.optBoolean
import com.mewbox.tv.api.parser.optLong
import com.mewbox.tv.api.parser.optObj
import com.mewbox.tv.api.parser.optString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网页端跨站 HTTP 请求执行器（对齐 MewPlayer WebCall）。
 * 支持完整自定义请求头、重定向控制、Gzip/Deflate 解压、二进制 Base64 编码及 WebView Cookie 双向同步。
 */
@Singleton
class WebCall @Inject constructor(
    @Named("api") private val apiClient: OkHttpClient,
) {
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_TIMEOUT_MS = 60_000L
        private const val MAX_BODY_BYTES = 12 * 1024 * 1024
    }

    fun execute(params: JsonObject?): JsonObject {
        requireNotNull(params) { "request params is required" }
        val url = params.optString("url", "").trim()
        require(CookieBridge.isHttpUrl(url)) { "invalid http/https url" }

        val method = params.optString("method", "GET").trim().ifEmpty { "GET" }.uppercase(Locale.ROOT)
        val timeoutMs = params.optLong("timeout", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val followRedirects = resolveFollowRedirects(params)
        val withCredentials = resolveWithCredentials(params)
        val responseType = params.optString("responseType", "auto").trim().lowercase(Locale.ROOT)
        val bodyEncoding = params.optString("bodyEncoding", "utf8").trim().lowercase(Locale.ROOT)

        val headersMap = extractHeaders(params.optObj("headers"))
        if (withCredentials) {
            CookieBridge.attachCookieHeader(headersMap, url)
        }

        val requestBody = buildRequestBody(method, params["body"], bodyEncoding, headersMap)
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(
                Headers.Builder().apply {
                    for ((k, v) in headersMap) {
                        add(k, v)
                    }
                }.build()
            )
            .method(method, requestBody)

        val callClient = apiClient.newBuilder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        callClient.newCall(requestBuilder.build()).execute().use { response ->
            if (withCredentials) {
                response.priorResponse?.let { prior ->
                    CookieBridge.storeResponseCookies(prior, url)
                }
                CookieBridge.storeResponseCookies(response, url)
            }

            val rawBytes = readBoundedBytes(response.body?.byteStream(), MAX_BODY_BYTES)
            val contentEncoding = response.header("Content-Encoding").orEmpty().lowercase(Locale.ROOT)
            val decodedBytes = decodeContentBytes(rawBytes, contentEncoding)
            val contentType = response.header("Content-Type").orEmpty()
            val charset = resolveCharset(contentType)

            val headersJson = buildJsonObject {
                val multiMap = response.headers.toMultimap()
                for ((name, values) in multiMap) {
                    if (values.isEmpty()) continue
                    put(name, values.joinToString("\n"))
                }
                val setCookies = response.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    put(
                        "set-cookie",
                        buildJsonArray {
                            setCookies.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
            }

            return buildJsonObject {
                put("ok", response.isSuccessful)
                put("status", response.code)
                put("statusText", response.message)
                put("url", response.request.url.toString())
                put("headers", headersJson)

                if (responseType == "base64") {
                    val b64 = Base64.encodeToString(decodedBytes, Base64.NO_WRAP)
                    put("body", b64)
                    put("data", b64)
                    put("base64", true)
                } else {
                    val text = String(decodedBytes, charset)
                    put("body", text)
                    put("base64", false)
                    val dataElement = when (responseType) {
                        "json" -> parseJsonOrText(text)
                        "text" -> JsonPrimitive(text)
                        else -> if (looksLikeJson(contentType, text)) parseJsonOrText(text) else JsonPrimitive(text)
                    }
                    put("data", dataElement)
                }
            }
        }
    }

    private fun resolveFollowRedirects(params: JsonObject): Boolean {
        if (params.containsKey("followRedirects")) {
            return params.optBoolean("followRedirects", true)
        }
        val redirect = params["redirect"] as? JsonPrimitive ?: return true
        redirect.booleanOrNull?.let { return it }
        val str = redirect.content.trim()
        return !str.equals("manual", ignoreCase = true) && !str.equals("error", ignoreCase = true)
    }

    private fun resolveWithCredentials(params: JsonObject): Boolean {
        if (params.containsKey("withCredentials")) {
            return params.optBoolean("withCredentials", true)
        }
        val credentials = params.optString("credentials", "include").trim().lowercase(Locale.ROOT)
        return credentials != "omit"
    }

    private fun extractHeaders(headersObj: JsonObject?): MutableMap<String, String> {
        val raw = LinkedHashMap<String, String>()
        if (headersObj != null) {
            for ((key, value) in headersObj) {
                if (value !is JsonNull) {
                    raw[key] = (value as? JsonPrimitive)?.content ?: value.toString()
                }
            }
        }
        return CookieBridge.sanitizeHeaders(raw)
    }

    private fun buildRequestBody(
        method: String,
        bodyEl: JsonElement?,
        bodyEncoding: String,
        headers: Map<String, String>,
    ): RequestBody? {
        if (!HttpMethod.permitsRequestBody(method)) return null
        val contentTypeHeader = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        if (bodyEl == null || bodyEl is JsonNull) {
            return if (HttpMethod.requiresRequestBody(method)) {
                ByteArray(0).toRequestBody(contentTypeHeader?.toMediaTypeOrNull())
            } else {
                null
            }
        }
        if (bodyEncoding == "base64" && bodyEl is JsonPrimitive) {
            val bytes = runCatching { Base64.decode(bodyEl.content, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            return bytes.toRequestBody((contentTypeHeader ?: "application/octet-stream").toMediaTypeOrNull())
        }
        return when (bodyEl) {
            is JsonObject, is JsonArray -> {
                val mediaType = (contentTypeHeader ?: "application/json; charset=utf-8").toMediaTypeOrNull()
                bodyEl.toString().toRequestBody(mediaType)
            }
            is JsonPrimitive -> {
                val mediaType = (contentTypeHeader ?: "text/plain; charset=utf-8").toMediaTypeOrNull()
                bodyEl.content.toRequestBody(mediaType)
            }
        }
    }

    private fun readBoundedBytes(input: java.io.InputStream?, maxBytes: Int): ByteArray {
        if (input == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            require(total <= maxBytes) { "response body exceeds limit ($maxBytes bytes)" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeContentBytes(raw: ByteArray, contentEncoding: String): ByteArray {
        if (raw.isEmpty()) return raw
        return try {
            when {
                contentEncoding.contains("gzip") -> {
                    GZIPInputStream(ByteArrayInputStream(raw)).use { readBoundedBytes(it, MAX_BODY_BYTES) }
                }
                contentEncoding.contains("deflate") -> {
                    InflaterInputStream(ByteArrayInputStream(raw), Inflater(true)).use { readBoundedBytes(it, MAX_BODY_BYTES) }
                }
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun resolveCharset(contentType: String): Charset {
        if (contentType.isNotBlank()) {
            val mediaType = contentType.toMediaTypeOrNull()
            val charset = mediaType?.charset()
            if (charset != null) return charset
        }
        return StandardCharsets.UTF_8
    }

    private fun looksLikeJson(contentType: String, text: String): Boolean {
        if (contentType.contains("json", ignoreCase = true)) return true
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private fun parseJsonOrText(text: String): JsonElement {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return JsonPrimitive(text)
        return ResultParser.parseJson(trimmed) ?: JsonPrimitive(text)
    }
}
