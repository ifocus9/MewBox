package com.mewbox.tv.domain

import com.mewbox.tv.api.net.CookieBridge
import com.mewbox.tv.api.parser.optArr
import com.mewbox.tv.api.parser.optBoolean
import com.mewbox.tv.api.parser.optInt
import com.mewbox.tv.api.parser.optLong
import com.mewbox.tv.api.parser.optObj
import com.mewbox.tv.api.parser.optString
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网页端内联点播虚拟站点仓库（对齐 MewPlayer WebHomeInlineVodStore）。
 *
 * 将网页通过 `player.playUrl` / `player.playVodInline` 传入的播放参数封装为内存虚拟站点 (`siteKey = "webhome_inline"`)，
 * 复用 MewBox 原生 [com.mewbox.tv.ui.detail.DirectPlayScreen] 与 [com.mewbox.tv.ui.detail.DetailViewModel] 进行播放与选集。
 */
@Singleton
class WebHomeInlineStore @Inject constructor() {

    companion object {
        const val KEY = "webhome_inline"
        private const val MAX_ENTRIES = 32
    }

    data class EpisodePlaySpec(
        val id: String,
        val name: String,
        val flag: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val format: String = "",
        val parse: Int = 0,
        val jx: Int = 0,
        val withCredentials: Boolean = true,
        val useResolver: Boolean = false,
    )

    data class InlineEntry(
        val vod: Vod,
        val site: Site,
        val episodeSpecs: Map<String, EpisodePlaySpec>,
        val defaultHeaders: Map<String, String>,
        val defaultFormat: String,
        val withCredentials: Boolean,
        val hasResolver: Boolean,
        val initialFlag: String,
        val initialEpisodeIndex: Int,
        val initialEpisodeName: String,
        val initialPosition: Long,
        val createdAt: Long = System.currentTimeMillis(),
    )

    fun interface InlineJsResolver {
        suspend fun resolve(vodId: String, flag: String, episodeId: String, episodeName: String): JsonObject?
    }

    private val seq = AtomicLong(1000L)
    private val entries = ConcurrentHashMap<String, InlineEntry>()
    private val pendingInitialSelections = ConcurrentHashMap<String, History>()

    @Volatile
    var jsResolver: InlineJsResolver? = null

    fun registerPlayUrl(params: JsonObject): InlineEntry {
        val rawUrl = params.optString("url", params.optString("playUrl", "")).trim()
        require(rawUrl.isNotEmpty()) { "playUrl requires non-empty url" }

        val title = firstNonBlank(
            params.optString("title"),
            params.optString("name"),
            params.optString("vodName"),
            "网页视频",
        )
        val pic = firstNonBlank(
            params.optString("pic"),
            params.optString("poster"),
            params.optString("vodPic"),
        )
        val siteName = firstNonBlank(
            params.optString("siteName"),
            params.optString("source"),
            "网页",
        )
        val flagName = firstNonBlank(
            params.optString("flag"),
            siteName,
            "默认",
        )
        val episodeName = firstNonBlank(
            params.optString("episodeName"),
            params.optString("epName"),
            title,
            "正片",
        )
        val vodId = firstNonBlank(
            params.optString("vodId"),
            params.optString("id"),
            "inline_${seq.incrementAndGet()}",
        )
        val withCredentials = !params.optString("credentials", "include").equals("omit", ignoreCase = true) &&
            params.optBoolean("withCredentials", true)
        val headers = extractHeaders(params.optObj("headers"), rawUrl, withCredentials)
        val format = firstNonBlank(params.optString("format"), params.optString("mimeType"))
        val parse = params.optInt("parse", if (params.optBoolean("sniff", false)) 1 else 0)
        val jx = params.optInt("jx", 0)
        val position = params.optLong("position", 0L).coerceAtLeast(0L)

        val epKey = "$vodId|0|0"
        val spec = EpisodePlaySpec(
            id = epKey,
            name = episodeName,
            flag = flagName,
            url = rawUrl,
            headers = headers,
            format = format,
            parse = parse,
            jx = jx,
            withCredentials = withCredentials,
            useResolver = false,
        )
        val flag = Flag(
            flag = flagName,
            episodes = listOf(Episode(name = episodeName, url = epKey, index = 0)),
        )
        val vod = Vod(
            vodId = vodId,
            vodName = title,
            vodPic = pic,
            vodRemarks = params.optString("remarks", params.optString("vodRemarks", "")),
            vodContent = params.optString("content", params.optString("desc", "")),
            vodPlayFrom = flagName,
            vodPlayUrl = "$episodeName$$epKey",
            siteKey = KEY,
            siteName = siteName,
            playFlags = listOf(flag),
        )
        val site = createVirtualSite(siteName)
        val entry = InlineEntry(
            vod = vod,
            site = site,
            episodeSpecs = mapOf(epKey to spec),
            defaultHeaders = headers,
            defaultFormat = format,
            withCredentials = withCredentials,
            hasResolver = false,
            initialFlag = flagName,
            initialEpisodeIndex = 0,
            initialEpisodeName = episodeName,
            initialPosition = position,
        )
        storeEntry(entry)
        return entry
    }

