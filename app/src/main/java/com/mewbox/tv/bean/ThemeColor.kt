package com.mewbox.tv.bean

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 内置主题预设色 */
data class ThemeColorPreset(
    val name: String,
    val argb: Int,
)

/** HSV 色彩分量：hue ∈ [0, 360)，saturation ∈ [0, 1]，value ∈ [0, 1] */
data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

/**
 * 全局主题色领域模型与纯 Kotlin 色彩工具（无 Android/Compose 依赖）。
 */
object ThemeColor {

    const val DEFAULT_ARGB: Int = 0xFFFF2D55.toInt()

    val presets: List<ThemeColorPreset> = listOf(
        ThemeColorPreset("绯红", DEFAULT_ARGB),
        ThemeColorPreset("活力橙", 0xFFFF7A00.toInt()),
        ThemeColorPreset("翡翠绿", 0xFF10B981.toInt()),
        ThemeColorPreset("碧海青", 0xFF06B6D4.toInt()),
        ThemeColorPreset("晴空蓝", 0xFF007AFF.toInt()),
        ThemeColorPreset("星云紫", 0xFF7C3AED.toInt()),
    )

    fun normalizeArgb(argb: Int): Int = argb or (0xFF shl 24)

    /**
     * 解析十六进制颜色文本：去除空白并允许可选前导 `#`，
     * 仅接受严格 6 位（RRGGBB）或 8 位（AARRGGBB，忽略 alpha 强制不透明），非法返回 null。
     */
    fun parseHex(raw: String?): Int? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val hex = if (trimmed.startsWith("#")) trimmed.substring(1) else trimmed
        if (hex.length != 6 && hex.length != 8) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val rgbHex = if (hex.length == 8) hex.substring(2) else hex
        val rgb = rgbHex.toInt(16)
        return normalizeArgb(rgb)
    }

    fun toHexRgb(argb: Int): String = String.format(Locale.US, "%06X", argb and 0xFFFFFF)

    fun fromStorage(raw: String?): Int = parseHex(raw) ?: DEFAULT_ARGB

    fun findPreset(argb: Int): ThemeColorPreset? {
        val normalized = normalizeArgb(argb)
        return presets.firstOrNull { it.argb == normalized }
    }

    fun isPreset(argb: Int): Boolean = findPreset(argb) != null

    fun displayLabel(argb: Int): String =
        findPreset(argb)?.name ?: "#${toHexRgb(normalizeArgb(argb))}"

    /**
     * ARGB 转 HSV。当处于无色相灰阶（delta == 0）时保留 [fallbackHue]，避免色相滑块回跳。
     */
    fun argbToHsv(argb: Int, fallbackHue: Float = 0f): HsvColor {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val v = max
        val s = if (max <= 0f) 0f else delta / max
        val h = if (delta == 0f) {
            normalizeHue(fallbackHue)
        } else {
            val rawHue = when (max) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
            normalizeHue(rawHue)
        }
        return HsvColor(hue = h, saturation = s, value = v)
    }

    /**
     * HSV 转不透明 ARGB；支持 hue == 360f 端点。
     */
    fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val h = normalizeHue(hue)
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)

        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c

        val (r1, g1, b1) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun normalizeHue(hue: Float): Float {
        if (!hue.isFinite()) return 0f
        val mod = ((hue % 360f) + 360f) % 360f
        return if (mod >= 360f || mod == -0f) 0f else mod
    }
}
