package com.fongmi.nodejs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

object NodeConfigMapper {
    @JvmStatic
    @Throws(Exception::class)
    fun transform(json: String?): String {
        require(!json.isNullOrBlank()) { "Node /config JSON is null or blank" }
        var root = Json.parseToJsonElement(json) as? JsonObject
            ?: throw Exception("Node /config is not a JSON object")
        val dataObj = root["data"] as? JsonObject
        if (dataObj != null) {
            root = dataObj
        }
        val videoObj = root["video"] as? JsonObject
            ?: throw Exception("Node /config has no video object")
        val sites = mapSites(videoObj)
        val mutable = videoObj.toMutableMap()
        mutable["sites"] = JsonArray(sites)
        return JsonObject(mutable).toString()
    }

    private fun mapSites(config: JsonObject): List<JsonElement> {
        val sitesArray = config["sites"] as? JsonArray ?: return emptyList()
        val sites = mutableListOf<JsonElement>()
        for (element in sitesArray) {
            val site = element as? JsonObject ?: continue
            if (!isEnabled(site["enable"])) continue
            val route = route(site)
            if (route.isEmpty()) continue
            val mutable = site.toMutableMap()
            mutable["type"] = JsonPrimitive(3)
            mutable["api"] = JsonPrimitive("node:$route")
            sites.add(JsonObject(mutable))
        }
        return sites
    }

    private fun isEnabled(enabled: JsonElement?): Boolean {
        if (enabled == null) return true
        val primitive = enabled as? JsonPrimitive ?: return true
        primitive.booleanOrNull?.let { return it }
        primitive.intOrNull?.let { return it != 0 }
        val value = primitive.content
        return !value.equals("false", ignoreCase = true) && value != "0"
    }

    private fun route(site: JsonObject): String {
        val apiPrimitive = site["api"] as? JsonPrimitive
        var api = if (apiPrimitive != null && apiPrimitive.isString) apiPrimitive.content else ""
        if (api.isNotEmpty()) {
            val scheme = api.indexOf("://")
            if (scheme >= 0) {
                val slash = api.indexOf('/', scheme + 3)
                api = if (slash < 0) "/" else api.substring(slash)
            }
            return if (api.startsWith("/")) api else "/$api"
        }
        val keyPrimitive = site["key"] as? JsonPrimitive
        var key = if (keyPrimitive != null && keyPrimitive.isString) keyPrimitive.content else ""
        if (key.startsWith("nodejs_")) key = key.substring(7)
        if (key.isEmpty()) return ""
        val type = (site["type"] as? JsonPrimitive)?.intOrNull ?: 3
        return "/spider/$key/$type"
    }
}

