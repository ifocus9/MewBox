package com.mewbox.tv.api.tmdb

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.mewbox.tv.bean.TmdbConfig

private val Context.tmdbDataStore by preferencesDataStore(name = "tmdb_settings")

/**
 * TMDB 配置持久化仓库
 */
@Singleton
class TmdbRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keyEnable = booleanPreferencesKey("tmdb_enable")
    private val keyApiKey = stringPreferencesKey("tmdb_api_key")
    private val keyApiBase = stringPreferencesKey("tmdb_api_base")
    private val keyImageBase = stringPreferencesKey("tmdb_image_base")

    @Volatile
    private var memoryCachedConfig: TmdbConfig? = null

    val tmdbConfigFlow: Flow<TmdbConfig> = context.tmdbDataStore.data.map { prefs ->
        TmdbConfig(
            enable = prefs[keyEnable] ?: false,
            apiKey = prefs[keyApiKey].orEmpty(),
            apiBase = prefs[keyApiBase].orEmpty(),
            imageBase = prefs[keyImageBase].orEmpty(),
        ).also { memoryCachedConfig = it }
    }

    suspend fun getTmdbConfig(): TmdbConfig {
        memoryCachedConfig?.let { return it }
        return tmdbConfigFlow.first().also { memoryCachedConfig = it }
    }

    suspend fun save(config: TmdbConfig) {
        memoryCachedConfig = config
        context.tmdbDataStore.edit { prefs ->
            prefs[keyEnable] = config.enable
            prefs[keyApiKey] = config.apiKey.trim()
            prefs[keyApiBase] = config.apiBase.trim()
            prefs[keyImageBase] = config.imageBase.trim()
        }
    }

    suspend fun setEnable(enable: Boolean) {
        memoryCachedConfig = memoryCachedConfig?.copy(enable = enable)
        context.tmdbDataStore.edit { prefs ->
            prefs[keyEnable] = enable
        }
    }
}
