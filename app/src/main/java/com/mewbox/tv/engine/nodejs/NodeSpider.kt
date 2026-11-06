package com.mewbox.tv.engine.nodejs

import com.fongmi.nodejs.NodeClient
import com.fongmi.nodejs.NodeRoute
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.catvod.Spider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Node.js 爬虫实现（对齐 FongMi 架构与协议）
 */
class NodeSpider(
    private val site: Site,
    private val client: NodeClient,
) : Spider {
    companion object {
        private const val LEGACY_CONFIG_ROUTE = "/spider/baseset/3"
        private val EMPTY_BODY = JsonObject(emptyMap())

        fun addMissingActions(result: String): String {
            return try {
                val root = Json.parseToJsonElement(result) as? JsonObject ?: return result
                val list = root["list"] as? JsonArray ?: return result
                var changed = false
                val newList = list.map { element ->
                    val item = element as? JsonObject
                    if (item != null) {
                        val id = item["vod_id"]?.jsonPrimitive?.content
                        if (!id.isNullOrEmpty() && id != "null" && !item.containsKey("action")) {
                            changed = true
                            val updated = item.toMutableMap()
                            updated["action"] = JsonPrimitive(id)
                            JsonObject(updated)
                        } else {
                            element
                        }
                    } else {
                        element
                    }
                }
                if (changed) {
                    val newRoot = root.toMutableMap()
                    newRoot["list"] = JsonArray(newList)
                    JsonObject(newRoot).toString()
                } else {
                    result
                }
            } catch (_: Exception) {
                result
            }
        }
    }

    @Volatile private var initialized = false

    private suspend fun <T> runNodeIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val thread = Thread.currentThread()
        val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                client.cancelCallsForThread(thread)
            }
        }
        try {
            block()
        } finally {
            handle?.dispose()
        }
    }

    private suspend fun ensureInit() {
        if (!initialized && site.ext.isNotBlank()) {
            init(site.ext)
            initialized = true
        }
    }

    override suspend fun init(ext: String) = runNodeIo {
        val body = buildJsonObject {
            val value = ext.ifEmpty { site.ext }
            put("ext", value)
            put("extend", value)
        }
        client.initialize(site.api, body)
        initialized = true
    }

    override suspend fun homeContent(filter: Boolean): Result = runNodeIo {
        ensureInit()
        val body = buildJsonObject { put("filter", filter) }
        val raw = addLegacyConfigActions(call("/home", body))
        tagResult(ResultParser.parseResult(raw))
    }

    override suspend fun homeVideoContent(): Result = runNodeIo {
        ensureInit()
        var raw = client.postOptional(NodeRoute.append(site.api, "/homeVod"), EMPTY_BODY)
        if (raw.isEmpty()) {
            raw = client.postOptional(NodeRoute.append(site.api, "/homeVideo"), EMPTY_BODY)
        }
        tagResult(ResultParser.parseResult(addLegacyConfigActions(raw)))
    }

    override suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: Map<String, String>,
    ): Result = runNodeIo {
        ensureInit()
        val body = buildJsonObject {
            put("id", tid)
            put("page", pg)
            put("filter", filter)
            put("filters", buildJsonObject {
                for ((k, v) in extend) {
                    put(k, v)
                }
            })
        }
        val raw = addLegacyConfigActions(call("/category", body))
        tagResult(ResultParser.parseResult(raw))
    }

    override suspend fun detailContent(ids: List<String>): Result = runNodeIo {
        ensureInit()
        val body = buildJsonObject {
            put("id", ids.firstOrNull().orEmpty())
        }
        val raw = call("/detail", body)
        tagResult(ResultParser.parseResult(raw))
    }

    override suspend fun searchContent(keyword: String, quick: Boolean, pg: String): Result = runNodeIo {
        ensureInit()
        val body = buildJsonObject {
            put("wd", keyword)
            put("quick", quick)
            put("page", pg)
        }
        val raw = call("/search", body)
        tagResult(ResultParser.parseResult(raw))
    }

    override suspend fun playerContent(flag: String, id: String, vipFlags: List<String>): Result = runNodeIo {
        ensureInit()
        client.setPlayInfo(flag, id)
        val body = buildJsonObject {
            put("flag", flag)
            put("id", id)
            put("flags", buildJsonArray {
                for (vipFlag in vipFlags) {
                    add(JsonPrimitive(vipFlag))
                }
            })
        }
        val raw = call("/play", body)
        tagResult(ResultParser.parseResult(raw))
    }

    override suspend fun action(action: String): Result = runNodeIo {
        ensureInit()
        val body = buildJsonObject { put("action", action) }
        var raw: String? = client.postOrNull(NodeRoute.append(site.api, "/action"), body)
        if (raw.isNullOrEmpty() && (isLegacyConfigSpider() || action.startsWith("{") || Vod.isActionId(action))) {
            val detailBody = buildJsonObject { put("id", action) }
            raw = call("/detail", detailBody)
        }
        if (raw.isNullOrEmpty()) return@runNodeIo Result()
        try {
            val response = Json.parseToJsonElement(raw) as? JsonObject
            val web = response?.get("web") as? JsonObject
            if (response != null && web != null) {
                val updated = response.toMutableMap()
                updated["web"] = client.normalizeWeb(web)
                tagResult(ResultParser.parseResult(JsonObject(updated).toString()))
            } else {
                tagResult(ResultParser.parseResult(raw))
            }
        } catch (_: Exception) {
            tagResult(ResultParser.parseResult(raw))
        }
    }

    private fun tagResult(result: Result): Result {
        return result.copy(list = result.list.map { it.copy(siteKey = site.key, siteName = site.name) })
    }

    private fun addLegacyConfigActions(result: String): String {
        return if (isLegacyConfigSpider()) addMissingActions(result) else result
    }

    private fun isLegacyConfigSpider(): Boolean {
        val route = try {
            if (site.api.startsWith("node:")) NodeRoute.path(site.api) else site.api
        } catch (_: Exception) {
            site.api
        }
        return route == LEGACY_CONFIG_ROUTE ||
            route.startsWith("$LEGACY_CONFIG_ROUTE?") ||
            site.key.contains("baseset", ignoreCase = true) ||
            route.contains("baseset", ignoreCase = true)
    }

    private fun call(route: String, body: JsonObject): String {
        return client.post(NodeRoute.append(site.api, route), body)
    }
}

