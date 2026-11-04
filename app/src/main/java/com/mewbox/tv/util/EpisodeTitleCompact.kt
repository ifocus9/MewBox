package com.mewbox.tv.util

import com.mewbox.tv.bean.Episode
import java.util.Locale

/**
 * 选集标题精简（移植自 MewPlayer 的 EpisodeTitleCompact）。
 *
 * 网盘 / 资源站的分集名常常是「剧名.S01E01.2160P.HDR.WEB-DL.mp4」「[GROUP] 第03集 1080P」这类长串，
 * 放进选集网格会被截断成看不懂的样子。这里把「整个列表」一起看，剥掉公共前后缀、文件名噪音、
 * 画质 / 编码 / 体积标记，尽量只留下集数，从而实现和 MewPlayer 一致的「精简」开关效果。
 *
 * 核心规则：
 * 1. 先去扩展名、体积、哈希、首尾括号等文件噪音；
 * 2. 求列表公共前缀（剧名）与公共后缀（画质 / 字幕组），逐步收缩到「有意义的边界」；
 * 3. 逐条识别集数标记（S01E01 / EP01 / 第01集 / 1x01 / 01v2 / 日期 / 纯数字等）；
 * 4. 列表 ≥3 条时，按「数字段列」找各集都不相同、且跨度合理的那一列作为集数；
 * 5. 去重：同名条目用画质标记（4K / 1080p / HDR…）区分，仍重名再加 -1/-2 后缀。
 */
object EpisodeTitleCompact {

    private const val MAX_COMPACT_LENGTH = 14
    private const val MIN_VARYING_NUMBER_ITEMS = 3

    /** 汉字「丨」与韩文字母「ㅣ」常被当作竖线分隔符，Unicode 里却算字母。 */
    private const val BAR_LETTERS = "丨ㅣ"
    private const val TOKEN_START = "(?:^|(?<=[^\\p{L}\\p{N}])|(?<=[$BAR_LETTERS]))"
    private const val TOKEN_END = "(?=$|[^\\p{L}\\p{N}]|[$BAR_LETTERS])"

    private const val SEPARATOR_CHARS = "\\s\\u00A0\\u3000._\\-·|｜$BAR_LETTERS/\\\\:：,，;；~～—–"
    private const val BOUNDARY_CHARS = "$SEPARATOR_CHARS\\[\\]()（）【】《》"
    private const val NOT_TILDE_RANGE = "(?!\\s*[~～]\\s*[0-9]{1,3}(?![0-9A-Za-z]))"

    private val EXTENSION = Regex("(?i)\\.(mp4|mkv|avi|mov|flv|wmv|ts|m2ts|m3u8|rmvb|webm)$")

    private const val SIZE_VALUE = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?"
    private const val SIZE_UNIT = "(?:TB|T|GB|G|MB|M)"
    private const val SIZE_TEXT = "($SIZE_VALUE)\\s*($SIZE_UNIT)"
    private val SIZE_PREFIX = Regex("(?i)^\\s*[\\[\\(（【]?\\s*$SIZE_TEXT\\s*[\\]\\)）】]?\\s*")
    private val SIZE_SUFFIX = Regex("(?i)\\s*[\\[\\(（【]?\\s*$SIZE_TEXT\\s*[\\]\\)）】]?\\s*$")
    private val HASH_SUFFIX = Regex("(?i)\\s*[\\[\\(（【]\\s*[A-F0-9]{8,32}\\s*[\\]\\)）】]\\s*$")
    private val RESOLUTION_START = Regex("(?i)^\\s*[0-9]{3,4}\\s*[x×]\\s*[0-9]{3,4}(?=$|\\D)")
    private val RESOLUTION_TOKEN = Regex("(?i)(?<![0-9])[0-9]{3,4}\\s*[x×]\\s*[0-9]{3,4}(?![0-9])")

