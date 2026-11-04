package com.mewbox.tv.ui.detail

import com.mewbox.tv.api.tmdb.TmdbCache
import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.api.tmdb.TmdbRepository
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 负责详情页 TMDB 影视元数据拉取、智能推荐匹配、弹窗多维搜索与手动修正持久化
 */
class DetailTmdbCoordinator @Inject constructor(
    private val tmdbClient: TmdbClient,
    private val tmdbCache: TmdbCache,
    private val tmdbRepository: TmdbRepository,
) {

    /**
     * 异步拉取 TMDB 海报、Logo、评分与演职员信息
     */
    fun loadTmdbInfo(
        scope: CoroutineScope,
        vodName: String,
        onLoaded: (detail: TmdbDetail, imageBase: String) -> Unit,
    ) {
        scope.launch {
            try {
                val config = tmdbRepository.getTmdbConfig()
                if (!config.enable || config.apiKey.isBlank()) return@launch

                val cleaned = TmdbClient.cleanTitle(vodName)
                if (cleaned.isBlank()) return@launch

                val cached = tmdbCache.getDetailByTitle(cleaned)
                if (cached != null) {
                    onLoaded(cached, config.imageBase)
                    return@launch
                }

                if (tmdbCache.isNegative(cleaned)) return@launch

                val searchRes = tmdbClient.searchMulti(cleaned).getOrNull()
                val best = TmdbClient.pickBestMatch(cleaned, searchRes)
                if (best != null) {
                    val detail = tmdbClient.fetchDetail(best.mediaType, best.id).getOrNull()
                    if (detail != null) {
                        tmdbCache.putDetailByTitle(cleaned, detail)
                        tmdbCache.putDetail(best.mediaType, best.id, detail)
                        onLoaded(detail, config.imageBase)
                    }
                } else {
                    tmdbCache.markNegative(cleaned)
                }
            } catch (e: Exception) {
                Timber.w(e, "获取 TMDB 增强信息失败: $vodName")
            }
        }
    }

    /**
     * 打开 TMDB 修正匹配弹窗并初始化状态
     */
    fun openTmdbMatchDialog(
        scope: CoroutineScope,
        vodName: String,
        currentDisplayTitle: String?,
        onReady: (initialKeyword: String, isConfigured: Boolean, imageBase: String) -> Unit,
        onAutoSearch: (keyword: String) -> Unit,
    ) {
        val initialKeyword = currentDisplayTitle?.takeIf { it.isNotBlank() }
            ?: TmdbClient.cleanTitle(vodName).ifBlank { vodName }

        scope.launch {
            val config = tmdbRepository.getTmdbConfig()
            val isConfigured = config.enable && config.apiKey.isNotBlank()
            onReady(initialKeyword, isConfigured, config.imageBase)
            if (isConfigured && initialKeyword.isNotBlank()) {
                onAutoSearch(initialKeyword)
            }
        }
    }

    /**
     * 执行 TMDB 搜索（支持片名关键字与纯数字 TMDB ID 搜索）
     */
    fun searchTmdb(
        scope: CoroutineScope,
        query: String,
        onStart: (keyword: String) -> Unit,
        onSuccess: (results: List<TmdbMedia>, error: String?) -> Unit,
        onError: (errorMsg: String, isNotConfigured: Boolean) -> Unit,
    ) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            onError("请输入片名或 TMDB ID", false)
            return
        }

        scope.launch {
            val config = tmdbRepository.getTmdbConfig()
            if (!config.enable || config.apiKey.isBlank()) {
                onError("TMDB 未开启或未配置 API Key，请在设置中配置", true)
                return@launch
            }

            onStart(trimmed)

            try {
                val results = mutableListOf<TmdbMedia>()
                val numericId = trimmed.toIntOrNull()
                if (numericId != null && numericId > 0) {
                    val movieRes = tmdbClient.fetchDetail("movie", numericId).getOrNull()
                    if (movieRes != null && movieRes.id > 0) {
                        results.add(
                            TmdbMedia(
                                id = movieRes.id,
                                rawMediaType = "movie",
                                title = movieRes.title,
                                originalTitle = movieRes.originalTitle,
                                overview = movieRes.overview,
                                posterPath = movieRes.posterPath,
                                backdropPath = movieRes.backdropPath,
                                releaseDate = movieRes.releaseDate,
                                voteAverage = movieRes.voteAverage,
                                voteCount = movieRes.voteCount,
                            )
                        )
                    }
                    val tvRes = tmdbClient.fetchDetail("tv", numericId).getOrNull()
                    if (tvRes != null && tvRes.id > 0) {
                        results.add(
                            TmdbMedia(
                                id = tvRes.id,
                                rawMediaType = "tv",
                                name = tvRes.name,
                                originalName = tvRes.originalName,
                                overview = tvRes.overview,
                                posterPath = tvRes.posterPath,
                                backdropPath = tvRes.backdropPath,
                                firstAirDate = tvRes.firstAirDate,
                                voteAverage = tvRes.voteAverage,
                                voteCount = tvRes.voteCount,
                            )
                        )
                    }
                }

                val searchRes = tmdbClient.searchMulti(trimmed).getOrNull()
                if (searchRes != null) {
                    for (item in searchRes.results) {
                        if (item.mediaType == "person") continue
                        if (results.none { it.id == item.id && it.mediaType == item.mediaType }) {
                            results.add(item)
                        }
                    }
                }

                val errorMsg = if (results.isEmpty()) "未搜索到匹配的影片，请尝试修改关键词" else null
                onSuccess(results, errorMsg)
            } catch (e: Exception) {
                Timber.w(e, "搜索 TMDB 失败: $trimmed")
                onError("搜索失败: ${e.message ?: "网络异常"}", false)
            }
        }
    }

    /**
     * 手动修正匹配条目并持久化到本地缓存
     */
    fun applyTmdbMatch(
        scope: CoroutineScope,
        media: TmdbMedia,
        vodName: String,
        onStart: () -> Unit,
        onSuccess: (detail: TmdbDetail, imageBase: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
    ) {
        scope.launch {
            onStart()
            try {
                val config = tmdbRepository.getTmdbConfig()
                val detail = tmdbClient.fetchDetail(media.mediaType, media.id).getOrNull()
                if (detail == null) {
                    onError("获取影视详情失败，请重试")
                    return@launch
                }

                val rawName = vodName.ifBlank { detail.displayTitle }
                val cleaned = TmdbClient.cleanTitle(rawName).ifBlank { rawName.trim() }
                if (cleaned.isNotBlank()) {
                    tmdbCache.putManualOverride(cleaned, detail)
                }
                tmdbCache.putDetail(media.mediaType, media.id, detail)

                onSuccess(detail, config.imageBase)
            } catch (e: Exception) {
                Timber.e(e, "手动修正 TMDB 匹配失败: mediaId=${media.id}")
                onError("修正匹配失败: ${e.message}")
            }
        }
    }
}

