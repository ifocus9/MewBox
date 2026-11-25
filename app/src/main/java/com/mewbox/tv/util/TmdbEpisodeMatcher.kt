package com.mewbox.tv.util

import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbEpisode
import com.mewbox.tv.api.tmdb.bean.TmdbImageItem
import com.mewbox.tv.bean.Episode
import java.util.Locale

/** 从站源分集名解析出的季/集定位引用。 */
data class EpisodeRef(
    val season: Int,
    val episode: Int,
    val explicitSeason: Boolean,
)

/** 详情页横向剧照选集卡片所需的渲染数据。 */
data class ResolvedEpisodeCard(
    val episode: Episode,
    val badgeText: String?,
    val stillUrl: String?,
    val title: String,
    val matchedTmdb: Boolean,
)

/**
 * 站源文件名与 TMDB 分集元数据匹配器。
 *
 * 负责过滤 NCOP/NCED/OP/ED/SP/PV/预告等非正片特典，从多样化的网盘/资源站命名中提取 `(season, episode)`，
 * 并在单季/多季、连续编号及缺集场景下完成 TMDB 单集剧照与标题关联。
 */
object TmdbEpisodeMatcher {

    private const val MAX_SEASONS_TO_FETCH = 8
    private const val MAX_REASONABLE_SEASON = 50
    private const val MAX_REASONABLE_EPISODE = 2000

    private val NON_MAINLINE_EN_REGEX = Regex(
        "(?i)(?:^|[^A-Za-z0-9])(?:NCOP|NCED|OP|ED|OVA|OAD|PV|CM|SP|MENU|TRAILER|TEASER|CREDITLESS)\\d{0,2}(?:[._\\-]?[vV]\\d+)?(?=$|[^A-Za-z0-9])"
    )
    private val STRONG_EXTRA_ZH_KEYWORDS = listOf(
        "预告", "預告", "花絮", "特典", "先导", "先導",
        "特辑", "特輯", "片花", "番外", "特别篇", "特別篇",
        "抢先看", "搶先看", "纯享"
    )
    private val SOFT_EXTRA_ZH_KEYWORDS = listOf(
        "幕后", "幕後", "彩蛋", "采访", "采訪"
    )

    private val EXTENSION_REGEX = Regex("(?i)\\.(mp4|mkv|avi|mov|flv|wmv|ts|m2ts|m3u8|rmvb|webm|iso)$")
    private val SIZE_TEXT = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?\\s*(?:TB|T|GB|G|MB|M)"
    private val SIZE_PREFIX_REGEX = Regex("(?i)^\\s*[\\[\\(（【]?\\s*$SIZE_TEXT\\s*[\\]\\)）】]?\\s*")
    private val SIZE_SUFFIX_REGEX = Regex("(?i)\\s*[\\[\\(（【]?\\s*$SIZE_TEXT\\s*[\\]\\)）】]?\\s*$")
    private val SIZE_BRACKET_REGEX = Regex("(?i)[\\[\\(（【]\\s*$SIZE_TEXT\\s*[\\]\\)）】]")
    private val HASH_SUFFIX_REGEX = Regex("(?i)\\s*[\\[\\(（【]\\s*[A-F0-9]{8,32}\\s*[\\]\\)）】]\\s*$")
    private val DATE_TOKEN_REGEX = Regex("(?<![0-9])[0-9]{4}[-._][0-9]{1,2}[-._][0-9]{1,2}(?![0-9])")
    private val TECH_NOISE_REGEX = Regex(
        "(?i)(?:^|(?<=[^A-Za-z0-9]))(?:" +
            "[0-9]{3,4}\\s*[x×]\\s*[0-9]{3,4}|" +
            "(?:4320|2160|1440|1080|720|576|480)[PI](?:60P)?|" +
            "[248]K|UHD|FHD|HD|HQ|SD|" +
            "HDR10(?:\\+|⁺)?|HDR|SDR|DV|DOVI|DOLBY(?:\\s*VISION)?|" +
            "HEVC|AVC|AV1|[HX][.]?26[45]|" +
            "AAC(?:\\s*[0-9][.·][0-9])?|E?AC3|DDP(?:\\s*[0-9][.·][0-9])?|DTS(?:-HD)?|TRUEHD|ATMOS|FLAC|PCM|" +
            "WEB-?DL|WEB-?RIP|BLU-?RAY|BDRIP|BD|TVRIP|HDTV|REMUX|" +
            "[0-9]{2,3}\\s*FPS|(?:HI)?10\\s*-?\\s*BITS?|8\\s*-?\\s*BITS?" +
            ")(?=$|[^A-Za-z0-9])"
    )
    private val EMPTY_BRACKETS_REGEX = Regex("\\s*(?:\\[\\s*]|【\\s*】|\\(\\s*\\)|（\\s*）)\\s*")
    private val EDGE_SEPARATORS_REGEX = Regex("^[\\s._\\-·|｜/:：]+|[\\s._\\-·|｜/:：]+$")