    private val BRACKET_NUMBER_TITLE = Regex("^\\s*[\\[【]\\s*([0-9]{1,4})\\s*[\\]】]\\s*(\\S.*)$")
    private val REPEATED_BRACKET_SERIES = Regex(
        "(?i)^\\s*[\\[【]\\s*([^\\]】]{1,24}?)\\s*[\\]】]\\s*\\1\\s*((?:S\\s*[0-9]{1,2}\\s*E\\s*[0-9]{1,4}|EP?\\s*[0-9]{1,4}|SP\\s*[0-9]{1,4}|[0-9]{1,4}(?:[.·][0-9]+)?))$TOKEN_END"
    )
    private val INLINE_EPISODE = Regex(
        "(?i)(?<![0-9])([0-9]{1,3}(?:\\.[0-9]+)?(?:SP)?)[\\s._-]*([\\p{sc=Han}々〆〇ヶ]+(?:[（(][^）)]{1,16}[）)])?)"
    )
    private val LEADING_FILE_EPISODE = Regex("(?i)^\\s*([0-9]{1,3})$NOT_TILDE_RANGE$TOKEN_END")

    private val BRACKET_TOKEN = Regex("[\\[【(（]\\s*([^\\]】)）]+?)\\s*[\\]】)）]")
    private val TECHNICAL_BRACKET = Regex(
        "(?i)(?:[0-9]{3,4}\\s*[x×]\\s*[0-9]{3,4}|(?:4320|2160|1080|720)P|[48]K|HDR10(?:\\+|⁺)?|HDR|SDR|DV|DOLBY|HEVC|H[.]?26[45]|X26[45]|AVC|AV1|AAC|FLAC|WEB-?DL|WEBRIP|BLU-?RAY|BDRIP|BD|MP4|MKV|AVI|TS|[A-F0-9]{8,32}|(?:简|繁|中|日|英|国|粤).*(?:字幕|双语|中字))"
    )
    private val TECHNICAL_DETAIL = Regex(
        "(?i).*(?:[0-9]{3,4}\\s*[x×]\\s*[0-9]{3,4}|(?:4320|2160|1080|720|576|480)P(?:60P)?|HI[0-9]+P|HDR10|HDR|SDR|DOLBY|HEVC|AVC|X26[45]|H[.]?26[45]|DIVX|AAC|AC3|EAC3|DDP|PCM|FLAC|DTS|TRUEHD|WEB-?DL|WEBRIP|BLU-?RAY|BDRIP|TVRIP|DVDRIP|HDRIP|MP4|MKV|AVI|M2TS).*"
    )
    private val LANGUAGE_DETAIL = Regex(
        "(?i)(?:(?:GB|CHS|CHT|BIG5)(?:[·._\\s-]*(?:CN|JAP|JP|CHS|CHT))*|.*(?:字幕|双语|中字|外挂|内嵌|简繁|繁中|简中).*)"
    )
    private val DATE_TOKEN = Regex("(?i)[0-9]{4}[-._][0-9]{1,2}[-._][0-9]{1,2}")

    private val EPISODE_START = Regex(
        "(?i)^(?:S\\s*[0-9]{1,2}\\s*E\\s*[0-9]{1,4}(?:\\s*(?:E|[-~—–])\\s*[0-9]{1,4})?|[0-9]{1,2}\\s*x\\s*[0-9]{1,4}(?:\\s*[-~—–]\\s*[0-9]{1,4})?|第\\s*[0-9一二三四五六七八九十百千零〇两兩]+\\s*(?:集|话|話|期|章|回|幕)|(?:最終|最终|特別|特别)\\s*幕|[0-9]{1,4}\\s*(?:集|话|話|期|章|回)|(?:EP|E)\\s*[0-9]{1,4}(?:\\s*[-~—–]\\s*[0-9]{1,4})?|[0-9]{4}[-._][0-9]{1,2}[-._][0-9]{1,2}|[0-9]{1,4}(?:\\D|$)|[上下](?:集|部)?|前篇|后篇|後篇|正片|预告|預告|花絮)"
    )
    private val WEAK_EPISODE_TOKEN = Regex("(?i)[上下](?:集|部)?|前篇|后篇|後篇|正片|预告|預告|花絮")
    private val VARIANT_TOKEN = Regex(
        "(?i)$TOKEN_START((?:4320|2160|1080|720)P|[48]K|HQ|HD|HDR10(?:\\+|⁺)?|HDR|SDR|DV|60FPS|50FPS|30FPS|25FPS|24FPS|10BITS|8BITS|HEVC|H265|H\\.265|AVC|H264|H\\.264|AV1|DDP\\s*2[.·]?0|AAC\\s*2[.·]?0)$TOKEN_END"
    )
    private val TAIL_CODE = Regex("(?i)[._\\-·]([0-9]{8,})$")

