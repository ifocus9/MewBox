package com.mewbox.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.HostMapping
import com.mewbox.tv.bean.ThemeColor
import com.mewbox.tv.ui.theme.buildDarkColorScheme
import com.mewbox.tv.ui.theme.buildDarkExtendedColors
import com.mewbox.tv.ui.theme.buildLightColorScheme
import com.mewbox.tv.ui.theme.buildLightExtendedColors
import com.mewbox.tv.ui.theme.contrastOnAccent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorTest {

    @Test
    fun parseHex_handlesValidAndInvalidInputs() {
        assertEquals(0xFFDBDE52.toInt(), ThemeColor.parseHex("DBDE52"))
        assertEquals(0xFFDBDE52.toInt(), ThemeColor.parseHex("#dbde52"))
        assertEquals(0xFFDBDE52.toInt(), ThemeColor.parseHex("  #DbDe52 "))
        assertEquals(0xFFDBDE52.toInt(), ThemeColor.parseHex("80DBDE52"))
        assertEquals(0xFFFF2D55.toInt(), ThemeColor.parseHex("#00FF2D55"))

        assertNull(ThemeColor.parseHex(null))
        assertNull(ThemeColor.parseHex(""))
        assertNull(ThemeColor.parseHex("   "))
        assertNull(ThemeColor.parseHex("#"))
        assertNull(ThemeColor.parseHex("12345"))
        assertNull(ThemeColor.parseHex("1234567"))
        assertNull(ThemeColor.parseHex("123456789"))
        assertNull(ThemeColor.parseHex("GGGGGG"))
        assertNull(ThemeColor.parseHex("-12345"))
        assertNull(ThemeColor.parseHex("+12345"))
        assertNull(ThemeColor.parseHex("#-12345"))
    }

    @Test
    fun hexFormattingStorageAndPresetLabels_workAsExpected() {
        assertEquals("FF2D55", ThemeColor.toHexRgb(ThemeColor.DEFAULT_ARGB))
        assertEquals("DBDE52", ThemeColor.toHexRgb(0x00DBDE52))

        assertEquals(ThemeColor.DEFAULT_ARGB, ThemeColor.fromStorage(null))
        assertEquals(ThemeColor.DEFAULT_ARGB, ThemeColor.fromStorage("invalid"))
        assertEquals(0xFFDBDE52.toInt(), ThemeColor.fromStorage("#dbde52"))

        assertEquals("绯红", ThemeColor.findPreset(ThemeColor.DEFAULT_ARGB)?.name)
        assertEquals("绯红", ThemeColor.findPreset(0x00FF2D55)?.name)
        assertTrue(ThemeColor.isPreset(0xFF007AFF.toInt()))
        assertFalse(ThemeColor.isPreset(0xFFDBDE52.toInt()))
        assertNull(ThemeColor.findPreset(0xFFDBDE52.toInt()))

        assertEquals("晴空蓝", ThemeColor.displayLabel(0xFF007AFF.toInt()))
        assertEquals("#DBDE52", ThemeColor.displayLabel(0xFFDBDE52.toInt()))
    }

    @Test
    fun hsvRoundTrip_preservesColorsAndFallbackHue() {
        val samples = ThemeColor.presets.map { it.argb } + listOf(
            0xFFDBDE52.toInt(),
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFFFF00FF.toInt(),
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF808080.toInt(),
        )
        samples.forEach { argb ->
            val hsv = ThemeColor.argbToHsv(argb)
            val roundTrip = ThemeColor.hsvToArgb(hsv.hue, hsv.saturation, hsv.value)
            assertEquals("Round-trip failed for ${ThemeColor.toHexRgb(argb)}", argb, roundTrip)
        }

        val blackHsv = ThemeColor.argbToHsv(0xFF000000.toInt(), fallbackHue = 215f)
        assertEquals(215f, blackHsv.hue, 0.01f)
        assertEquals(0f, blackHsv.saturation, 0.01f)
        assertEquals(0f, blackHsv.value, 0.01f)

        val whiteHsv = ThemeColor.argbToHsv(0xFFFFFFFF.toInt(), fallbackHue = 142f)
        assertEquals(142f, whiteHsv.hue, 0.01f)
        assertEquals(0f, whiteHsv.saturation, 0.01f)
        assertEquals(1f, whiteHsv.value, 0.01f)

        val grayHsv = ThemeColor.argbToHsv(0xFF777777.toInt(), fallbackHue = 310f)
        assertEquals(310f, grayHsv.hue, 0.01f)
        assertEquals(0f, grayHsv.saturation, 0.01f)

        assertEquals(0xFFFF0000.toInt(), ThemeColor.hsvToArgb(360f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), ThemeColor.hsvToArgb(0f, 1f, 1f))
    }

    @Test
    fun settingsRepository_persistsEmitsAndFallbacksThemeColor() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val settings = SettingsRepository(store, HostMapping())

        settings.load()
        assertEquals(ThemeColor.DEFAULT_ARGB, settings.currentThemeColorArgb)
        assertEquals(ThemeColor.DEFAULT_ARGB, settings.themeColorArgbFlow.first())

        val preset = 0xFF007AFF.toInt()
        settings.setThemeColorArgb(preset)
        assertEquals(preset, settings.currentThemeColorArgb)
        assertEquals(preset, settings.themeColorArgbFlow.first())

        val reloadedPreset = SettingsRepository(store, HostMapping())
        reloadedPreset.load()
        assertEquals(preset, reloadedPreset.currentThemeColorArgb)

        val custom = 0xFFDBDE52.toInt()
        reloadedPreset.setThemeColorArgb(custom)
        assertEquals(custom, reloadedPreset.currentThemeColorArgb)
        assertEquals(custom, reloadedPreset.themeColorArgbFlow.first())

        val reloadedCustom = SettingsRepository(store, HostMapping())
        reloadedCustom.load()
        assertEquals(custom, reloadedCustom.currentThemeColorArgb)

        store.edit { it[stringPreferencesKey("theme_color")] = "NOT_A_HEX" }
        val reloadedCorrupted = SettingsRepository(store, HostMapping())
        reloadedCorrupted.load()
        assertEquals(ThemeColor.DEFAULT_ARGB, reloadedCorrupted.currentThemeColorArgb)
        assertEquals(ThemeColor.DEFAULT_ARGB, reloadedCorrupted.themeColorArgbFlow.first())
    }

    @Test
    fun themeColorDerivation_maintainsContrastAndEdgeReadability() {
        val darkText = Color(0xFF16181D)
        ThemeColor.presets.forEach { preset ->
            assertEquals("Preset ${preset.name} should use white onPrimary", Color.White, contrastOnAccent(Color(preset.argb)))
            val lightScheme = buildLightColorScheme(preset.argb)
            assertEquals(Color.White, lightScheme.onPrimary)
            assertTrue(lightScheme.onPrimaryContainer.luminance() <= 0.28f)
        }

        val yellowGreen = 0xFFDBDE52.toInt()
        assertEquals(darkText, contrastOnAccent(Color(yellowGreen)))
        val yellowLightScheme = buildLightColorScheme(yellowGreen)
        assertEquals(darkText, yellowLightScheme.onPrimary)
        assertTrue(yellowLightScheme.onPrimaryContainer.luminance() <= 0.28f)

        val whiteArgb = 0xFFFFFFFF.toInt()
        assertEquals(darkText, contrastOnAccent(Color.White))
        val whiteLightScheme = buildLightColorScheme(whiteArgb)
        assertNotEquals(Color.White, whiteLightScheme.primary)
        assertTrue(whiteLightScheme.primary.luminance() < 0.85f)
        assertTrue(whiteLightScheme.onPrimaryContainer.luminance() <= 0.28f)

        val blackArgb = 0xFF000000.toInt()
        val blackDarkScheme = buildDarkColorScheme(blackArgb)
        assertNotEquals(Color.Black, blackDarkScheme.primary)
        assertTrue(blackDarkScheme.primary.luminance() > 0.05f)
        assertTrue(blackDarkScheme.primary.luminance() > blackDarkScheme.surface.luminance() * 4f)

        val lightExt = buildLightExtendedColors(yellowGreen)
        val darkExt = buildDarkExtendedColors(yellowGreen)
        assertEquals(darkExt.panelAccent, lightExt.panelAccent)
        assertEquals(darkExt.accentOnDark, lightExt.accentOnDark)
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val updated = transform(state.value)
                state.value = updated
                updated
            }
    }
}
