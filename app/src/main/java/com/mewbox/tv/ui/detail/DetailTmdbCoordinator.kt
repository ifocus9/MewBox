package com.mewbox.tv.ui.detail

import com.mewbox.tv.api.tmdb.TmdbCache
import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.api.tmdb.TmdbRepository
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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

    companion object {
        /** searchMulti 的预算：弹窗实测约 3s 返回，留足代理/弱网余量，避免静默超时 */
        private const val SEARCH_TIMEOUT_MS = 10_000L
    }

    /**
     * 异步两段式拉取 TMDB 海报、Logo、评分与演职员信息
     * @param onQuick 第一段：轻量搜索命中后立即回调海报与横版背景图
     * @param onLoaded 第二段：完整大 JSON 加载完成，回填评分、简介、Logo 与演职员
     */
    fun loadTmdbInfo(
        scope: CoroutineScope,
        vodName: String,
        onLoaded: (detail: TmdbDetail, imageBase: String) -> Unit,
        onQuick: ((poster: String?, backdrop: String?) -> Unit)? = null,
    ): Job = scope.launch {
        try {
            Timber.i("TMDB 自动匹配启动: $vodName")
            val config = tmdbRepository.getTmdbConfig()
            Timber.i("TMDB 配置读取完成: enable=${config.enable}")
            if (!config.enable || config.apiKey.isBlank()) {
                Timber.d("TMDB 未启用或未配置 Key，跳过: $vodName")
                return@launch
            }

            val cleaned = TmdbClient.cleanTitle(vodName)
            if (cleaned.isBlank()) {
                Timber.d("TMDB 片名清洗后为空，跳过: $vodName")
                return@launch
            }

            val cached = tmdbCache.getDetailByTitle(cleaned)
            if (cached != null) {
                Timber.i("TMDB 缓存命中: $cleaned")
                onLoaded(cached, config.imageBase)
                return@launch
            }

            if (tmdbCache.isNegative(cleaned)) {
                Timber.d("TMDB 负缓存命中，跳过搜索: $cleaned")
                return@launch
            }
            Timber.i("TMDB 开始 searchMulti: $cleaned")

            // 第一段：带超时的 searchMulti。超时属于「本次没拿到结果」而非「TMDB 无收录」，
            // 必须与外部取消区分开：超时要留日志、且绝不写负缓存，下次进详情页还能重试。
            val searchResult = runCatching {
                withTimeout(SEARCH_TIMEOUT_MS) { tmdbClient.searchMulti(cleaned) }
            }.getOrElse { e ->
                when (e) {
                    is TimeoutCancellationException -> {
                        Timber.w("TMDB searchMulti 超时(${SEARCH_TIMEOUT_MS}ms): $cleaned（不打负缓存，下次进入可重试）")
                        return@launch
                    }
                    is CancellationException -> throw e
                    else -> {
                        Timber.w(e, "TMDB searchMulti 失败: $cleaned")
                        return@launch
                    }
                }
            }

            val searchRes = searchResult.getOrNull()
            if (searchRes == null) {
                // 网络或 HTTP 错误，绝不打负缓存
                Timber.w("TMDB searchMulti 返回错误: $cleaned")
                return@launch
            }

            // 严格匹配优先；未命中不代表无收录 —— 译名差异（站源译名 vs TMDB 中文名）会让它返回 null，
            // 此时改用候选列表兜底，只有接口明确「零候选」才写负缓存。
            var best = TmdbClient.pickBestMatch(cleaned, searchRes)
            var isFallback = false
            if (best == null) {
                val candidates = searchRes.results.filter { it.mediaType != "person" }
                if (candidates.isEmpty()) {
                    Timber.i("TMDB 接口返回零候选，写入负缓存: $cleaned")
                    tmdbCache.markNegative(cleaned)
                    return@launch
                }
                best = TmdbClient.pickFallbackMatch(cleaned, searchRes)
                if (best == null) {
                    Timber.i("TMDB 候选均不可用，保留站源海报: $cleaned")
                    return@launch
                }
                isFallback = true
            }
            val chosen = best ?: return@launch
            if (isFallback) {
                Timber.i("TMDB 严格匹配未命中，采用兜底候选: $cleaned -> ${chosen.displayTitle} (id=${chosen.id}, ${chosen.mediaType})")
            }

            // 第一段出图：backdrop 与 poster 尺寸与后续保持统一（w1280 / w780）
            onQuick?.invoke(
                TmdbClient.imageUrl(config.imageBase, chosen.posterPath, "w780").ifEmpty { null },
                TmdbClient.imageUrl(config.imageBase, chosen.backdropPath, "w1280").ifEmpty { null },
            )

            // 第二段：后台补齐评分、演职员、多图等元数据
            val detail = tmdbClient.fetchDetail(chosen.mediaType, chosen.id).getOrNull()
            if (detail != null) {
                tmdbCache.putDetailByTitle(cleaned, detail)
                tmdbCache.putDetail(chosen.mediaType, chosen.id, detail)
                onLoaded(detail, config.imageBase)
            } else {
                Timber.w("TMDB fetchDetail 失败: ${chosen.mediaType}/${chosen.id} ($cleaned)")
            }
        } catch (e: CancellationException) {
            Timber.i("TMDB 协程被取消: $vodName")
            throw e
        } catch (e: Exception) {
            Timber.w(e, "获取 TMDB 增强信息失败: $vodName")
        } finally {
            Timber.i("TMDB 协程结束: $vodName")
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