    private val EPISODE_TOKENS = listOf(
        Regex("(?i)S\\s*[0-9]{1,2}\\s*E\\s*[0-9]{1,4}(?:\\s*(?:E|[-~—–])\\s*[0-9]{1,4})?"),
        Regex("(?i)(?<![0-9])[0-9]{1,2}\\s*[x×]\\s*[0-9]{1,4}(?:\\s*[-~—–]\\s*[0-9]{1,4})?(?![0-9])"),
        Regex("(?i)(?:第\\s*[0-9一二三四五六七八九十百千零〇两兩]+\\s*幕|(?:最終|最终|特別|特别)\\s*幕)"),
        Regex("(?i)第\\s*[0-9一二三四五六七八九十百]+\\s*(?:集|话|話|期|章|回)"),
        Regex("(?i)[0-9]{1,4}\\s*(?:集|话|話|期|章|回)"),
        Regex("(?i)(?:EP|E)\\s*[0-9]{1,4}(?:\\s*[-~—–]\\s*[0-9]{1,4})?"),
        Regex("(?i)^\\s*([0-9]{1,3})$NOT_TILDE_RANGE$TOKEN_END"),
        DATE_TOKEN,
        Regex("(?i)$TOKEN_START([0-9]{1,4}v[0-9]+|[0-9]{1,3})$TOKEN_END"),
    )

    private val TECH_SUFFIX = Regex(
        "(?i)^[\\s._\\-\\[\\]()（）【】]+(?:4K|8K|2160P|1080P|720P|HDR|HDR10|DV|DOLBY|HEVC|H265|H\\.265|H264|H\\.264|AV1|AAC|FLAC|WEB-DL|WEBRIP|BLURAY|BD|HD|国语|国配|粤语|中字|中英双字|简中|繁中|内嵌字幕|无字)(?:[\\s._\\-\\[\\]()（）【】]+(?:4K|8K|2160P|1080P|720P|HDR|HDR10|DV|DOLBY|HEVC|H265|H\\.265|H264|H\\.264|AV1|AAC|FLAC|WEB-DL|WEBRIP|BLURAY|BD|HD|国语|国配|粤语|中字|中英双字|简中|繁中|内嵌字幕|无字))*[\\s._\\-\\[\\]()（）【】]*$"
    )
    private val FILE_EDGE_SEPARATORS = Regex("^[$SEPARATOR_CHARS]+|[$SEPARATOR_CHARS]+$")
    private val EDGE_SEPARATORS = Regex("^[$BOUNDARY_CHARS]+|[$BOUNDARY_CHARS]+$")
    private val DIGIT_RUN = Regex("(?<![0-9])[0-9]{1,4}(?![0-9])")
    private val NOISE_TOKEN = Regex(
        "(?i)$TOKEN_START(?:"
            + "[0-9]{3,4}\\s*[x×]\\s*[0-9]{3,4}|(?:4320|2160|1440|1080|720|576|480)[PI]|[248]K|UHD|FHD|HD|HQ|SD"
            + "|HDR10(?:\\+|⁺)?|HDR|SDR|DV|DOVI|DOLBY(?:\\s*VISION)?|HEVC|AVC|AV1|[HX][.]?26[45]"
            + "|AAC(?:\\s*[0-9][.·][0-9])?|E?AC3|DDP(?:\\s*[0-9][.·][0-9])?|DTS(?:-HD)?|TRUEHD|ATMOS|FLAC|PCM"
            + "|WEB-?DL|WEB-?RIP|BLU-?RAY|BDRIP|BD|TVRIP|HDTV|REMUX|MP4|MKV|AVI|TS"
            + "|[0-9]{2,3}\\s*FPS|(?:HI)?10\\s*-?\\s*BITS?|8\\s*-?\\s*BITS?"
            + "|[0-9]{4}[-._][0-9]{1,2}[-._][0-9]{1,2}"
            + "|国语|国配|粤语|中字|中英双字|简中|繁中|内嵌字幕|无字"
            + ")$TOKEN_END"
    )
    private val NOT_LETTER_OR_DIGIT = Regex("(?:[^\\p{L}\\p{N}]|[$BAR_LETTERS])+")

