package com.mewbox.tv.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * 播放器轨道展示信息格式化工具
 * 参考 MewPlayer (FongMi) 的成熟规则，将 Media3 Format 解析为本地化可读文本与技术参数
 */
object PlayerTrackFormatter {

    /**
     * 生成轨道主标题（如 "中文 (简体中文), Simplified, SRT"、"1080P · HDR10"、"国语 (杜比音轨)"）
     */
    fun formatTrackTitle(format: Format, type: Int, trackIndex: Int): String {
        return when (type) {
            C.TRACK_TYPE_TEXT -> formatSubtitleTitle(format, trackIndex)
            C.TRACK_TYPE_AUDIO -> formatAudioTitle(format, trackIndex)
            C.TRACK_TYPE_VIDEO -> formatVideoTitle(format, trackIndex)
            else -> "轨道 ${trackIndex + 1}"
        }
    }

    /**
     * 生成轨道副标题/技术细节（如 "5.1 声道 · 48 kHz · E-AC-3"，字幕返回 null 避免暴露内部 cue 等废话）
     */
    fun formatTrackDetails(format: Format, type: Int): String? {
        return when (type) {
            C.TRACK_TYPE_TEXT -> null // 字幕主标题已完整表达语言与格式，不展示冗余副标题（杜绝 X-MEDIA3-CUES）
            C.TRACK_TYPE_AUDIO -> formatAudioDetails(format)
            C.TRACK_TYPE_VIDEO -> formatVideoDetails(format)
            else -> null
        }
    }

    /**
     * 格式化字幕主标题：[语言名称], [自定义标签], [字幕格式]
     */
    private fun formatSubtitleTitle(format: Format, trackIndex: Int): String {
        val langName = buildLanguageDisplayName(format)
        val label = format.label?.trim()?.takeIf { it.isNotBlank() }
        val formatName = getSubtitleFormatDisplayName(format)

        val components = mutableListOf<String>()

        // 1. 语言名称
        if (!langName.isNullOrBlank()) {
            components.add(langName)
        }

        // 2. 自定义标签（若存在且不与语言名称重复）
        if (!label.isNullOrBlank()) {
            val isDuplicate = langName != null && (
                langName.contains(label, ignoreCase = true) ||
                    label.contains(langName, ignoreCase = true)
                )
            if (!isDuplicate) {
                components.add(label)
            }
        }

        // 3. 字幕格式（SRT, ASS, VTT, PGS 等）
        if (!formatName.isNullOrBlank()) {
            components.add(formatName)
        }

        return if (components.isNotEmpty()) {
            components.joinToString(", ")
        } else {
            "字幕 ${trackIndex + 1}"
        }
    }

    /**
     * 格式化音频主标题：[语言名称], [标签]
     */
    private fun formatAudioTitle(format: Format, trackIndex: Int): String {
        val langName = buildLanguageDisplayName(format)
        val label = format.label?.trim()?.takeIf { it.isNotBlank() }

        val components = mutableListOf<String>()
        if (!langName.isNullOrBlank()) {
            components.add(langName)
        }
        if (!label.isNullOrBlank()) {
            val isDuplicate = langName != null && (
                langName.contains(label, ignoreCase = true) ||
                    label.contains(langName, ignoreCase = true)
                )
            if (!isDuplicate) {
                components.add(label)
            }
        }

        return if (components.isNotEmpty()) {
            components.joinToString(", ")
        } else {
            "音轨 ${trackIndex + 1}"
        }
    }

    /**
     * 格式化视频主标题：分辨率、HDR 标识
     */
    private fun formatVideoTitle(format: Format, trackIndex: Int): String {
        val resolution = when {
            format.width >= 3840 || format.height >= 2160 -> "4K (${format.width}x${format.height})"
            format.width >= 1920 || format.height >= 1080 -> "1080P"
            format.width >= 1280 || format.height >= 720 -> "720P"
            format.width > 0 && format.height > 0 -> "${format.width}x${format.height}"
            else -> null
        }

        val hdr = getHdrDisplayName(format)
        val label = format.label?.trim()?.takeIf { it.isNotBlank() }

        val components = mutableListOf<String>()
        if (resolution != null) components.add(resolution)
        if (!hdr.isNullOrBlank()) components.add(hdr)
        if (!label.isNullOrBlank() && label != resolution && label != hdr) components.add(label)

        return if (components.isNotEmpty()) {
            components.joinToString(" · ")
        } else {
            "视轨 ${trackIndex + 1}"
        }
    }

