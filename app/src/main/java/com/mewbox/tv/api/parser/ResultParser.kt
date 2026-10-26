package com.mewbox.tv.api.parser

import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.PlayUrl
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.bean.VodClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 基于 JsonElement 树的高容错解析器。
 * 严禁强类型 DTO 直接反序列化整个响应：不同源字段类型差异很大。
 */
object ResultParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val FLAG_SEP = "\$\$\$"

    fun parseJson(text: String): JsonElement? = try {
        json.parseToJsonElement(text.trim().removePrefix("\uFEFF"))
    } catch (_: Exception) {
        null
    }

    fun parseResult(text: String): Result {
        val root = parseJson(text) as? JsonObject ?: return Result()
        val (primaryUrl, playUrls) = parsePlayUrls(root["url"])
        return Result(
            classes = root["class"].asArray().mapNotNull(::parseClass),
            list = root["list"].asArray().mapNotNull(::parseVod),
            url = primaryUrl,
            playUrls = playUrls,
            header = parseHeader(root["header"]),
            parse = root["parse"].int(),
            jx = root["jx"].int(),
            flag = root["flag"].str(),
            page = root["page"].int(1).coerceAtLeast(1),
            pageCount = root["pagecount"].int(1).coerceAtLeast(1),
            total = root["total"].int(),
        )
    }

    /** 对齐 FongMi UrlAdapter：支持 String 单链接、[名称, 链接, ...] 键值对数组、纯 URL 数组。 */
    fun parsePlayUrls(element: JsonElement?): Pair<String, List<PlayUrl>> {
        if (element == null || element is JsonNull) return "" to emptyList()
        when (element) {
            is JsonPrimitive -> {
                val s = element.content.trim()
                return if (s.isBlank()) "" to emptyList() else s to listOf(PlayUrl("默认", s))
            }
            is JsonArray -> {
                val list = mutableListOf<PlayUrl>()
                val items = element.map { if (it is JsonPrimitive && it !is JsonNull) it.content.trim() else "" }

                // 模式 1：成对出现 [清晰度名称, 播放地址, ...] (FongMi 标准，如 ["无限4k", "https://..."])
                if (items.size >= 2 && items.size % 2 == 0) {
                    for (i in items.indices step 2) {
                        val name = items[i]
                        val url = items[i + 1]
                        if (url.isNotBlank()) {
                            list.add(PlayUrl(name.ifBlank { "清晰度 ${(i / 2) + 1}" }, url))
                        }
                    }
                }

                // 模式 2：非成对数组或纯播放地址列表
                if (list.isEmpty()) {
                    items.filter { it.isNotBlank() }.forEachIndexed { idx, u ->
                        list.add(PlayUrl("清晰度 ${idx + 1}", u))
                    }
                }

                val primaryUrl = list.firstOrNull {
                    it.url.startsWith("http://", ignoreCase = true) || it.url.startsWith("https://", ignoreCase = true)
                }?.url ?: list.firstOrNull()?.url ?: ""

                return primaryUrl to list
            }
            is JsonObject -> {
                val u = element["url"]?.str() ?: element["v"]?.str() ?: ""
                return if (u.isNotBlank()) u to listOf(PlayUrl("默认", u)) else "" to emptyList()
            }
            else -> return "" to emptyList()
        }
    }

    private fun parseClass(e: JsonElement): VodClass? {
        val o = e as? JsonObject ?: return null
        val id = o["type_id"].str()
        if (id.isEmpty()) return null
        return VodClass(typeId = id, typeName = o["type_name"].str())
    }

    private fun parseVod(e: JsonElement): Vod? {
        val o = e as? JsonObject ?: return null
        val from = o["vod_play_from"].str()
        val url = o["vod_play_url"].str()
        return Vod(
            vodId = o["vod_id"].str(),
            vodName = o["vod_name"].str(),
            vodPic = o["vod_pic"].str(),
            vodRemarks = o["vod_remarks"].str(),
            vodYear = o["vod_year"].str(),
            vodArea = o["vod_area"].str(),
            vodActor = o["vod_actor"].str(),
            vodDirector = o["vod_director"].str(),
            vodContent = o["vod_content"].str(),
            typeName = o["type_name"].str(),
            vodPlayFrom = from,
            vodPlayUrl = url,
            playFlags = parseFlags(from, url),
        )
    }

    /**
     * 两级剧集分割：`$$$` 分线路，`#` 分单集，`$`（limit=2）分 `集数名$播放ID`。
     * 播放 ID 视为不透明字符串（可能含 `&`、`=`、`||`）。
     */
    fun parseFlags(playFrom: String, playUrl: String): List<Flag> {
        if (playFrom.isBlank() || playUrl.isBlank()) return emptyList()
        val froms = playFrom.split(FLAG_SEP)
        val urls = playUrl.split(FLAG_SEP)
        return froms.mapIndexedNotNull { i, from ->
            val group = urls.getOrNull(i) ?: return@mapIndexedNotNull null
            val episodes = group.split("#")
                .filter { it.isNotBlank() }
                .mapIndexed { index, item ->
                    val parts = item.split("$", limit = 2)
                    if (parts.size == 2) Episode(parts[0].trim(), parts[1], index)
                    else Episode("正片", parts[0], index)
                }
            if (episodes.isEmpty()) null else Flag(from.trim(), episodes)
        }
    }

    /** 解析订阅 config.json 中的 sites；仅处理 sites，其余顶层字段忽略。 */
    fun parseSites(text: String): List<Site> {
        val root = parseJson(text) as? JsonObject ?: return emptyList()
        return root["sites"].asArray().mapIndexedNotNull { index, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull null
            val key = o["key"].str()
            if (key.isBlank()) return@mapIndexedNotNull null
            Site(
                key = key,
                name = o["name"].str().ifBlank { key },
                type = o["type"].int(),
                api = o["api"].str(),
                ext = o["ext"].let { if (it is JsonPrimitive) it.str() else if (it == null || it is JsonNull) "" else it.toString() },
                jar = o["jar"].str(),
                searchable = o["searchable"].int(1) == 1,
                quickSearch = o["quickSearch"].int(0) == 1,
                filterable = o["filterable"].int(1) == 1,
                indexs = o["indexs"].int(0) == 1,
                changeable = o["changeable"].int(1) == 1,
                hide = o["hide"].int(0) == 1,
                priority = index,
            )
        }
    }

    /** header 兼容 JSON 对象与嵌套 JSON 字符串。 */
    private fun parseHeader(e: JsonElement?): Map<String, String> {
        val obj = when (e) {
            is JsonObject -> e
            is JsonPrimitive -> (parseJson(e.str()) as? JsonObject)
            else -> null
        } ?: return emptyMap()
        return obj.mapValues { it.value.str() }
    }

    // ---- 容错取值：list/class 允许是 ""、false、null；id 允许是 Int 或 String ----

    private fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

    private fun JsonElement?.str(): String {
        val p = this as? JsonPrimitive ?: return ""
        return if (p is JsonNull) "" else p.content
    }

    private fun JsonElement?.int(default: Int = 0): Int {
        val p = this as? JsonPrimitive ?: return default
        if (p is JsonNull) return default
        val c = p.content
        return c.toIntOrNull()
            ?: c.toDoubleOrNull()?.toInt()
            ?: when (c.lowercase()) {
                "true" -> 1
                "false" -> 0
                else -> default
            }
    }
}