    /**
     * 按精简开关返回与 [episodes] 一一对应的展示名。
     * 关闭时直接返回原名，不做任何处理。
     */
    fun displayNames(episodes: List<Episode>, compact: Boolean): List<String> {
        if (episodes.isEmpty()) return emptyList()
        val raw = episodes.map { it.name }
        if (!compact) return raw
        return compactForDisplay(raw)
    }

    /** 列表展示用：优先不带体积，只有去掉体积后出现重名（同一集多版本）才保留。 */
    fun compactForDisplay(rawNames: List<String>): List<String> {
        val withSize = compact(rawNames, true)
        val withoutSize = compact(rawNames, false)
        if (withoutSize.size != withSize.size) return withSize
        for (i in withSize.indices) {
            val bare = withoutSize[i]
            if (bare.isEmpty()) return withSize
            if (withSize[i] != appendSize(bare, extractSize(rawNames[i]))) return withSize
        }
        return withoutSize
    }

    private fun compact(rawNames: List<String>, includeSize: Boolean): List<String> {
        if (rawNames.isEmpty()) return emptyList()
        val names = rawNames.map { cleanFileNoise(it) }
        val sizes = rawNames.map { if (includeSize) extractSize(it) else "" }

        if (rawNames.size < 2) {
            val structured = findCollectionDisplay(rawNames[0])
            return listOf(appendSize(structured.ifEmpty { names[0] }, sizes[0]))
        }

        val prefix = findPrefix(names)
        val suffix = findSuffix(names, prefix)
        val fallback = ArrayList<String>(names.size)
        val detectedTokens = ArrayList<String>(names.size)
        val collectionDisplays = ArrayList<String>(names.size)

        names.forEachIndexed { index, name ->
            val start = minOf(prefix, name.length)
            val end = maxOf(minOf(name.length - suffix, name.length), start)
            val slice = name.substring(start, end)
            val compacted = if (start == 0 && end == name.length) cleanupFileEdge(slice) else cleanupEdge(slice)
            fallback.add(compacted.ifEmpty { name })
            detectedTokens.add(findEpisodeToken(stripFileNoise(rawNames[index])))
            collectionDisplays.add(findCollectionDisplay(rawNames[index]))
        }

        val varyingNumbers = findVaryingNumberDisplays(fallback, collectionDisplays)
        val unifyTokens = shouldUnifyEpisodeTokens(fallback, detectedTokens, collectionDisplays)

        val compacted = ArrayList<String>(names.size)
        val tokens = ArrayList<String>(names.size)
        for (i in names.indices) {
            val collectionDisplay = collectionDisplays[i]
            val varyingNumber = varyingNumbers[i]
            val token = collectionDisplay.ifEmpty {
                varyingNumber.ifEmpty { detectedTokens[i] }
            }
            val base = when {
                collectionDisplay.isNotEmpty() -> collectionDisplay
                varyingNumber.isNotEmpty() -> varyingNumber
                unifyTokens && token.isNotEmpty() -> token
                else -> preferEpisodeToken(token, fallback[i])
            }
            compacted.add(appendSize(base, sizes[i]))
            tokens.add(token)
        }

        return resolveDuplicates(rawNames, compacted, fallback, tokens, sizes)
    }

    private fun cleanFileNoise(value: String): String = cleanupFileEdge(stripFileNoise(value))

    private fun cleanupFileEdge(text: String?): String {
        var value = FILE_EDGE_SEPARATORS.replace(text.orEmpty().trim(), "")
        while (hasEntireBracketWrapper(value)) {
            value = FILE_EDGE_SEPARATORS.replace(value.substring(1, value.length - 1).trim(), "")
        }
        return value
    }

    private fun hasEntireBracketWrapper(value: String): Boolean {
        if (value.length < 2) return false
        val open = value[0]
        val close = matchingBracket(open)
        if (close == '\u0000' || value[value.length - 1] != close) return false
        var depth = 0
        for (i in value.indices) {
            val current = value[i]
            if (current == open) depth++
            if (current == close && --depth == 0 && i < value.length - 1) return false
        }
        return depth == 0
    }