    /**
     * 音频技术细节：声道数 · 采样率 · 码率 · 编码格式
     */
    private fun formatAudioDetails(format: Format): String? {
        val details = buildList {
            if (format.channelCount > 0) {
                when (format.channelCount) {
                    1 -> add("单声道")
                    2 -> add("立体声")
                    6 -> add("5.1 声道")
                    8 -> add("7.1 声道")
                    else -> add("${format.channelCount} 声道")
                }
            }
            if (format.sampleRate > 0) {
                add("${format.sampleRate / 1000} kHz")
            }
            if (format.bitrate > 0) {
                add("${format.bitrate / 1000} kbps")
            }
            val codecName = getAudioCodecDisplayName(format)
            if (!codecName.isNullOrBlank()) {
                add(codecName)
            }
        }
        return details.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /**
     * 视频技术细节：分辨率 · 码率 · 帧率 · 编码格式
     */
    private fun formatVideoDetails(format: Format): String? {
        val details = buildList {
            if (format.width > 0 && format.height > 0) {
                add("${format.width}x${format.height}")
            }
            if (format.bitrate > 0) {
                val kbps = format.bitrate / 1000
                if (kbps >= 10000) {
                    add(String.format(Locale.US, "%.1f Mbps", kbps / 1000f))
                } else {
                    add("$kbps kbps")
                }
            }
            if (format.frameRate > 0f) {
                add("${format.frameRate.toInt()} fps")
            }
            val codecName = getVideoCodecDisplayName(format)
            if (!codecName.isNullOrBlank()) {
                add(codecName)
            }
        }
        return details.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /**
     * 解析语言本地化显示名称（标准中文呈现）
     */
    fun buildLanguageDisplayName(format: Format): String? {
        var lang = format.language?.trim()?.lowercase() ?: return null
        if (lang.isEmpty() || lang == C.LANGUAGE_UNDETERMINED || lang == "und") {
            return null
        }

        // 规范化特殊语言代码
        if (lang == "awr" || lang == "zh-cmn") lang = "zh"
        if (lang == "awq" || lang == "qph") lang = "yue"
        if (lang == "chs") lang = "zh-hans"
        if (lang == "cht") lang = "zh-hant"

        // 通用中文 zh/chi/zho：若 label 或 id 含有简繁倾向则补全
        if (lang == "zh" || lang == "chi" || lang == "zho") {
            val hint = "${format.label.orEmpty()} ${format.id.orEmpty()}".lowercase()
            lang = when {
                hint.contains("简") || hint.contains("hans") || hint.contains("simplified") -> "zh-hans"
                hint.contains("繁") || hint.contains("hant") || hint.contains("traditional") -> "zh-hant"
                else -> "zh"
            }
        }

        val locale = Locale.forLanguageTag(lang)
        val displayLocale = Locale.SIMPLIFIED_CHINESE
        val displayName = locale.getDisplayName(displayLocale).trim()

        if (displayName.isBlank() || displayName.equals(lang, ignoreCase = true)) {
            return when (lang) {
                "zh-hans" -> "中文 (简体中文)"
                "zh-hant" -> "中文 (繁体中文)"
                "zh" -> "中文"
                "yue" -> "粤语"
                "en" -> "英语"
                "ja" -> "日语"
                "ko" -> "韩语"
                else -> lang.uppercase()
            }
        }

        val normalized = when (displayName) {
            "中文 (简体)", "中文(简体)" -> "中文 (简体中文)"
            "中文 (繁体)", "中文(繁体)" -> "中文 (繁体中文)"
            else -> displayName
        }

        return normalized.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString()
        }
    }

    /**
     * 解析字幕真实格式名称（SRT, ASS, VTT, PGS 等）
     */
    fun getSubtitleFormatDisplayName(format: Format): String? {
        val mime = format.sampleMimeType?.lowercase().orEmpty()
        val codecs = format.codecs?.lowercase().orEmpty()
        val id = format.id?.lowercase().orEmpty()

        return when {
            mime == MimeTypes.APPLICATION_SUBRIP.lowercase() ||
                codecs.contains("subrip") || codecs.contains("srt") || id.endsWith(".srt") -> "SRT"

            mime == MimeTypes.TEXT_SSA.lowercase() || mime == "text/x-ssa" ||
                codecs.contains("ssa") || codecs.contains("ass") || id.endsWith(".ass") || id.endsWith(".ssa") -> "ASS"

            mime == MimeTypes.TEXT_VTT.lowercase() || mime == MimeTypes.APPLICATION_MP4VTT.lowercase() ||
                codecs.contains("vtt") || id.endsWith(".vtt") -> "VTT"

            mime == MimeTypes.APPLICATION_PGS.lowercase() ||
                codecs.contains("pgs") || codecs.contains("hdmv") -> "PGS"

            mime == MimeTypes.APPLICATION_DVBSUBS.lowercase() || codecs.contains("dvb") -> "DVB"
            mime == MimeTypes.APPLICATION_VOBSUB.lowercase() || codecs.contains("vobsub") -> "VobSub"
            mime == MimeTypes.APPLICATION_TTML.lowercase() || codecs.contains("ttml") -> "TTML"
            mime == MimeTypes.APPLICATION_TX3G.lowercase() || codecs.contains("tx3g") -> "TX3G"
            mime == MimeTypes.APPLICATION_CEA608.lowercase() || mime == MimeTypes.APPLICATION_MP4CEA608.lowercase() -> "CEA-608"
            mime == MimeTypes.APPLICATION_CEA708.lowercase() -> "CEA-708"
            // 内部 cue 渲染器或未知格式直接返回 null，杜绝暴露技术名称给用户
            else -> null
        }
    }

    /**
     * 解析音频编解码器名称
     */
    private fun getAudioCodecDisplayName(format: Format): String? {
        val mime = format.sampleMimeType ?: return null
        return when {
            mime.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) -> {
                if (format.codecs?.contains("joc", ignoreCase = true) == true) "E-AC-3 JOC" else "E-AC-3"
            }
            mime.equals(MimeTypes.AUDIO_AC3, ignoreCase = true) -> "AC-3"
            mime.equals(MimeTypes.AUDIO_AAC, ignoreCase = true) -> "AAC"
            mime.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true) -> "FLAC"
            mime.equals(MimeTypes.AUDIO_OPUS, ignoreCase = true) -> "Opus"
            mime.equals(MimeTypes.AUDIO_TRUEHD, ignoreCase = true) -> {
                if (format.codecs?.contains("atmos", ignoreCase = true) == true) "TrueHD Atmos" else "TrueHD"
            }
            mime.contains("dts", ignoreCase = true) || format.codecs?.contains("dts", ignoreCase = true) == true -> {
                when {
                    format.codecs?.contains("x", ignoreCase = true) == true -> "DTS:X"
                    mime.equals(MimeTypes.AUDIO_DTS_HD, ignoreCase = true) ||
                        mime.contains("dts.hd", ignoreCase = true) ||
                        mime.contains("dts-hd", ignoreCase = true) -> "DTS-HD"
                    mime.equals(MimeTypes.AUDIO_DTS_EXPRESS, ignoreCase = true) -> "DTS Express"
                    else -> "DTS"
                }
            }
            mime.equals(MimeTypes.AUDIO_MPEG, ignoreCase = true) -> "MP3"
            mime.startsWith("audio/raw", ignoreCase = true) -> "PCM"
            mime.equals(MimeTypes.AUDIO_VORBIS, ignoreCase = true) -> "Vorbis"
            else -> mime.substringAfterLast('/').uppercase().takeUnless { it.isBlank() }
        }
    }

    /**
     * 解析视频编解码器名称
     */
    private fun getVideoCodecDisplayName(format: Format): String? {
        val mime = format.sampleMimeType ?: return null
        return when {
            mime.equals(MimeTypes.VIDEO_H265, ignoreCase = true) -> "H.265"
            mime.equals(MimeTypes.VIDEO_H264, ignoreCase = true) -> "H.264"
            mime.equals(MimeTypes.VIDEO_AV1, ignoreCase = true) -> "AV1"
            mime.equals(MimeTypes.VIDEO_VP9, ignoreCase = true) -> "VP9"
            mime.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) -> "Dolby Vision"
            else -> mime.substringAfterLast('/').uppercase().takeUnless { it.isBlank() }
        }
    }

    /**
     * 视频 HDR / 杜比视界标签解析
     */
    private fun getHdrDisplayName(format: Format): String? {
        if (format.sampleMimeType?.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) == true) {
            return "杜比视界"
        }
        val colorInfo: ColorInfo? = format.colorInfo
        if (colorInfo != null) {
            if (colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) return "HLG"
            if (colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) return "HDR10"
        }
        return null
    }
}
