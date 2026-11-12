package com.mewbox.tv.api.tmdb

import android.content.Context
import androidx.collection.LruCache
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB 两级缓存：
 * 1. 内存 LruCache — 最近 50 条详情，进程存活期间有效
 * 2. 磁盘负缓存 — 仅持久化「接口成功返回零候选」的记录，7 天过期，避免冷门片反复触发无效网络请求
 * 3. 磁盘手动修正缓存 — 持久化用户手动指定的 TMDB 匹配结果，永久生效
 *
 * 注意：超时 / 网络错误 / 严格匹配未命中但仍有候选，都不写负缓存。
 */
@Singleton
class TmdbCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private val NEGATIVE_TTL_MS = TimeUnit.DAYS.toMillis(7)
        private const val PREF_NAME = "tmdb_negative_cache"
        private const val PREF_MANUAL_NAME = "tmdb_manual_overrides"
        // v2：旧版把「严格匹配未命中」也写进负缓存，导致译名差异片被误判 7 天不换海报。
        // 换前缀让所有 v1 条目一次性失效，无须清 App 数据。
        private const val KEY_NEG_PREFIX = "tmdb_neg_v2_"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val detailCache = LruCache<String, TmdbDetail>(50)
    private val prefs by lazy { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }
    private val manualPrefs by lazy { context.getSharedPreferences(PREF_MANUAL_NAME, Context.MODE_PRIVATE) }

    fun putDetail(mediaType: String, tmdbId: Int, detail: TmdbDetail) {
        detailCache.put("${mediaType}_$tmdbId", detail)
    }

    fun getDetail(mediaType: String, tmdbId: Int): TmdbDetail? {
        return detailCache.get("${mediaType}_$tmdbId")
    }

    fun putDetailByTitle(cleanedTitle: String, detail: TmdbDetail) {
        detailCache.put("title_$cleanedTitle", detail)
    }

    /**
     * 保存用户手动修正的 TMDB 匹配结果（同时写入内存与磁盘持久化）
     */
    fun putManualOverride(cleanedTitle: String, detail: TmdbDetail) {
        removeNegative(cleanedTitle)
        putDetailByTitle(cleanedTitle, detail)
        putDetail(detail.mediaType, detail.id, detail)
        try {
            manualPrefs.edit().putString(cleanedTitle, json.encodeToString(TmdbDetail.serializer(), detail)).apply()
        } catch (_: Exception) {}
    }

    fun getDetailByTitle(cleanedTitle: String): TmdbDetail? {
        val memory = detailCache.get("title_$cleanedTitle")
        if (memory != null) return memory

        // 尝试从持久化手动修正中加载
        val savedJson = manualPrefs.getString(cleanedTitle, null)
        if (!savedJson.isNullOrBlank()) {
            try {
                val detail = json.decodeFromString<TmdbDetail>(savedJson)
                detailCache.put("title_$cleanedTitle", detail)
                detailCache.put("${detail.mediaType}_${detail.id}", detail)
                return detail
            } catch (_: Exception) {}
        }
        return null
    }

    fun markNegative(cleanedTitle: String) {
        prefs.edit().putLong(negKey(cleanedTitle), System.currentTimeMillis()).apply()
    }

    fun removeNegative(cleanedTitle: String) {
        prefs.edit().remove(negKey(cleanedTitle)).apply()
    }

    fun isNegative(cleanedTitle: String): Boolean {
        // 如果有手动修正记录，绝不判定为负缓存
        if (manualPrefs.contains(cleanedTitle)) return false

        val ts = prefs.getLong(negKey(cleanedTitle), 0L)
        if (ts == 0L) return false
        if (System.currentTimeMillis() - ts > NEGATIVE_TTL_MS) {
            prefs.edit().remove(negKey(cleanedTitle)).apply()
            return false
        }
        return true
    }

    fun clearAll() {
        detailCache.evictAll()
        prefs.edit().clear().apply()
        manualPrefs.edit().clear().apply()
    }

    fun clearMemory() {
        detailCache.evictAll()
    }

    private fun negKey(cleanedTitle: String): String = "$KEY_NEG_PREFIX${cleanedTitle.hashCode()}"
}