    private fun matchingBracket(open: Char): Char = when (open) {
        '[' -> ']'
        '【' -> '】'
        '(' -> ')'
        '（' -> '）'
        '《' -> '》'
        else -> '\u0000'
    }

    private fun stripFileNoise(value: String?): String {
        var text = if (value.isNullOrEmpty()) "" else value.trim()
        text = cleanEdgeNoise(text)
        text = EXTENSION.replace(text, "")
        text = cleanEdgeNoise(text)
        return text.trim()
    }

    private fun extractSize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val text = EXTENSION.replace(value.trim(), "")
        return extractSizeMatch(SIZE_SUFFIX.find(text)) ?: extractSizeMatch(SIZE_PREFIX.find(text)) ?: ""
    }

    private fun extractSizeMatch(match: MatchResult?): String? {
        if (match == null) return null
        val value = match.groupValues[1].replace(",", "")
        var unit = match.groupValues[2].uppercase(Locale.ROOT)
        unit = when (unit) {
            "T" -> "TB"
            "G" -> "GB"
            "M" -> "MB"
            else -> unit
        }
        return value + unit
    }

    private fun appendSize(display: String, size: String): String = when {
        size.isEmpty() -> display
        display.isEmpty() -> "[$size]"
        else -> "$display [$size]"
    }

    private fun cleanEdgeNoise(value: String): String {
        var text = HASH_SUFFIX.replace(value, "")
        text = SIZE_SUFFIX.replace(text, "")
        text = SIZE_PREFIX.replace(text, "")
        return text
    }

    // ---------------- 公共前后缀 ----------------

    private fun findPrefix(names: List<String>): Int {
        val prefix = commonPrefix(names)
        for (i in prefix downTo 1) if (isUsefulPrefix(names, i)) return i
        return 0
    }

    private fun commonPrefix(names: List<String>): Int {
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
        return prefix
    }

    private fun isUsefulPrefix(names: List<String>, index: Int): Boolean {
        if (index < 2) return false
        for (name in names) {
            if (index > name.length) return false
            val rest = cleanupEdge(name.substring(index))
            if (rest.isEmpty()) return false
            if (!isBoundary(name, index) && !canStartEpisodeAfterPrefix(name, index, rest)) return false
        }
        return index >= 6 || names.all { startsEpisode(cleanupEdge(it.substring(index))) }
    }

    private fun findSuffix(names: List<String>, prefix: Int): Int {
        val suffix = commonSuffix(names, prefix)
        for (i in suffix downTo 1) if (isUsefulSuffix(names, i, prefix)) return i
        return 0
    }

    private fun commonSuffix(names: List<String>, prefix: Int): Int {
        var suffix = names[0].length - minOf(prefix, names[0].length)
        for (name in names) {
            val max = name.length - minOf(prefix, name.length)
            suffix = minOf(suffix, max)
            for (i in 0 until suffix) {
                if (names[0][names[0].length - 1 - i] != name[name.length - 1 - i]) {
                    suffix = i
                    break
                }
            }
        }
        return suffix
    }

    private fun isUsefulSuffix(names: List<String>, suffix: Int, prefix: Int): Boolean {
        if (suffix < 2) return false
        for (name in names) {
            val start = name.length - suffix
            if (start <= prefix || !isBoundary(name, start)) return false
            if (cleanupEdge(name.substring(prefix, start)).isEmpty()) return false
        }
        val removed = names[0].substring(names[0].length - suffix)
        return suffix >= 6 || TECH_SUFFIX.matches(removed.uppercase(Locale.ROOT))
    }

    private fun isBoundary(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return true
        return isSeparator(text[index - 1]) || isSeparator(text[index])
    }

    private fun isSeparator(c: Char): Boolean =
        c.isWhitespace() || "-_.·|｜丨ㅣ/\\:：,，;；~～—–\u00A0\u3000[]()（）【】《》".indexOf(c) >= 0

    private fun startsEpisode(text: String): Boolean {
        if (RESOLUTION_START.containsMatchIn(text)) return false
        return EPISODE_START.containsMatchIn(text)
    }

    private fun canStartEpisodeAfterPrefix(text: String, index: Int, rest: String): Boolean {
        if (!startsEpisode(rest)) return false
        val previous = text[index - 1]
        return !previous.isDigit() && previous !in 'A'..'Z' && previous !in 'a'..'z'
    }

    // ---------------- 集数识别 ----------------

    private fun findEpisodeToken(text: String): String {
        for (pattern in EPISODE_TOKENS) {
            val match = pattern.find(text) ?: continue
            val token = if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) {
                match.groupValues[1]
            } else {
                match.value
            }
            if (isStandaloneYear(token)) continue
            return normalizeEpisodeToken(token)
        }
        for (match in WEAK_EPISODE_TOKEN.findAll(text)) {
            if (isInsideMetadataBracket(text, match.range.first)) continue
            return normalizeEpisodeToken(match.value)
        }
        for (match in BRACKET_TOKEN.findAll(text)) {
            val value = cleanupEdge(match.groupValues[1])
            if (WEAK_EPISODE_TOKEN.matches(value)) return normalizeEpisodeToken(value)
        }
        return ""
    }

    private fun isInsideMetadataBracket(text: String, index: Int): Boolean {
        val open = maxOf(text.lastIndexOf('[', index), text.lastIndexOf('【', index))
        val close = maxOf(text.lastIndexOf(']', index), text.lastIndexOf('】', index))
        return open > close
    }

    private fun normalizeEpisodeToken(token: String?): String {
        var value = token.orEmpty().replace(Regex("\\s+"), "").replace('·', '.').uppercase(Locale.ROOT)
        if (value.matches(Regex("[0-9]{4}[-._][0-9]{1,2}[-._][0-9]{1,2}"))) {
            value = value.replace('.', '-').replace('_', '-')
        }
        return value
    }

    private fun isStandaloneYear(token: String?): Boolean {
        if (token == null || !token.matches(Regex("[0-9]{4}"))) return false
        val year = token.toIntOrNull() ?: return false
        return year in 1900..2099
    }

    // ---------------- 结构识别 ----------------

    private fun findCollectionDisplay(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        findBracketNumberTitleDisplay(text)?.let { return it }
        findRepeatedBracketSeriesDisplay(text)?.let { return it }
        findLeadingFileDisplay(text)?.let { return it }
        return ""
    }

    /** 「【2】剧情标题」这类统一精简成「第2集」，避免同列表一部分被精简、一部分没变。 */
    private fun findBracketNumberTitleDisplay(text: String): String? {
        val match = BRACKET_NUMBER_TITLE.matchEntire(stripFileNoise(text)) ?: return null
        val number = match.groupValues[1]
        if (isStandaloneYear(number)) return null
        return "第${number}集"
    }

    private fun findRepeatedBracketSeriesDisplay(text: String): String? {
        val match = REPEATED_BRACKET_SERIES.find(stripFileNoise(text)) ?: return null
        val label = normalizeDisplayText(match.groupValues[1])
        if (!isEpisodeTitleCandidate(label)) return null
        return label + " " + normalizeEpisodeToken(match.groupValues[2])
    }

    /** 文件名风格：「[字幕组] 03 剧名 1080P」→「03」。 */
    private fun findLeadingFileDisplay(text: String): String? {
        val remainder = stripLeadingBracketGroup(stripFileNoise(text))
        val episode = LEADING_FILE_EPISODE.find(remainder) ?: return null
        return buildLeadingEpisodeDisplay(remainder, normalizeEpisodeToken(episode.groupValues[1]), episode.range.last + 1)
    }

    private fun stripLeadingBracketGroup(text: String): String {
        var start = 0
        while (start < text.length && text[start].isWhitespace()) start++
        if (start >= text.length) return text
        val open = text[start]
        val close = matchingBracket(open)
        if (close == '\u0000') return text
        var depth = 0
        for (i in start until text.length) {
            val current = text[i]
            if (current == open) depth++
            if (current != close) continue
            if (--depth == 0) return text.substring(i + 1).trim()
        }
        return text
    }

    private fun buildLeadingEpisodeDisplay(text: String, episode: String, end: Int): String {
        val remainder = text.substring(end).trim()
        if (!remainder.startsWith("纯享")) return episode
        val display = StringBuilder(episode).append(" 纯享")
        for (match in BRACKET_TOKEN.findAll(remainder.substring(2))) {
            val value = cleanupEdge(match.groupValues[1])
            if (value.matches(Regex("重置版|修复版|修復版"))) display.append(' ').append(value)
        }
        return display.toString()
    }

    private fun isEpisodeTitleCandidate(value: String): Boolean {
        if (value.isEmpty() || RESOLUTION_TOKEN.matches(value)) return false
        if (DATE_TOKEN.matches(value)) return false
        if (isTechnicalToken(value)) return false
        return !value.matches(Regex("(?i)(?:(?:EP?|S[0-9]{1,2}E|SP)?[0-9]{1,4}|[0-9]{1,3}(?:[.·][0-9]+)?(?:SP)?)"))
    }

    private fun isTechnicalToken(value: String): Boolean {
        val token = cleanupEdge(value)
        if (TECHNICAL_BRACKET.matches(token)) return true
        if (TECHNICAL_DETAIL.matches(token)) return true
        if (LANGUAGE_DETAIL.matches(token)) return true
        return token.matches(Regex("(?i)(?:END|V[0-9]+|(?:HI)?10-?BIT|8-?BIT)"))
    }

    // ---------------- 整列表推断 ----------------

    /**
     * 不依赖命名格式：把每条的「数字段」按出现顺序排成列，第一列满足
     * 「每条都有、互不重复、不是年份、跨度不超过条数两倍」的就当作集数。
     * 只有去掉该数字后只剩画质/编码噪音时才采用，带剧情标题的保持原样。
     */
    private fun findVaryingNumberDisplays(texts: List<String>, collectionDisplays: List<String>): List<String> {
        val result = MutableList(texts.size) { "" }
        if (texts.size < MIN_VARYING_NUMBER_ITEMS) return result

        val runs = ArrayList<List<IntRange>>(texts.size)
        var columns = Int.MAX_VALUE
        for (text in texts) {
            val positions = DIGIT_RUN.findAll(text).map { it.range }.toList()
            runs.add(positions)
            columns = minOf(columns, positions.size)
        }
        val column = findVaryingNumberColumn(texts, runs, columns)
        if (column < 0) return result
        for (i in texts.indices) {
            if (collectionDisplays[i].isNotEmpty()) continue
            val range = runs[i][column]
            val text = texts[i]
            val rest = text.substring(0, range.first) + " " + text.substring(range.last + 1)
            if (isOnlyTechnicalNoise(rest)) result[i] = text.substring(range.first, range.last + 1)
        }
        return result
    }

    private fun findVaryingNumberColumn(
        texts: List<String>,
        runs: List<List<IntRange>>,
        columns: Int,
    ): Int {
        for (column in 0 until columns) {
            val values = HashSet<Int>()
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            var valid = true
            for (i in texts.indices) {
                val range = runs[i][column]
                val value = texts[i].substring(range.first, range.last + 1)
                val number = value.toIntOrNull()
                if (number == null || isStandaloneYear(value) || !values.add(number)) {
                    valid = false
                    break
                }
                min = minOf(min, number)
                max = maxOf(max, number)
            }
            // 允许缺集，但跨度不能超过条数的两倍（排除 720/1080 这类偶然不同的数字）
            if (valid && max - min + 1 <= texts.size * 2) return column
        }
        return -1
    }

    private fun isOnlyTechnicalNoise(text: String): Boolean {
        val value = NOISE_TOKEN.replace(text, " ")
        return NOT_LETTER_OR_DIGIT.replace(value, "").isEmpty()
    }

    private fun preferEpisodeToken(token: String, compact: String): String {
        if (compact.length <= MAX_COMPACT_LENGTH) return compact
        return token.ifEmpty { compact }
    }

    /**
     * 只要有一条剥完前后缀后仍超过 [MAX_COMPACT_LENGTH] 且能找到集数，
     * 就让所有能找到集数的条目都只显示集数，避免长短混排。
     * 统一后会撞号（同集的正片/预告）则退回逐条判断。
     */
    private fun shouldUnifyEpisodeTokens(
        fallback: List<String>,
        tokens: List<String>,
        collectionDisplays: List<String>,
    ): Boolean {
        var needToken = false
        val seen = HashSet<String>()
        for (i in fallback.indices) {
            if (collectionDisplays[i].isNotEmpty()) continue
            val token = tokens[i]
            if (token.isEmpty()) continue
            if (!seen.add(token)) return false
            if (fallback[i].length > MAX_COMPACT_LENGTH) needToken = true
        }
        return needToken
    }

    // ---------------- 去重 ----------------

    private fun resolveDuplicates(
        names: List<String>,
        compacted: List<String>,
        fallback: List<String>,
        tokens: List<String>,
        sizes: List<String>,
    ): List<String> {
        val result = compacted.toMutableList()
        val duplicates = compacted.groupingBy { it }.eachCount().filterValues { it > 1 }
        for (display in duplicates.keys) {
            val indexes = compacted.indices.filter { compacted[it] == display }
            val distinct = buildDistinctDisplays(names, fallback, tokens, sizes, indexes)
            indexes.forEachIndexed { i, index -> result[index] = distinct[i] }
        }
        return ensureUnique(result)
    }

    private fun buildDistinctDisplays(
        names: List<String>,
        fallback: List<String>,
        tokens: List<String>,
        sizes: List<String>,
        indexes: List<Int>,
    ): List<String> {
        var firstCandidates: List<String>? = null
        for (level in 1..4) {
            val candidates = indexes.map { index ->
                appendSize(buildVariantDisplay(names[index], tokens[index], fallback[index], level), sizes[index])
            }
            if (level == 1) firstCandidates = candidates
            if (allDistinct(candidates)) return candidates
        }
        return ensureUnique(firstCandidates.orEmpty())
    }

    private fun buildVariantDisplay(name: String, token: String, fallback: String, level: Int): String {
        if (token.isEmpty()) return fallback
        val hints = findVariantHints(name)
        if (hints.isEmpty()) return token
        val builder = StringBuilder(token)
        for (i in 0 until minOf(level, hints.size)) builder.append(' ').append(hints[i])
        return builder.toString()
    }

    private fun findVariantHints(text: String): List<String> {
        val hints = ArrayList<String>()
        for (match in VARIANT_TOKEN.findAll(text)) addHint(hints, normalizeVariantToken(match.groupValues[1]))
        TAIL_CODE.find(text)?.let { addHint(hints, it.groupValues[1]) }
        return hints
    }

    private fun addHint(hints: MutableList<String>, hint: String) {
        if (hint.isEmpty() || hints.contains(hint)) return
        hints.add(hint)
    }

    private fun normalizeVariantToken(token: String): String {
        var value = token.replace(Regex("\\s+"), "").replace('⁺', '+').replace('·', '.').uppercase(Locale.ROOT)
        value = when (value) {
            "H265", "H.265", "X265" -> "HEVC"
            "H264", "H.264", "X264" -> "AVC"
            else -> value
        }
        return when {
            value.matches(Regex("[0-9]{3,4}P")) -> value.dropLast(1) + "p"
            value.matches(Regex("[0-9]{2}FPS")) -> value.dropLast(3) + "fps"
            value.matches(Regex("[0-9]{1,2}BITS")) -> value.dropLast(4) + "bits"
            value.matches(Regex("AAC2[.]?0")) -> "AAC2.0"
            value.matches(Regex("DDP2[.]?0")) -> "DDP2.0"
            else -> value.replace("HDR10+", "HDR10")
        }
    }

    private fun allDistinct(values: List<String>): Boolean = values.toSet().size == values.size

    private fun ensureUnique(values: List<String>): List<String> {
        val total = values.groupingBy { it }.eachCount()
        val used = HashMap<String, Int>()
        return values.map { value ->
            if ((total[value] ?: 0) <= 1) {
                value
            } else {
                val index = (used[value] ?: 0) + 1
                used[value] = index
                "$value-$index"
            }
        }
    }

    private fun cleanupEdge(text: String?): String = EDGE_SEPARATORS.replace(text.orEmpty().trim(), "")

    private fun normalizeDisplayText(value: String): String = cleanupEdge(value).replace(Regex("\\s+"), " ")
}
