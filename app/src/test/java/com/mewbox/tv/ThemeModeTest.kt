package com.mewbox.tv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.HostMapping
import com.mewbox.tv.bean.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun fromStorage_handlesNullBlankCaseAndUnknown() {
        assertEquals(ThemeMode.Auto, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.Auto, ThemeMode.fromStorage(""))
        assertEquals(ThemeMode.Auto, ThemeMode.fromStorage("   "))
        assertEquals(ThemeMode.Auto, ThemeMode.fromStorage("unknown"))

        assertEquals(ThemeMode.Auto, ThemeMode.fromStorage("Auto"))
        assertEquals(ThemeMode.Dark, ThemeMode.fromStorage("DARK"))
        assertEquals(ThemeMode.Light, ThemeMode.fromStorage(" Light "))

        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorage(mode.storageValue))
        }
    }

    @Test
    fun isDark_truthTable() {
        assertFalse(ThemeMode.Auto.isDark(systemDark = false))
        assertTrue(ThemeMode.Auto.isDark(systemDark = true))

        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
        assertTrue(ThemeMode.Dark.isDark(systemDark = true))

        assertFalse(ThemeMode.Light.isDark(systemDark = false))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
    }

    @Test
    fun settingsRepository_persistsAndEmitsThemeMode() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val settings = SettingsRepository(store, HostMapping())

        settings.load()
        assertEquals(ThemeMode.Auto, settings.currentThemeMode)
        assertEquals(ThemeMode.Auto, settings.themeModeFlow.first())

        settings.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, settings.currentThemeMode)
        assertEquals(ThemeMode.Dark, settings.themeModeFlow.first())

        val reloadedFromDark = SettingsRepository(store, HostMapping())
        reloadedFromDark.load()
        assertEquals(ThemeMode.Dark, reloadedFromDark.currentThemeMode)

        reloadedFromDark.setThemeMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, reloadedFromDark.currentThemeMode)
        assertEquals(ThemeMode.Light, reloadedFromDark.themeModeFlow.first())

        val reloadedFromLight = SettingsRepository(store, HostMapping())
        reloadedFromLight.load()
        assertEquals(ThemeMode.Light, reloadedFromLight.currentThemeMode)

        reloadedFromLight.setThemeMode(ThemeMode.Auto)
        assertEquals(ThemeMode.Auto, reloadedFromLight.currentThemeMode)
        assertEquals(ThemeMode.Auto, reloadedFromLight.themeModeFlow.first())
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
