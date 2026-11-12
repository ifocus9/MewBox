package com.mewbox.tv.api.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * [JsonObject] 高容错读取扩展（对齐项目 Kotlinx Serialization 技术栈）。
 */
fun JsonObject?.optString(key: String, default: String = ""): String {
    val el = this?.get(key) ?: return default
    if (el is JsonNull) return default
    return (el as? JsonPrimitive)?.content ?: el.toString()
}

fun JsonObject?.optInt(key: String, default: Int = 0): Int {
    val el = this?.get(key) as? JsonPrimitive ?: return default
    return el.intOrNull ?: el.content.toIntOrNull() ?: default
}

fun JsonObject?.optLong(key: String, default: Long = 0L): Long {
    val el = this?.get(key) as? JsonPrimitive ?: return default
    return el.longOrNull ?: el.content.toLongOrNull() ?: default
}

fun JsonObject?.optBoolean(key: String, default: Boolean = false): Boolean {
    val el = this?.get(key) as? JsonPrimitive ?: return default
    return el.booleanOrNull ?: when (el.content.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> default
    }
}

fun JsonObject?.optObj(key: String): JsonObject? = this?.get(key) as? JsonObject

fun JsonObject?.optArr(key: String): JsonArray? = this?.get(key) as? JsonArray

fun String?.toJsonObjectOrEmpty(): JsonObject {
    val text = this?.trim().orEmpty()
    if (text.isEmpty() || text == "null" || text == "undefined") return JsonObject(emptyMap())
    val el = ResultParser.parseJson(text)
    return el as? JsonObject ?: JsonObject(mapOf("value" to (el ?: JsonNull)))
}

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(this.toString())
}

fun quoteJsString(value: String): String = JsonPrimitive(value).toString()