    fun registerPlayVodInline(params: JsonObject): InlineEntry {
        val vodObj = params.optObj("vod") ?: params
        val vodId = firstNonBlank(
            vodObj.optString("vod_id"),
            vodObj.optString("vodId"),
            vodObj.optString("id"),
            params.optString("vodId"),
            params.optString("id"),
            "inline_vod_${seq.incrementAndGet()}",
        )
        val vodName = firstNonBlank(
            vodObj.optString("vod_name"),
            vodObj.optString("vodName"),
            vodObj.optString("title"),
            vodObj.optString("name"),
            params.optString("title"),
            params.optString("name"),
            "网页视频",
        )
        val vodPic = firstNonBlank(
            vodObj.optString("vod_pic"),
            vodObj.optString("vodPic"),
            vodObj.optString("pic"),
            vodObj.optString("poster"),
            params.optString("pic"),
            params.optString("poster"),
        )
        val siteName = firstNonBlank(
            params.optString("siteName"),
            params.optString("source"),
            vodObj.optString("siteName"),
            vodObj.optString("type_name"),
            "网页",
        )
        val withCredentials = !params.optString("credentials", "include").equals("omit", ignoreCase = true) &&
            params.optBoolean("withCredentials", true)
        val defaultHeaders = extractHeaders(
            params.optObj("headers") ?: vodObj.optObj("headers"),
            "",
            withCredentials = false,
        )
        val defaultFormat = firstNonBlank(params.optString("format"), vodObj.optString("format"))
        val hasResolver = params.optBoolean("hasResolver", false) || vodObj.optBoolean("hasResolver", false)

        val specs = LinkedHashMap<String, EpisodePlaySpec>()
        val flags = parseFlagsAndEpisodes(
            vodId = vodId,
            params = params,
            vodObj = vodObj,
            defaultFlagName = firstNonBlank(params.optString("flag"), siteName, "播放"),
            defaultHeaders = defaultHeaders,
            defaultFormat = defaultFormat,
            withCredentials = withCredentials,
            hasResolver = hasResolver,
            outSpecs = specs,
        )
        require(flags.isNotEmpty() && flags.any { it.episodes.isNotEmpty() }) {
            "playVodInline requires at least one playable episode"
        }

        val reqFlagIndex = params.optInt("flagIndex", vodObj.optInt("flagIndex", 0)).coerceAtLeast(0)
        val reqFlagName = firstNonBlank(params.optString("flag"), vodObj.optString("flag"))
        val selectedFlag = flags.firstOrNull { reqFlagName.isNotEmpty() && it.flag == reqFlagName }
            ?: flags.getOrNull(reqFlagIndex)
            ?: flags.first()

        val reqEpIndex = params.optInt("episodeIndex", vodObj.optInt("episodeIndex", 0)).coerceAtLeast(0)
        val reqEpName = firstNonBlank(params.optString("episodeName"), vodObj.optString("episodeName"))
        val selectedEp = selectedFlag.episodes.firstOrNull { reqEpName.isNotEmpty() && it.name == reqEpName }
            ?: selectedFlag.episodes.getOrNull(reqEpIndex)
            ?: selectedFlag.episodes.first()

        val position = params.optLong("position", vodObj.optLong("position", 0L)).coerceAtLeast(0L)

        val vod = Vod(
            vodId = vodId,
            vodName = vodName,
            vodPic = vodPic,
            vodRemarks = firstNonBlank(vodObj.optString("vod_remarks"), vodObj.optString("vodRemarks"), vodObj.optString("remarks")),
            vodYear = firstNonBlank(vodObj.optString("vod_year"), vodObj.optString("vodYear"), vodObj.optString("year")),
            vodArea = firstNonBlank(vodObj.optString("vod_area"), vodObj.optString("vodArea"), vodObj.optString("area")),
            vodActor = firstNonBlank(vodObj.optString("vod_actor"), vodObj.optString("vodActor"), vodObj.optString("actor")),
            vodDirector = firstNonBlank(vodObj.optString("vod_director"), vodObj.optString("vodDirector"), vodObj.optString("director")),
            vodContent = firstNonBlank(vodObj.optString("vod_content"), vodObj.optString("vodContent"), vodObj.optString("content"), vodObj.optString("desc")),
            typeName = firstNonBlank(vodObj.optString("type_name"), vodObj.optString("typeName")),
            vodPlayFrom = flags.joinToString("$$$") { it.flag },
            vodPlayUrl = flags.joinToString("$$$") { f -> f.episodes.joinToString("#") { "${it.name}$${it.url}" } },
            siteKey = KEY,
            siteName = siteName,
            playFlags = flags,
        )
        val entry = InlineEntry(
            vod = vod,
            site = createVirtualSite(siteName),
            episodeSpecs = specs,
            defaultHeaders = defaultHeaders,
            defaultFormat = defaultFormat,
            withCredentials = withCredentials,
            hasResolver = hasResolver,
            initialFlag = selectedFlag.flag,
            initialEpisodeIndex = selectedEp.index,
            initialEpisodeName = selectedEp.name,
            initialPosition = position,
        )
        storeEntry(entry)
        return entry
    }

