package com.mewbox.tv.api.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mewbox.tv.api.net.HostMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 全局偏好：本地地址映射、最近一次订阅地址。 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostMapping: HostMapping,
) {
    private val hostKey = stringPreferencesKey("host_mapping")
    private val configUrlKey = stringPreferencesKey("config_url")

    val hostMappingFlow: Flow<String> = context.dataStore.data.map { it[hostKey].orEmpty() }
    val configUrlFlow: Flow<String> = context.dataStore.data.map { it[configUrlKey].orEmpty() }

    /** 启动时把持久化的映射装载进内存。 */
    suspend fun load() {
        hostMapping.target = context.dataStore.data.first()[hostKey].orEmpty()
    }

    suspend fun setHostMapping(value: String) {
        val v = value.trim()
        hostMapping.target = v
        context.dataStore.edit { it[hostKey] = v }
    }

    suspend fun setConfigUrl(url: String) {
        context.dataStore.edit { it[configUrlKey] = url }
    }
}
