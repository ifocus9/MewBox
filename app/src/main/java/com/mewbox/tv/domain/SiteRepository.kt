package com.mewbox.tv.domain

import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.HistoryDao
import com.mewbox.tv.db.SiteDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SiteRepository @Inject constructor(
    private val siteDao: SiteDao,
    private val historyDao: HistoryDao,
    private val router: SpiderRouter,
) {
    /** 监听当前默认首页站点 */
    fun observeHomeSite(): Flow<Site?> =
        siteDao.observeAll().map { sites ->
            sites.firstOrNull { it.isHome && it.enabled && !it.hide && router.isSupported(it) }
                ?: sites.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }
        }

    suspend fun getSite(key: String): Site? =
        siteDao.getAll().firstOrNull { it.key == key }

    suspend fun getHomeContent(site: Site): Result = withContext(Dispatchers.IO) {
        try {
            router.spider(site).homeContent()
        } catch (e: Exception) {
            Timber.e(e, "获取首页失败: ${site.name}")
            Result()
        }
    }

    suspend fun getCategoryContent(site: Site, tid: String, pg: Int): Result = withContext(Dispatchers.IO) {
        try {
            router.spider(site).categoryContent(tid = tid, pg = pg.toString())
        } catch (e: Exception) {
            Timber.e(e, "获取分类失败: ${site.name}, tid=$tid, pg=$pg")
            Result()
        }
    }

    suspend fun getDetailContent(site: Site, vodId: String): Result = withContext(Dispatchers.IO) {
        try {
            router.spider(site).detailContent(listOf(vodId))
        } catch (e: Exception) {
            Timber.e(e, "获取详情失败: ${site.name}, vodId=$vodId")
            Result()
        }
    }

    /**
     * 解析播放直链
     */
    suspend fun resolvePlay(site: Site, flag: String, playUrl: String): Result = withContext(Dispatchers.IO) {
        try {
            router.spider(site).playerContent(flag = flag, id = playUrl)
        } catch (e: Exception) {
            Timber.e(e, "解析播放地址失败: site=${site.name}, flag=$flag, url=$playUrl")
            Result()
        }
    }

    /**
     * 多源并发流式搜索：
     * 1. 筛选出所有 enabled && searchable 且 router.isSupported 的站点
     * 2. 使用 Semaphore 限制最大并发数（6）
     * 3. 单源独立超时（8 秒）与异常捕获，搜到一个源的结果立即发送到流中
     */
    fun searchAll(keyword: String): Flow<List<Vod>> = channelFlow {
        if (keyword.isBlank()) return@channelFlow
        val sites = siteDao.getAll().filter { it.enabled && it.searchable && !it.hide && router.isSupported(it) }
        if (sites.isEmpty()) return@channelFlow

        val semaphore = Semaphore(6)
        withContext(Dispatchers.IO) {
            sites.map { site ->
                async {
                    semaphore.withPermit {
                        try {
                            val result = withTimeoutOrNull(8_000L) {
                                router.spider(site).searchContent(keyword = keyword, quick = false, pg = "1")
                            }
                            if (result != null && result.list.isNotEmpty()) {
                                send(result.list)
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "站点搜索失败: ${site.name}")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * 针对指定片名的快速检索（换源 / 豆瓣跨源推荐播放）：
     * 1. 筛选 enabled && changeable && router.isSupported 的源
     * 2. quick = true 进行轻量快搜
     * 3. 严格根据片名核心词匹配并流式 emit
     */
    fun quickSearch(vodName: String): Flow<Vod> = channelFlow {
        val cleanTarget = vodName.trim()
        if (cleanTarget.isBlank()) return@channelFlow
        val sites = siteDao.getAll().filter { it.enabled && it.changeable && !it.hide && router.isSupported(it) }
        if (sites.isEmpty()) return@channelFlow

        val semaphore = Semaphore(6)
        withContext(Dispatchers.IO) {
            sites.map { site ->
                async {
                    semaphore.withPermit {
                        try {
                            val result = withTimeoutOrNull(6_000L) {
                                router.spider(site).searchContent(keyword = cleanTarget, quick = true, pg = "1")
                            }
                            result?.list?.forEach { vod ->
                                if (isNameMatched(vod.vodName, cleanTarget)) {
                                    send(vod)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "快搜换源失败: ${site.name}")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun isNameMatched(name: String, target: String): Boolean {
        val n1 = name.replace(" ", "").lowercase()
        val n2 = target.replace(" ", "").lowercase()
        return n1.contains(n2) || n2.contains(n1)
    }

    fun observeHistories(): Flow<List<History>> = historyDao.observeAll()

    suspend fun getHistory(siteKey: String, vodId: String): History? = withContext(Dispatchers.IO) {
        historyDao.get(History.key(siteKey, vodId))
    }

    suspend fun saveHistory(history: History) = withContext(Dispatchers.IO) {
        historyDao.upsert(history)
    }

    suspend fun deleteHistory(key: String) = withContext(Dispatchers.IO) {
        historyDao.delete(key)
    }

    suspend fun clearHistories() = withContext(Dispatchers.IO) {
        historyDao.clear()
    }
}
