package com.mewbox.tv.ui.detail

import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 负责跨源检索、豆瓣推荐条目快速全网寻源起播，以及后台换源列表并发抓取
 */
class DetailSourceCoordinator @Inject constructor(
    private val repository: SiteRepository,
) {
    private var autoSearchJob: Job? = null
    private var changeSourceJob: Job? = null

    /**
     * 豆瓣纯推荐源自动跨源检索首个可用播放源
     */
    fun startAutoCrossSourceResolve(
        scope: CoroutineScope,
        vodName: String,
        onStart: (message: String) -> Unit,
        onResolved: (site: Site, vod: Vod, flag: Flag, episode: Episode) -> Unit,
        onFailed: (errorMessage: String) -> Unit,
    ) {
        autoSearchJob?.cancel()
        autoSearchJob = scope.launch {
            onStart("正在全网快速检索《$vodName》可用片源...")

            var resolvedVod: Vod? = null
            repository.quickSearch(vodName).collect { foundVod ->
                if (resolvedVod == null) {
                    val targetSite = repository.getSite(foundVod.siteKey)
                    if (targetSite != null) {
                        try {
                            val res = repository.getDetailContent(targetSite, foundVod.vodId)
                            val validVod = res.list.firstOrNull()
                            if (validVod != null && validVod.playFlags.isNotEmpty() && validVod.playFlags.any { it.episodes.isNotEmpty() }) {
                                resolvedVod = validVod
                                val flag = validVod.playFlags.first()
                                val ep = flag.episodes.first()
                                onResolved(targetSite, validVod, flag, ep)
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "校验聚合源失败: ${foundVod.siteKey}")
                        }
                    }
                }
            }

            if (resolvedVod == null) {
                onFailed("全网未检索到《$vodName》的有效播放线路")
            }
        }
    }

    /**
     * 后台抓取其它站同名资源，供换源列表实时消费
     */
    fun triggerSearchChangeSources(
        scope: CoroutineScope,
        vodName: String,
        currentSiteKey: String,
        onStart: () -> Unit,
        onResultsUpdated: (List<Vod>) -> Unit,
        onFinished: (resultCount: Int) -> Unit,
    ) {
        if (vodName.isBlank()) return
        changeSourceJob?.cancel()
        changeSourceJob = scope.launch {
            onStart()
            val resultList = mutableListOf<Vod>()
            repository.quickSearch(vodName).collect { foundVod ->
                if (foundVod.siteKey != currentSiteKey && resultList.none { it.siteKey == foundVod.siteKey }) {
                    resultList.add(foundVod)
                    onResultsUpdated(resultList.toList())
                }
            }
            onFinished(resultList.size)
        }
    }

    fun cancelAll() {
        autoSearchJob?.cancel()
        autoSearchJob = null
        changeSourceJob?.cancel()
        changeSourceJob = null
    }
}