    private val ZH_SEASON_REGEX = Regex("第\\s*([0-9零〇一二三四五六七八九十两兩]+)\\s*季")
    private val VOD_EN_SEASON_REGEX = Regex("(?i)(?:^|[^A-Za-z0-9])(?:Season|S)\\s*0?([1-9]\\d?)(?=$|[^A-Za-z0-9])")
    private val FLAG_EN_SEASON_REGEX = Regex("(?i)(?:^|[^A-Za-z0-9])Season\\s*0?([1-9]\\d?)(?=$|[^A-Za-z0-9])")

    private val EXPLICIT_SE_REGEX = Regex(
        "(?i)(?:^|[^A-Za-z0-9])S0*(\\d{1,2})[._\\-\\s]*E[Pp]?0*(\\d{1,4})(?:[vV]\\d+)?(?=$|[^A-Za-z0-9])"
    )
    private val EXPLICIT_X_REGEX = Regex(
        "(?i)(?:^|[^A-Za-z0-9])(\\d{1,2})\\s*[x×]\\s*0*(\\d{1,4})(?:[vV]\\d+)?(?=$|[^A-Za-z0-9])"
    )
    private val RANGE_TAIL = "(?:\\s*[-~—–至到]\\s*[0-9一二三四五六七八九十百千零〇两兩]+)?"
    private val EXPLICIT_ZH_SEASON_EP_REGEX = Regex(
        "第\\s*([0-9一二三四五六七八九十百两兩]+)\\s*季.*?第\\s*([0-9一二三四五六七八九十百千零〇两兩]+)$RANGE_TAIL\\s*[集话話期回幕]"
    )
    private val EXPLICIT_ZH_EP_REGEX = Regex(
        "第\\s*([0-9一二三四五六七八九十百千零〇两兩]+)$RANGE_TAIL\\s*[集话話期回幕]"
    )
    private val EXPLICIT_EP_REGEX = Regex(
        "(?i)(?:^|[^A-Za-z0-9])(?:EP\\s*|E)0*(\\d{1,4})(?:[vV]\\d+)?(?=$|[^A-Za-z0-9])"
    )
    private val EXPLICIT_NUM_ZH_EP_REGEX = Regex("(?<![0-9])0*(\\d{1,4})$RANGE_TAIL\\s*[集话話期回幕]")

    private val PURE_NUMBER_REGEX = Regex("^\\s*0*(\\d{1,4})(?:[vV]\\d+)?\\s*$")
    private val BRACKET_NUMBER_REGEX = Regex("[\\[【]\\s*0*(\\d{1,4})(?:[vV]\\d+)?\\s*[\\]】]")
    private val DASH_NUMBER_REGEX = Regex("(?:^|\\s)[-—–]\\s*0*(\\d{1,4})(?:[vV]\\d+)?(?=$|\\s|[\\[【(（])")
    private val LEADING_NUMBER_REGEX = Regex("^\\s*0*(\\d{1,4})(?:[vV]\\d+)?(?=$|[^0-9A-Za-z])")
    private val SEP_TRAILING_NUMBER_REGEX = Regex("(?:[\\s._\\-·|｜]+)0*(\\d{1,4})(?:[vV]\\d+)?\\s*$")

