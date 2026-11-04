package com.mewbox.tv.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class HotRankings(
    val tvList: List<String> = emptyList(),
    val movieList: List<String> = emptyList(),
    val varietyList: List<String> = emptyList(),
)

/**
 * 影视热搜榜单数据仓储
 */
@Singleton
class HotRankingRepository @Inject constructor(
    @param:Named("api") private val okHttpClient: OkHttpClient,
) {
    suspend fun getHotRankings(): HotRankings = coroutineScope {
        val tvAsync = async { fetch360Rank(3) }
        val movieAsync = async { fetch360Rank(2) }
        val varietyAsync = async { fetch360Rank(4) }

        HotRankings(
            tvList = tvAsync.await(),
            movieList = movieAsync.await(),
            varietyList = varietyAsync.await(),
        )
    }

    private suspend fun fetch360Rank(cat: Int): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.web.360kan.com/v1/rank?cat=$cat")
                .header("Referer", "https://www.360kan.com/rank/general")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@withContext emptyList()
            val json = JSONObject(body)
            val dataArray = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until minOf(dataArray.length(), 10)) {
                val item = dataArray.optJSONObject(i)
                val title = item?.optString("title")?.ifBlank { item.optString("name") }.orEmpty()
                if (title.isNotBlank()) list.add(title)
            }
            list
        } catch (e: Exception) {
            Timber.w(e, "获取360热搜榜失败: cat=$cat")
            emptyList()
        }
    }
}
