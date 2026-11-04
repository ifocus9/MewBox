package com.mewbox.tv.ui.theme

import android.os.Build

const val GLASS_BACKDROP_BAND_MARGIN_DP = 64

data class LiquidGlassConfig(
    val navbarEnabled: Boolean = true,
    val controlsEnabled: Boolean = true,
    val blurDp: Float = 5f,
    val distortionDp: Float = 30f,
    val translucency: Float = 0.5f,
    val dispersion: Boolean = true,
) {
    val containerAlphaScale: Float get() = 2f - translucency * 2f

    val contentBrightness: Float get() = (0.5f - translucency).coerceAtLeast(0f) * 0.12f

    val contentContrast: Float get() = 1f - (0.5f - translucency).coerceAtLeast(0f) * 0.25f

    val contentSaturation: Float get() = 1.5f - (translucency - 0.5f).coerceAtLeast(0f)
}

object LiquidGlassDefaults {
    val Config = LiquidGlassConfig()

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