    private val VARIANT_TOKEN_REGEX = Regex(
        "(?i)(?:^|[^A-Za-z0-9])(4K|8K|2160P|1080P|720P|HDR10(?:\\+|⁺)?|HDR|DV|HEVC|H\\.?265|AV1|60FPS)(?=$|[^A-Za-z0-9])"
    )
    private val VARIANT_ZH_KEYWORDS = listOf("杜比", "国语", "粤语", "原盘")

    /** 判断文件是否为 NCOP/NCED/OP/ED/PV/SP/预告/花絮等非正片特典。 */
    fun isNonMainlineExtra(rawName: String, vodName: String = ""): Boolean {
        if (rawName.isBlank()) return false
        val effective = stripVodTitlePrefix(rawName, vodName).ifBlank { rawName }
        return isNonMainlineEffective(effective)
    }

    /** 从片名或线路名识别默认季号，未命中时返回 1。 */
    fun detectDefaultSeason(vodName: String, flagName: String = ""): Int {
        if (vodName.isNotBlank()) {
            ZH_SEASON_REGEX.find(vodName)?.let { match ->
                val season = parseChineseOrArabicInt(match.groupValues[1])
                if (season != null && season in 1..MAX_REASONABLE_SEASON) return season
            }
            VOD_EN_SEASON_REGEX.find(vodName)?.let { match ->
                val season = match.groupValues[1].toIntOrNull()
                if (season != null && season in 1..MAX_REASONABLE_SEASON) return season
            }
        }
        if (flagName.isNotBlank()) {
            ZH_SEASON_REGEX.find(flagName)?.let { match ->
                val season = parseChineseOrArabicInt(match.groupValues[1])
                if (season != null && season in 1..MAX_REASONABLE_SEASON) return season
            }
            FLAG_EN_SEASON_REGEX.find(flagName)?.let { match ->
                val season = match.groupValues[1].toIntOrNull()
                if (season != null && season in 1..MAX_REASONABLE_SEASON) return season
            }
        }
        return 1
    }

    /** 逐集解析季/集引用，保持与 [episodes] 严格 1:1 对齐。 */
    fun parseEpisodeRefs(
        episodes: List<Episode>,
        defaultSeason: Int = 1,
        vodName: String = "",
    ): List<EpisodeRef?> {
        if (episodes.isEmpty()) return emptyList()
        val safeDefaultSeason = defaultSeason.coerceIn(1, MAX_REASONABLE_SEASON)

        val rawStripped = Array(episodes.size) { index ->
            stripVodTitlePrefix(episodes[index].name, vodName)
        }
        val listCommonPrefixLen = if (episodes.size >= 2) {
            findSafeCommonPrefixLength(rawStripped.toList())
        } else 0

        val extras = BooleanArray(episodes.size) { index ->
            val effective = if (listCommonPrefixLen in 1 until rawStripped[index].length) {
                rawStripped[index].substring(listCommonPrefixLen).trimStart(' ', '.', '_', '-', '·', '|', '/', ':', '：')
            } else {
                rawStripped[index]
            }
            isNonMainlineEffective(effective.ifBlank { rawStripped[index] })
        }

        val cleaned = Array(episodes.size) { index ->
            if (extras[index]) "" else cleanEpisodeNoise(rawStripped[index])
        }

        // Pass 1：强规则（显式季集、第X集、EP01 等）
        val refs = Array<EpisodeRef?>(episodes.size) { index ->
            if (extras[index]) null else parseStrongRef(cleaned[index], safeDefaultSeason)
        }

        // Pass 2：弱规则兜底（仅在未命中强规则的非特典条目内计算安全公共前缀，避免混排列表拉归零）
        val needFallbackIndexes = episodes.indices.filter { !extras[it] && refs[it] == null }
        if (needFallbackIndexes.isEmpty()) return refs.toList()

        val fallbackCleaned = needFallbackIndexes.map { cleaned[it] }
        val fallbackPrefixLen = if (fallbackCleaned.size >= 2) {
            findSafeCommonPrefixLength(fallbackCleaned)
        } else 0

        for (index in needFallbackIndexes) {
            val text = cleaned[index]
            val candidate = if (fallbackPrefixLen in 1 until text.length) {
                text.substring(fallbackPrefixLen).trimStart(' ', '.', '_', '-', '·', '|', '/', ':', '：')
            } else {
                text
            }
            refs[index] = parseWeakRef(candidate, safeDefaultSeason)
        }

        return refs.toList()
    }