    fun getSite(vodId: String? = null): Site {
        val entry = if (!vodId.isNullOrBlank()) entries[vodId] else entries.values.maxByOrNull { it.createdAt }
        return entry?.site ?: createVirtualSite("网页")
    }

    fun getDetailContent(vodId: String): Result {
        val entry = entries[vodId] ?: entries.values.maxByOrNull { it.createdAt } ?: return Result()
        return Result(list = listOf(entry.vod))
    }

    fun consumeInitialHistory(vodId: String): History? = pendingInitialSelections.remove(vodId)

    suspend fun resolvePlay(flag: String, id: String): Result {
        val entry = entries.values.firstOrNull { it.episodeSpecs.containsKey(id) }
            ?: entries.values.maxByOrNull { it.createdAt }
        val spec = entry?.episodeSpecs?.get(id)

        if (spec == null) {
            if (CookieBridge.isHttpUrl(id)) {
                val headers = LinkedHashMap<String, String>()
                CookieBridge.attachCookieHeader(headers, id)
                return Result(url = id, header = headers, flag = flag)
            }
            return Result()
        }

        val resolver = jsResolver
        if ((spec.useResolver || entry.hasResolver || !CookieBridge.isHttpUrl(spec.url)) && resolver != null) {
            try {
                val resolvedJson = resolver.resolve(entry.vod.vodId, flag.ifBlank { spec.flag }, spec.url, spec.name)
                if (resolvedJson != null) {
                    val resolvedUrl = firstNonBlank(
                        resolvedJson.optString("url"),
                        resolvedJson.optString("playUrl"),
                        spec.url,
                    )
                    val withCred = !resolvedJson.optString("credentials", "include").equals("omit", ignoreCase = true) &&
                        resolvedJson.optBoolean("withCredentials", spec.withCredentials)
                    val mergedHeaders = LinkedHashMap(entry.defaultHeaders)
                    mergedHeaders.putAll(spec.headers)
                    mergedHeaders.putAll(extractHeaders(resolvedJson.optObj("headers"), resolvedUrl, withCred))
                    if (withCred) {
                        CookieBridge.attachCookieHeader(mergedHeaders, resolvedUrl)
                    }
                    val format = firstNonBlank(
                        resolvedJson.optString("format"),
                        resolvedJson.optString("mimeType"),
                        spec.format,
                        entry.defaultFormat,
                    )
                    val parse = resolvedJson.optInt("parse", spec.parse)
                    val jx = resolvedJson.optInt("jx", spec.jx)
                    return Result(
                        url = resolvedUrl,
                        header = mergedHeaders,
                        format = format,
                        parse = parse,
                        jx = jx,
                        flag = flag.ifBlank { spec.flag },
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "调用网页端 inline resolver 解析剧集失败: vodId=%s, ep=%s", entry.vod.vodId, spec.name)
            }
        }

        val finalHeaders = LinkedHashMap(entry.defaultHeaders)
        finalHeaders.putAll(spec.headers)
        if (spec.withCredentials && CookieBridge.isHttpUrl(spec.url)) {
            CookieBridge.attachCookieHeader(finalHeaders, spec.url)
        }
        return Result(
            url = spec.url,
            header = finalHeaders,
            format = spec.format.ifBlank { entry.defaultFormat },
            parse = spec.parse,
            jx = spec.jx,
            flag = flag.ifBlank { spec.flag },
        )
    }

    private fun storeEntry(entry: InlineEntry) {
        if (entries.size >= MAX_ENTRIES) {
            val oldestKey = entries.entries.minByOrNull { it.value.createdAt }?.key
            if (oldestKey != null) {
                entries.remove(oldestKey)
                pendingInitialSelections.remove(oldestKey)
            }
        }
        entries[entry.vod.vodId] = entry
        pendingInitialSelections[entry.vod.vodId] = History(
            key = History.key(KEY, entry.vod.vodId),
            siteKey = KEY,
            siteName = entry.site.name,
            vodId = entry.vod.vodId,
            vodName = entry.vod.vodName,
            vodPic = entry.vod.vodPic,
            vodFlag = entry.initialFlag,
            episodeName = entry.initialEpisodeName,
            episodeUrl = "",
            episodeIndex = entry.initialEpisodeIndex,
            position = entry.initialPosition,
        )
    }

    private fun parseFlagsAndEpisodes(
        vodId: String,
        params: JsonObject,
        vodObj: JsonObject,
        defaultFlagName: String,
        defaultHeaders: Map<String, String>,
        defaultFormat: String,
        withCredentials: Boolean,
        hasResolver: Boolean,
        outSpecs: MutableMap<String, EpisodePlaySpec>,
    ): List<Flag> {
        val result = ArrayList<Flag>()

        val flagsArray = vodObj.optArr("flags")
            ?: vodObj.optArr("playFlags")
            ?: params.optArr("flags")
            ?: params.optArr("playFlags")

        if (!flagsArray.isNullOrEmpty()) {
            for (fi in flagsArray.indices) {
                val flagItem = flagsArray[fi] as? JsonObject ?: continue
                val flagName = firstNonBlank(
                    flagItem.optString("flag"),
                    flagItem.optString("name"),
                    flagItem.optString("from"),
                    "线路${fi + 1}",
                )
                val epsArray = flagItem.optArr("episodes") ?: flagItem.optArr("urls") ?: continue
                val episodes = parseEpisodeArray(
                    vodId = vodId,
                    flagIndex = fi,
                    flagName = flagName,
                    epsArray = epsArray,
                    defaultHeaders = defaultHeaders,
                    defaultFormat = defaultFormat,
                    withCredentials = withCredentials,
                    hasResolver = hasResolver,
                    outSpecs = outSpecs,
                )
                if (episodes.isNotEmpty()) {
                    result.add(Flag(flag = flagName, episodes = episodes))
                }
            }
            if (result.isNotEmpty()) return result
        }

        val directEpisodes = vodObj.optArr("episodes")
            ?: params.optArr("episodes")
            ?: vodObj.optArr("urls")
            ?: params.optArr("urls")

        if (!directEpisodes.isNullOrEmpty()) {
            val episodes = parseEpisodeArray(
                vodId = vodId,
                flagIndex = 0,
                flagName = defaultFlagName,
                epsArray = directEpisodes,
                defaultHeaders = defaultHeaders,
                defaultFormat = defaultFormat,
                withCredentials = withCredentials,
                hasResolver = hasResolver,
                outSpecs = outSpecs,
            )
            if (episodes.isNotEmpty()) {
                return listOf(Flag(flag = defaultFlagName, episodes = episodes))
            }
        }

        val playFrom = firstNonBlank(
            vodObj.optString("vod_play_from"),
            vodObj.optString("vodPlayFrom"),
            params.optString("vod_play_from"),
            params.optString("vodPlayFrom"),
            defaultFlagName,
        )
        val playUrl = firstNonBlank(
            vodObj.optString("vod_play_url"),
            vodObj.optString("vodPlayUrl"),
            params.optString("vod_play_url"),
            params.optString("vodPlayUrl"),
            params.optString("url"),
        )
        if (playUrl.isNotEmpty()) {
            val fromParts = playFrom.split("$$$")
            val urlGroups = playUrl.split("$$$")
            for (fi in urlGroups.indices) {
                val flagName = fromParts.getOrNull(fi)?.trim()?.takeIf { it.isNotEmpty() } ?: "线路${fi + 1}"
                val rawGroup = urlGroups[fi].trim()
                if (rawGroup.isEmpty()) continue
                val items = rawGroup.split("#").mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                val episodes = ArrayList<Episode>(items.size)
                items.forEachIndexed { ei, item ->
                    val dollarIdx = item.indexOf('$')
                    val epName = if (dollarIdx > 0) item.substring(0, dollarIdx).trim() else "第${ei + 1}集"
                    val epUrl = if (dollarIdx >= 0) item.substring(dollarIdx + 1).trim() else item
                    if (epUrl.isNotEmpty()) {
                        val epKey = "$vodId|$fi|$ei"
                        outSpecs[epKey] = EpisodePlaySpec(
                            id = epKey,
                            name = epName,
                            flag = flagName,
                            url = epUrl,
                            headers = defaultHeaders,
                            format = defaultFormat,
                            withCredentials = withCredentials,
                            useResolver = hasResolver || !CookieBridge.isHttpUrl(epUrl),
                        )
                        episodes.add(Episode(name = epName, url = epKey, index = episodes.size))
                    }
                }
                if (episodes.isNotEmpty()) {
                    result.add(Flag(flag = flagName, episodes = episodes))
                }
            }
        }
        return result
    }

    private fun parseEpisodeArray(
        vodId: String,
        flagIndex: Int,
        flagName: String,
        epsArray: JsonArray,
        defaultHeaders: Map<String, String>,
        defaultFormat: String,
        withCredentials: Boolean,
        hasResolver: Boolean,
        outSpecs: MutableMap<String, EpisodePlaySpec>,
    ): List<Episode> {
        val episodes = ArrayList<Episode>(epsArray.size)
        for (ei in epsArray.indices) {
            when (val item = epsArray[ei]) {
                is JsonObject -> {
                    val epName = firstNonBlank(
                        item.optString("name"),
                        item.optString("title"),
                        "第${ei + 1}集",
                    )
                    val epUrl = firstNonBlank(
                        item.optString("url"),
                        item.optString("playUrl"),
                        item.optString("id"),
                        item.optString("playId"),
                    )
                    if (epUrl.isEmpty()) continue
                    val epKey = "$vodId|$flagIndex|$ei"
                    val epHeaders = LinkedHashMap(defaultHeaders)
                    epHeaders.putAll(extractHeaders(item.optObj("headers"), epUrl, withCredentials = false))
                    val epFormat = firstNonBlank(item.optString("format"), item.optString("mimeType"), defaultFormat)
                    val parse = item.optInt("parse", if (item.optBoolean("sniff", false)) 1 else 0)
                    val jx = item.optInt("jx", 0)
                    val useResolver = hasResolver || item.optBoolean("useResolver", false) || !CookieBridge.isHttpUrl(epUrl)
                    outSpecs[epKey] = EpisodePlaySpec(
                        id = epKey,
                        name = epName,
                        flag = flagName,
                        url = epUrl,
                        headers = epHeaders,
                        format = epFormat,
                        parse = parse,
                        jx = jx,
                        withCredentials = withCredentials,
                        useResolver = useResolver,
                    )
                    episodes.add(Episode(name = epName, url = epKey, index = episodes.size))
                }
                is JsonPrimitive -> {
                    val trimmed = item.content.trim()
                    if (trimmed.isEmpty()) continue
                    val dollarIdx = trimmed.indexOf('$')
                    val epName = if (dollarIdx > 0) trimmed.substring(0, dollarIdx).trim() else "第${ei + 1}集"
                    val epUrl = if (dollarIdx >= 0) trimmed.substring(dollarIdx + 1).trim() else trimmed
                    if (epUrl.isEmpty()) continue
                    val epKey = "$vodId|$flagIndex|$ei"
                    outSpecs[epKey] = EpisodePlaySpec(
                        id = epKey,
                        name = epName,
                        flag = flagName,
                        url = epUrl,
                        headers = defaultHeaders,
                        format = defaultFormat,
                        withCredentials = withCredentials,
                        useResolver = hasResolver || !CookieBridge.isHttpUrl(epUrl),
                    )
                    episodes.add(Episode(name = epName, url = epKey, index = episodes.size))
                }
                else -> Unit
            }
        }
        return episodes
    }

    private fun extractHeaders(
        headersObj: JsonObject?,
        url: String,
        withCredentials: Boolean,
    ): Map<String, String> {
        val raw = LinkedHashMap<String, String>()
        if (headersObj != null) {
            for ((k, v) in headersObj) {
                if (v !is JsonNull) {
                    raw[k] = (v as? JsonPrimitive)?.content ?: v.toString()
                }
            }
        }
        val clean = CookieBridge.sanitizeHeaders(raw)
        if (withCredentials && CookieBridge.isHttpUrl(url)) {
            CookieBridge.attachCookieHeader(clean, url)
        }
        return clean
    }

    private fun createVirtualSite(siteName: String): Site = Site(
        key = KEY,
        name = siteName.ifBlank { "网页" },
        type = 4,
        api = KEY,
        searchable = 0,
        quickSearch = false,
        filterable = false,
        changeable = false,
        hide = true,
        enabled = true,
    )

    private fun firstNonBlank(vararg values: String?): String {
        for (v in values) {
            val trimmed = v?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }
}
