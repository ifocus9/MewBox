package com.mewbox.tv.api.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mewbox.tv.api.net.HostMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 全局偏好：本地地址映射、最近一次订阅地址、搜索历史等。 */
@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val hostMapping: HostMapping,
) {
    private val hostKey = stringPreferencesKey("host_mapping")
    private val configUrlKey = stringPreferencesKey("config_url")
    private val configIdKey = stringPreferencesKey("config_id")
    private val searchHistoryKey = stringPreferencesKey("search_history")
    private val doubanUidKey = stringPreferencesKey("douban_uid")

    /** 选集「精简」开关：全局记忆，详情页抽屉与全屏选集面板共用（对齐 MewPlayer）。 */
    private val compactEpisodeKey = booleanPreferencesKey("compact_episode_title")

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var currentConfigUrl: String = ""
        private set

    @Volatile
    var currentConfigId: String = ""
        private set

    @Volatile
    var searchHistory: List<String> = emptyList()
        private set

    val hostMappingFlow: Flow<String> = context.dataStore.data.map { it[hostKey].orEmpty() }
    val configUrlFlow: Flow<String> = context.dataStore.data.map { it[configUrlKey].orEmpty() }
    val configIdFlow: Flow<String> = context.dataStore.data.map { it[configIdKey].orEmpty() }
    val doubanUidFlow: Flow<String> = context.dataStore.data.map { it[doubanUidKey].orEmpty() }
    val compactEpisodeTitleFlow: Flow<Boolean> = context.dataStore.data.map { it[compactEpisodeKey] ?: false }
    val searchHistoryFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        parseSearchHistory(prefs[searchHistoryKey].orEmpty())
    }

    /** 首次渲染前同步读取一次，避免抽屉打开瞬间按钮状态闪一下。 */
    @Volatile
    var compactEpisodeTitle: Boolean = false
        private set

    private fun parseSearchHistory(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<String>>(raw)
        }.getOrDefault(emptyList())
    }

    /** 启动时把持久化的映射与搜索历史装载进内存。 */
    suspend fun load() {
        val prefs = context.dataStore.data.first()
        val saved = prefs[hostKey].orEmpty()
        currentConfigUrl = prefs[configUrlKey].orEmpty()
        currentConfigId = prefs[configIdKey].orEmpty()
        compactEpisodeTitle = prefs[compactEpisodeKey] ?: false
        searchHistory = parseSearchHistory(prefs[searchHistoryKey].orEmpty())
        // 容错处理：若残留废弃的 10.0.2.2 模拟器旧映射，自动清除，优先使用 App 本地内置 Node.js 服务
        if (saved == "10.0.2.2") {
            setHostMapping("")
        } else {
            hostMapping.target = saved
        }
    }

    suspend fun setHostMapping(value: String) {
        val v = value.trim()
        hostMapping.target = v
        context.dataStore.edit { it[hostKey] = v }
    }

    suspend fun setConfigUrl(url: String) {
        val v = url.trim()
        currentConfigUrl = v
        context.dataStore.edit { it[configUrlKey] = v }
    }

    suspend fun setConfigId(id: String) {
        val v = id.trim()
        currentConfigId = v
        context.dataStore.edit { it[configIdKey] = v }
    }

    suspend fun setActiveConfig(url: String, id: String) {
        val u = url.trim()
        val i = id.trim()
        currentConfigUrl = u
        currentConfigId = i
        context.dataStore.edit {
            it[configUrlKey] = u
            it[configIdKey] = i
        }
    }

    suspend fun setCompactEpisodeTitle(value: Boolean) {
        compactEpisodeTitle = value
        context.dataStore.edit { it[compactEpisodeKey] = value }
    }

    /** 保存豆瓣个人 uid（传入值应为 normalizeDoubanUid 归一化后的纯数字）。 */
    suspend fun setDoubanUid(uid: String) {
        val v = uid.trim()
        context.dataStore.edit { it[doubanUidKey] = v }
    }

    suspend fun addSearchHistory(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseSearchHistory(prefs[searchHistoryKey].orEmpty())
            val updated = (listOf(kw) + current.filter { it != kw }).take(20)
            searchHistory = updated
            prefs[searchHistoryKey] = json.encodeToString(updated)
        }
    }

    suspend fun deleteSearchHistory(keyword: String) {
        context.dataStore.edit { prefs ->
            val current = parseSearchHistory(prefs[searchHistoryKey].orEmpty())
            val updated = current.filter { it != keyword }
            searchHistory = updated
            prefs[searchHistoryKey] = json.encodeToString(updated)
        }
    }

    suspend fun clearSearchHistory() {
        searchHistory = emptyList()
        context.dataStore.edit { prefs ->
            prefs.remove(searchHistoryKey)
        }
    }

    companion object {
        private val DOUBAN_PEOPLE_PATTERN = Regex("""people/(\d+)""")

        /**
         * 归一化豆瓣 uid 输入：纯数字直接通过；粘贴个人主页链接时提取 people 段数字；
         * 输入合法返回 uid（空输入返回空串代表清除），无法识别返回 null。
         */
        fun normalizeDoubanUid(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.all { it.isDigit() }) return trimmed
            return DOUBAN_PEOPLE_PATTERN.find(trimmed)?.groupValues?.getOrNull(1)
        }
    }
}