    /** 计算展示该线路分集所需拉取的 TMDB 季号列表（升序、去重、上限裁切）。 */
    fun resolveRequiredSeasons(
        tmdbDetail: TmdbDetail,
        vodName: String,
        flagName: String,
        episodes: List<Episode>,
    ): List<Int> {
        if (tmdbDetail.mediaType != "tv" || episodes.isEmpty()) return emptyList()

        val defaultSeason = detectDefaultSeason(vodName, flagName)
        val refs = parseEpisodeRefs(episodes, defaultSeason, vodName)
        val positiveSeasons = tmdbDetail.seasons
            .filter { it.seasonNumber > 0 }
            .sortedBy { it.seasonNumber }

        val needed = linkedSetOf<Int>()
        for (ref in refs) {
            if (ref == null) continue
            if (ref.explicitSeason) {
                if (ref.season in 0..MAX_REASONABLE_SEASON) {
                    needed.add(ref.season)
                }
            } else {
                val mapped = if (ref.season == 1 && positiveSeasons.size > 1) {
                    mapContinuousEpisode(ref.episode, positiveSeasons)
                } else null
                if (mapped != null && ref.episode > positiveSeasons.first().episodeCount) {
                    needed.add(mapped.first)
                } else {
                    needed.add(ref.season)
                }
            }
        }

        // 全为特典或无任何可识别集号时不再触发无意义的季详情请求
        if (needed.isEmpty()) return emptyList()

        val filtered = if (positiveSeasons.isNotEmpty()) {
            val available = tmdbDetail.seasons.map { it.seasonNumber }.toSet()
            needed.filter { it in available }
                .ifEmpty {
                    listOfNotNull(
                        positiveSeasons.firstOrNull { it.seasonNumber == defaultSeason }?.seasonNumber
                            ?: positiveSeasons.first().seasonNumber
                    )
                }
        } else {
            // 兼容磁盘旧版手动缓存（seasons 字段为空）：保留正向检测季号
            needed.filter { it > 0 }.ifEmpty { listOf(defaultSeason) }
        }

        return filtered
            .distinct()
            .sorted()
            .take(MAX_SEASONS_TO_FETCH)
    }

