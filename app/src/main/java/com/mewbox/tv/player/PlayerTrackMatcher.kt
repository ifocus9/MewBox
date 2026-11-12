package com.mewbox.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.mewbox.tv.bean.TrackPreference
import java.util.Locale

/**
 * 播放器轨道智能比对与打分器：
 * 用于切集或恢复历史记录时，根据持久化的 TrackPreference 智能匹配当前媒体流中最佳的轨道。
 *
 * 解决常见问题：
 * 1. 同部剧不同集之间字幕 id 或码率有轻微变化导致全等比对失败
 * 2. 多语言/多字幕（如简中、繁中、英文）匹配混淆
 * 3. 视轨码率浮动但分辨率一致时的清晰度继承
 */
object PlayerTrackMatcher {

    /**
     * 格式化 Format 的关键标识描述（对齐 MewPlayer describeFormat）
     */
    fun describeFormat(format: Format): String {
        val parts = mutableListOf<String>()
        format.id?.let { parts.add("id=$it") }
        format.label?.let { parts.add("label=$it") }
        format.language?.let { parts.add("lang=$it") }
        format.codecs?.let { parts.add("codecs=$it") }
        format.sampleMimeType?.let { parts.add("mime=$it") }
        if (format.width != C.LENGTH_UNSET) parts.add("w=${format.width}")
        if (format.height != C.LENGTH_UNSET) parts.add("h=${format.height}")
        if (format.sampleRate != C.RATE_UNSET_INT) parts.add("sr=${format.sampleRate}")
        if (format.channelCount != C.LENGTH_UNSET) parts.add("ch=${format.channelCount}")
        return parts.joinToString(",")
    }

    data class MatchCandidate(
        val groupIndex: Int,
        val trackIndex: Int,
        val group: Tracks.Group,
        val format: Format,
        val score: Int,
    )

    /**
     * 规范化语言代码（zh-CN, chi, zho, chs, cht 等归一为 zh）
     */
    fun normalizeLanguage(lang: String?): String {
        if (lang.isNullOrBlank()) return ""
        val lower = lang.trim().lowercase(Locale.ROOT)
        return when {
            lower.startsWith("zh") || lower == "chi" || lower == "zho" || lower == "chs" || lower == "cht" -> "zh"
            lower.startsWith("en") || lower == "eng" -> "en"
            lower.startsWith("ja") || lower == "jpn" -> "ja"
            lower.startsWith("ko") || lower == "kor" -> "ko"
            lower.startsWith("yue") || lower == "cantonese" -> "yue"
            else -> lower
        }
    }

    /**
     * 在 Tracks 中查找与 TrackPreference 最匹配的轨道
     */
    fun findBestMatch(
        tracks: Tracks,
        preference: TrackPreference,
    ): MatchCandidate? {
        if (preference.isDisabled) return null

        val targetType = preference.type
        var bestCandidate: MatchCandidate? = null
        var maxScore = 0

        val groups = tracks.groups
        var groupIdx = 0
        for (group in groups) {
            if (group.type != targetType) {
                groupIdx++
                continue
            }

            for (trackIdx in 0 until group.length) {
                // 安全防线：如果轨道在当前设备不受解码器支持（例如无法解码的 DTS），不参与偏好自动匹配
                if (!group.isTrackSupported(trackIdx)) continue

                val format = group.getTrackFormat(trackIdx)
                val score = calculateScore(format, preference)
                if (score > maxScore) {
                    maxScore = score
                    bestCandidate = MatchCandidate(
                        groupIndex = groupIdx,
                        trackIndex = trackIdx,
                        group = group,
                        format = format,
                        score = score,
                    )
                }
            }
            groupIdx++
        }

        // 至少满足一项基本特征匹配（score >= 30）才认可匹配结果，避免把无关轨道硬套上去
        return if (maxScore >= 30) bestCandidate else null
    }

    /**
     * 针对 Format 和 Preference 计算多维度匹配分值
     */
    fun calculateScore(format: Format, preference: TrackPreference): Int {
        var score = 0
        val currentDesc = describeFormat(format)

        // 1. 压制参数描述完全一致（极高置信度）
        if (preference.format.isNotBlank() && currentDesc == preference.format) {
            score += 100
        }

        // 2. 轨道 Label / Name 匹配
        val currentLabel = format.label?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val prefLabel = preference.label?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val prefName = preference.name.trim().lowercase(Locale.ROOT)

        if (prefLabel.isNotBlank() && currentLabel.isNotBlank()) {
            if (currentLabel == prefLabel) {
                score += 80
            } else if (currentLabel.contains(prefLabel) || prefLabel.contains(currentLabel)) {
                score += 50
            }
        } else if (prefName.isNotBlank() && currentLabel.isNotBlank()) {
            if (prefName.contains(currentLabel) || currentLabel.contains(prefName)) {
                score += 45
            }
        }

        // 关键词亲和度检测（简中 / 繁中 / 粤语 / 国语 / 英语等）
        val affinityKeywords = listOf("简", "繁", "中", "粤", "国", "eng", "english", "jp", "日", "韩")
        for (kw in affinityKeywords) {
            val prefHas = prefLabel.contains(kw) || prefName.contains(kw)
            val currHas = currentLabel.contains(kw)
            if (prefHas && currHas) {
                score += 25
                break
            }
        }

        // 3. 语言代码匹配
        val normCurrLang = normalizeLanguage(format.language)
        val normPrefLang = normalizeLanguage(preference.language)
        if (normPrefLang.isNotBlank() && normCurrLang.isNotBlank()) {
            if (normCurrLang == normPrefLang) {
                score += 40
            }
        }

        // 4. 视轨专用：分辨率匹配
        if (preference.type == C.TRACK_TYPE_VIDEO) {
            if (preference.height > 0 && format.height == preference.height) {
                score += 70
                if (preference.width > 0 && format.width == preference.width) {
                    score += 20
                }
            }
        }

        return score
    }
}

