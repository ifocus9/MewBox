package com.mewbox.tv.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import java.util.concurrent.ConcurrentHashMap

object DynamicTheme {

    private val schemeCache = ConcurrentHashMap<Triple<Int, Boolean, PaletteStyle>, ColorScheme>()

    fun customScheme(
        seedArgb: Int,
        isDark: Boolean = true,
        style: PaletteStyle = PaletteStyle.TonalSpot,
    ): ColorScheme = schemeCache.getOrPut(Triple(seedArgb, isDark, style)) {
        dynamicColorScheme(
            seedColor = Color(seedArgb),
            isDark = isDark,
            isAmoled = false,
            style = style,
        )
    }
}