    /** 将站源分集列表与 TMDB 分集数据关联，输出每张卡片的角标、剧照 URL 与标题。 */
    fun resolveCards(
        episodes: List<Episode>,
        fallbackDisplayNames: List<String>,
        vodName: String,
        flagName: String,
        tmdbDetail: TmdbDetail?,
        tmdbEpisodesBySeason: Map<Int, List<TmdbEpisode>>,
        imageBase: String,
    ): List<ResolvedEpisodeCard> {
        if (episodes.isEmpty()) return emptyList()
        if (tmdbDetail?.mediaType == "movie") {
            return episodes.mapIndexed { index, episode ->
                ResolvedEpisodeCard(
                    episode = episode,
                    badgeText = null,
                    stillUrl = null,
                    title = fallbackDisplayNames.getOrElse(index) { episode.name },
                    matchedTmdb = false,
                )
            }
        }

        val defaultSeason = detectDefaultSeason(vodName, flagName)
        val refs = parseEpisodeRefs(episodes, defaultSeason, vodName)
        val positiveSeasons = tmdbDetail?.seasons.orEmpty()
            .filter { it.seasonNumber > 0 }
            .sortedBy { it.seasonNumber }

        val episodeLookup = HashMap<Pair<Int, Int>, TmdbEpisode>()
        for ((seasonNum, epList) in tmdbEpisodesBySeason) {
            for (ep in epList) {
                val effectiveSeason = if (ep.seasonNumber > 0 || seasonNum == 0) ep.seasonNumber else seasonNum
                episodeLookup[effectiveSeason to ep.episodeNumber] = ep
            }
        }

        data class MatchSlot(
            val resolvedSeason: Int,
            val resolvedEpisode: Int,
            val matched: TmdbEpisode?,
            val hasRef: Boolean,
        )

        val slots = refs.map { ref ->
            if (ref == null) {
                MatchSlot(0, 0, null, false)
            } else {
                var targetSeason = ref.season
                var targetEp = ref.episode
                if (!ref.explicitSeason &&
                    ref.season == 1 &&
                    positiveSeasons.size > 1 &&
                    positiveSeasons.first().episodeCount in 1 until ref.episode
                ) {
                    val mapped = mapContinuousEpisode(ref.episode, positiveSeasons)
                    if (mapped != null) {
                        targetSeason = mapped.first
                        targetEp = mapped.second
                    }
                }
                val matched = episodeLookup[targetSeason to targetEp]
                MatchSlot(targetSeason, targetEp, matched, true)
            }
        }

        val matchedSeasons = slots.mapNotNull { if (it.matched != null) it.resolvedSeason else null }.toSet()
        val isMultiSeason = positiveSeasons.size > 1 ||
            (tmdbDetail?.numberOfSeasons ?: 0) > 1 ||
            matchedSeasons.size > 1 ||
            slots.any { it.hasRef && it.resolvedSeason > 1 }

        // 同集多版本（如 第01集 4K / 第01集 1080P）检测，仅对撞号条目附加画质版本标签
        val matchedPairCounts = slots
            .filter { it.hasRef && it.matched != null }
            .groupingBy { it.resolvedSeason to it.resolvedEpisode }
            .eachCount()

        return episodes.mapIndexed { index, episode ->
            val slot = slots[index]
            val fallbackTitle = fallbackDisplayNames.getOrElse(index) { episode.name }
            if (!slot.hasRef) {
                ResolvedEpisodeCard(
                    episode = episode,
                    badgeText = null,
                    stillUrl = null,
                    title = fallbackTitle,
                    matchedTmdb = false,
                )
            } else {
                val badge = when {
                    slot.resolvedSeason == 0 -> "S0E${slot.resolvedEpisode}"
                    isMultiSeason -> "S${slot.resolvedSeason}E${slot.resolvedEpisode}"
                    else -> slot.resolvedEpisode.toString()
                }
                val matched = slot.matched
                val stillUrl = matched?.stillPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { TmdbClient.stillUrl(imageBase, it).ifEmpty { null } }
                val baseTitle = matched?.name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
                val isDuplicateMatched = matched != null &&
                    (matchedPairCounts[slot.resolvedSeason to slot.resolvedEpisode] ?: 0) > 1
                val title = if (isDuplicateMatched) {
                    val variant = extractVariantTag(episode.name)
                    if (variant.isNotEmpty() && !baseTitle.contains(variant, ignoreCase = true)) {
                        "$baseTitle · $variant"
                    } else {
                        baseTitle
                    }
                } else {
                    baseTitle
                }
                ResolvedEpisodeCard(
                    episode = episode,
                    badgeText = badge,
                    stillUrl = stillUrl,
                    title = title,
                    matchedTmdb = matched != null,
                )
            }
        }
    }

