package com.mewbox.tv.bean

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 内部 Webview 启动参数配置（对齐 FongMi Web 实体模型）
 */
@Serializable
data class WebOptions(
    val url: String = "",
    val title: String = "",
    val mode: String = "browser",
    val cookie: List<CookieCheckGroup> = emptyList(),
) {
    fun isValid(): Boolean =
        url.isNotBlank() && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    fun hasCookies(): Boolean = cookie.isNotEmpty()

    fun toJsonString(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        fun fromJsonElement(element: JsonElement?): WebOptions {
            if (element == null) return WebOptions()
            return when (element) {
                is JsonPrimitive -> {
                    val s = element.content.trim()
                    if (s.startsWith("{")) fromJsonString(s) else WebOptions(url = s)
                }
                is JsonObject -> {
                    val url = (element["url"] as? JsonPrimitive)?.content.orEmpty()
                    val title = (element["title"] as? JsonPrimitive)?.content.orEmpty()
                    val mode = (element["mode"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "browser" }
                    val cookies = parseCookieGroups(element["cookie"])
                    WebOptions(url = url, title = title, mode = mode, cookie = cookies)
                }
                else -> WebOptions()
            }
        }

        fun fromJsonString(str: String): WebOptions {
            val trimmed = str.trim()
            if (!trimmed.startsWith("{")) {
                return WebOptions(url = trimmed)
            }
            return try {
                val element = json.parseToJsonElement(trimmed)
                fromJsonElement(element)
            } catch (_: Exception) {
                WebOptions(url = trimmed)
            }
        }

        private fun parseCookieGroups(element: JsonElement?): List<CookieCheckGroup> {
            val array = element as? JsonArray ?: return emptyList()
            return array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val groupUrl = (obj["url"] as? JsonPrimitive)?.content.orEmpty()
                val keys = (obj["keys"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                if (keys.isEmpty()) null else CookieCheckGroup(url = groupUrl, keys = keys)
            }
        }
    }
}

@Serializable
data class CookieCheckGroup(
    val url: String = "",
    val keys: List<String> = emptyList(),
)