    /** 解析剧照卡片无单集剧照（或特典文件）时的横版背景兜底图 URL。 */
    fun resolveFallbackBackdropUrl(
        tmdbDetail: TmdbDetail?,
        tmdbImageBase: String,
        quickBackdrop: String? = null,
        vodPic: String = "",
    ): String? {
        if (tmdbDetail != null) {
            TmdbClient.imageUrl(tmdbImageBase, tmdbDetail.backdropPath, "w780")
                .takeIf { it.isNotEmpty() }
                ?.let { return it }

            tmdbDetail.images?.backdrops.orEmpty()
                .filter { !it.filePath.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<TmdbImageItem> { it.iso639 == null }
                        .thenByDescending { it.voteAverage }
                )
                .firstOrNull()
                ?.filePath
                ?.let { TmdbClient.imageUrl(tmdbImageBase, it, "w780") }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        quickBackdrop?.takeIf { it.isNotBlank() }?.let { return it }

        if (tmdbDetail != null) {
            TmdbClient.imageUrl(tmdbImageBase, tmdbDetail.posterPath, "w780")
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        return vodPic.takeIf { it.isNotBlank() }
    }

    private fun isNonMainlineEffective(effective: String): Boolean {
        if (effective.isBlank()) return false
        if (NON_MAINLINE_EN_REGEX.containsMatchIn(effective)) return true
        if (STRONG_EXTRA_ZH_KEYWORDS.any { effective.contains(it) }) return true
        if (SOFT_EXTRA_ZH_KEYWORDS.any { effective.contains(it) }) {
            return parseStrongRef(cleanEpisodeNoise(effective), 1) == null
        }
        return false
    }

    private fun stripVodTitlePrefix(rawName: String, vodName: String): String {
        val raw = rawName.trim()
        if (vodName.isBlank() || raw.isEmpty()) return raw
        val cleanedVod = TmdbClient.cleanTitle(vodName).trim()
        val candidates = listOf(vodName.trim(), cleanedVod).filter { it.length >= 2 }.distinct()
        for (title in candidates) {
            if (raw.startsWith(title, ignoreCase = true) && raw.length > title.length) {
                val boundaryIdx = title.length
                if (isPrefixBoundary(raw, boundaryIdx)) {
                    val rest = raw.substring(boundaryIdx).trimStart(' ', '.', '_', '-', '·', '|', '/', ':', '：')
                    if (rest.isNotEmpty()) return rest
                }
            }
        }
        return raw
    }

    private fun extractVariantTag(rawName: String): String {
        VARIANT_TOKEN_REGEX.find(rawName)?.let { match ->
            return match.groupValues[1].uppercase(Locale.ROOT).replace("H265", "HEVC")
        }
        for (kw in VARIANT_ZH_KEYWORDS) {
            if (rawName.contains(kw)) return kw
        }
        return ""
    }

    private fun parseStrongRef(cleaned: String, defaultSeason: Int): EpisodeRef? {
        EXPLICIT_SE_REGEX.find(cleaned)?.let { m ->
            val s = m.groupValues[1].toIntOrNull()
            val e = m.groupValues[2].toIntOrNull()
            if (s != null && e != null && s in 0..MAX_REASONABLE_SEASON && e in 1..MAX_REASONABLE_EPISODE) {
                return EpisodeRef(s, e, explicitSeason = true)
            }
        }
        EXPLICIT_X_REGEX.find(cleaned)?.let { m ->
            val s = m.groupValues[1].toIntOrNull()
            val e = m.groupValues[2].toIntOrNull()
            if (s != null && e != null && s in 1..MAX_REASONABLE_SEASON && e in 1..MAX_REASONABLE_EPISODE) {
                return EpisodeRef(s, e, explicitSeason = true)
            }
        }
        EXPLICIT_ZH_SEASON_EP_REGEX.find(cleaned)?.let { m ->
            val s = parseChineseOrArabicInt(m.groupValues[1])
            val e = parseChineseOrArabicInt(m.groupValues[2])
            if (s != null && e != null && s in 1..MAX_REASONABLE_SEASON && e in 1..MAX_REASONABLE_EPISODE) {
                return EpisodeRef(s, e, explicitSeason = true)
            }
        }
        EXPLICIT_ZH_EP_REGEX.find(cleaned)?.let { m ->
            val e = parseChineseOrArabicInt(m.groupValues[1])
            if (e != null && e in 1..MAX_REASONABLE_EPISODE) {
                return EpisodeRef(defaultSeason, e, explicitSeason = false)
            }
        }
        EXPLICIT_EP_REGEX.find(cleaned)?.let { m ->
            val rawNum = m.groupValues[1]
            val e = rawNum.toIntOrNull()
            if (e != null && !isStandaloneYear(rawNum) && e in 1..MAX_REASONABLE_EPISODE) {
                return EpisodeRef(defaultSeason, e, explicitSeason = false)
            }
        }
        EXPLICIT_NUM_ZH_EP_REGEX.find(cleaned)?.let { m ->
            val e = m.groupValues[1].toIntOrNull()
            if (e != null && e in 1..MAX_REASONABLE_EPISODE) {
                return EpisodeRef(defaultSeason, e, explicitSeason = false)
            }
        }
        return null
    }

    private fun parseWeakRef(candidate: String, defaultSeason: Int): EpisodeRef? {
        if (candidate.isBlank()) return null
        val patterns = listOf(
            PURE_NUMBER_REGEX,
            BRACKET_NUMBER_REGEX,
            DASH_NUMBER_REGEX,
            LEADING_NUMBER_REGEX,
            SEP_TRAILING_NUMBER_REGEX,
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(candidate)) {
                val rawNum = match.groupValues[1]
                if (isStandaloneYear(rawNum)) continue
                val ep = rawNum.toIntOrNull() ?: continue
                if (ep in 1..MAX_REASONABLE_EPISODE) {
                    return EpisodeRef(defaultSeason, ep, explicitSeason = false)
                }
            }
        }
        return null
    }

    private fun cleanEpisodeNoise(raw: String): String {
        var text = raw.trim()
        text = HASH_SUFFIX_REGEX.replace(text, "")
        text = SIZE_SUFFIX_REGEX.replace(text, "")
        text = SIZE_PREFIX_REGEX.replace(text, "")
        text = EXTENSION_REGEX.replace(text, "")
        text = SIZE_SUFFIX_REGEX.replace(text, "")
        text = SIZE_BRACKET_REGEX.replace(text, " ")
        text = DATE_TOKEN_REGEX.replace(text, " ")
        text = TECH_NOISE_REGEX.replace(text, " ")
        while (true) {
            val collapsed = EMPTY_BRACKETS_REGEX.replace(text, " ")
            if (collapsed == text) break
            text = collapsed
        }
        text = EDGE_SEPARATORS_REGEX.replace(text, "")
        return text.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun findSafeCommonPrefixLength(names: List<String>): Int {
        if (names.size < 2) return 0
        var prefix = names[0].length
        for (name in names) {
            prefix = minOf(prefix, name.length)
            for (i in 0 until prefix) {
                if (names[0][i] != name[i]) {
                    prefix = i
                    break
                }
            }
        }
        for (len in prefix downTo 1) {
            val valid = names.all { name ->
                len < name.length && isPrefixBoundary(name, len)
            }
            if (valid) return len
        }
        return 0
    }

    private fun isPrefixBoundary(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return false
        val prev = text[index - 1]
        val next = text[index]
        if (prev.isDigit() && next.isDigit()) return false
        if (!prev.isDigit() && prev !in 'A'..'Z' && prev !in 'a'..'z' && next.isDigit()) return true
        return isSeparatorChar(prev) || isSeparatorChar(next) || !prev.isLetterOrDigit() || !next.isLetterOrDigit()
    }

    private fun isSeparatorChar(c: Char): Boolean =
        c.isWhitespace() || "-_.·|｜/\\:：,，;；~～—–[]()（）【】《》".indexOf(c) >= 0

    private fun isStandaloneYear(token: String): Boolean {
        if (token.length != 4) return false
        val year = token.toIntOrNull() ?: return false
        return year in 1900..2099
    }

    private fun mapContinuousEpisode(
        absoluteEpisode: Int,
        positiveSeasons: List<com.mewbox.tv.api.tmdb.bean.TmdbSeasonSummary>,
    ): Pair<Int, Int>? {
        if (absoluteEpisode <= 0 || positiveSeasons.isEmpty()) return null
        var remaining = absoluteEpisode
        for (season in positiveSeasons) {
            val count = season.episodeCount
            if (count <= 0) return null
            if (remaining <= count) {
                return season.seasonNumber to remaining
            }
            remaining -= count
        }
        return null
    }

    /** 解析阿拉伯数字或中文数字（支持 1..999，如「十二」「二十四」「一百零一」）。 */
    fun parseChineseOrArabicInt(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }

        var total = 0
        var current = 0
        var hasDigit = false
        for (ch in s) {
            val digit = when (ch) {
                '零', '〇' -> 0
                '一' -> 1
                '二', '两', '兩' -> 2
                '三' -> 3
                '四' -> 4
                '五' -> 5
                '六' -> 6
                '七' -> 7
                '八' -> 8
                '九' -> 9
                else -> -1
            }
            if (digit >= 0) {
                current = digit
                hasDigit = true
                continue
            }
            val unit = when (ch) {
                '十' -> 10
                '百' -> 100
                '千' -> 1000
                else -> return null
            }
            total += (if (current == 0 && !hasDigit) 1 else current) * unit
            current = 0
            hasDigit = false
        }
        val result = total + current
        return result.takeIf { it > 0 }
    }
}
