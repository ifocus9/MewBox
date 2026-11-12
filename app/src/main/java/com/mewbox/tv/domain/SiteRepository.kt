package com.mewbox.tv.domain

import android.content.Context
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.TrackPreference
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.HistoryDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.db.TrackDao
import com.mewbox.tv.ui.web.WebActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    private val configDao: ConfigDao,
    private val historyDao: HistoryDao,
    private val trackDao: TrackDao,
    private val settingsRepository: SettingsRepository,
    private val vodConfig: VodConfig,
    private val router: SpiderRouter,
    private val webHomeInlineStore: WebHomeInlineStore,
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val PUSH_KEY = VodConfig.PUSH_KEY

        private val FALLBACK_PUSH_SITE = Site(
            key = PUSH_KEY,
            name = "推送",
            type = 3,
            api = "node:/spider/push_agent/3",
            searchable = 0,
            quickSearch = false,
            changeable = false,
        )
    }

    /** 监听当前默认首页站点 */
    fun observeHomeSite(): Flow<Site?> =
        siteDao.observeAll().map { sites ->
            sites.firstOrNull { it.isHome && it.enabled && !it.hide && router.isSupported(it) }
                ?: sites.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }
        }.distinctUntilChanged()

    suspend fun getSite(key: String): Site? = withContext(Dispatchers.IO) {
        if (key == WebHomeInlineStore.KEY) {
            return@withContext webHomeInlineStore.getSite()
        }
        val sites = siteDao.getAll()
        sites.firstOrNull { it.key == key }?.let { return@withContext it }
        if (key == PUSH_KEY) {
            sites.firstOrNull { VodConfig.isPushSite(it) }?.let { return@withContext it }
        }

        // 若当前处于 HTML 网页源且本地站点表尚未拉取或缺少 push_agent，尝试静默恢复底层 Node.js/点播站点
        vodConfig.ensurePushBackendReady()
        val refreshed = siteDao.getAll()
        refreshed.firstOrNull { it.key == key }?.let { return@withContext it }
        if (key == PUSH_KEY) {
            refreshed.firstOrNull { VodConfig.isPushSite(it) }?.let { return@withContext it }
            if (vodConfig.hasPushRuntimeAvailable() && router.isSupported(FALLBACK_PUSH_SITE)) {
                return@withContext FALLBACK_PUSH_SITE
            }
        }
        null
    }

    suspend fun action(site: Site, action: String): Result = withContext(Dispatchers.IO) {
        try {
            val result = router.spider(site).action(action)
            result.web?.let { webOptions ->
                if (webOptions.isValid()) {
                    WebActivity.open(context, webOptions)
                }
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "执行站点 Action 失败: ${site.name}, action=$action")
            Result()
        }
    }

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
        if (site.key == WebHomeInlineStore.KEY) {
            return@withContext webHomeInlineStore.getDetailContent(vodId)
        }
        var lastError: Exception? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                val result = router.spider(site).detailContent(listOf(vodId))
                if (result.list.isNotEmpty() || attempt == maxAttempts) {
                    return@withContext result
                }
                // 若列表为空且为 Node/T4 引擎源，可能正逢切回前台拉起间隙，稍作等待重试
                if (site.type == 3 || site.api.startsWith("node:") || site.type == 4) {
                    delay(500L * attempt)
                } else {
                    return@withContext result
                }
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "获取详情异常 (尝试 $attempt/$maxAttempts): ${site.name}, vodId=$vodId")
                if (attempt < maxAttempts) {
                    delay(500L * attempt)
                }
            }
        }
        if (lastError != null) {
            Timber.e(lastError, "获取详情最终失败: ${site.name}, vodId=$vodId")
        }
        Result()
    }

    /**
     * 解析播放直链
     */
    suspend fun resolvePlay(site: Site, flag: String, playUrl: String): Result = withContext(Dispatchers.IO) {
        if (site.key == WebHomeInlineStore.KEY) {
            return@withContext webHomeInlineStore.resolvePlay(flag, playUrl)
        }
        var lastError: Exception? = null
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            try {
                return@withContext router.spider(site).playerContent(flag = flag, id = playUrl)
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "解析播放地址异常 (尝试 $attempt/$maxAttempts): site=${site.name}, flag=$flag, url=$playUrl")
                if (attempt < maxAttempts) {
                    delay(600L)
                }
            }
        }
        if (lastError != null) {
            Timber.e(lastError, "解析播放地址最终失败: site=${site.name}, flag=$flag, url=$playUrl")
        }
        Result()
    }

    /**
     * 多源并发流式搜索：
     * 1. 筛选出所有 enabled && searchable 且 router.isSupported 的站点
     * 2. 优先调度支持快搜与网盘站点（如 [盘] 标记），确保首屏结果毫秒级呈现（秒出结果）
     * 3. 提升并发许可（HTTP 24 / 共享运行时 16），避免多源排队
     * 4. 单源超时对齐 FongMi 30 秒（TIMEOUT_SEARCH = 30_000L），确保网盘与耗时源完整搜出，不丢结果
     */
    fun searchAll(keyword: String, targetSiteKey: String? = null): Flow<List<Vod>> = channelFlow {
        if (keyword.isBlank()) return@channelFlow
        val sites = siteDao.getAll().filter {
            it.enabled && !it.hide && router.isSupported(it) &&
                if (targetSiteKey.isNullOrEmpty()) it.isSearchable else it.key == targetSiteKey
        }
        if (sites.isEmpty()) return@channelFlow

        // 优先调度支持快搜或标记为秒播的站点，确保首屏结果毫秒级呈现
        val sortedSites = sites.sortedWith(
             compareByDescending<Site> { it.name.contains("盘") } // 盘源（带 [盘]、网盘）拥有绝对第一优先级
                .thenByDescending { it.quickSearch }
                .thenBy { it.priority }
        )

        val httpPermit = Semaphore(24)
        val sharedPermit = Semaphore(8)
        withContext(Dispatchers.IO) {
            sortedSites.map { site ->
                async {
                    val permit = if (router.isRuntimeShared(site)) sharedPermit else httpPermit
                    permit.withPermit {
                        try {
                            val result = withTimeoutOrNull(30_000L) {
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
     * 2. quick = true 进行轻量快搜，按引擎类型分组限流（HTTP 16 / 共享运行时 8）
     * 3. 单源超时提升至 15 秒，流式 emit 检索结果
     */
    fun quickSearch(vodName: String): Flow<Vod> = channelFlow {
        val cleanTarget = vodName.trim()
        if (cleanTarget.isBlank()) return@channelFlow
        val sites = siteDao.getAll().filter { it.enabled && it.changeable && !it.hide && router.isSupported(it) && it.quickSearch && it.isSearchable }
        if (sites.isEmpty()) return@channelFlow

        val httpPermit = Semaphore(16)
        val sharedPermit = Semaphore(8)
        withContext(Dispatchers.IO) {
            sites.map { site ->
                async {
                    val permit = if (router.isRuntimeShared(site)) sharedPermit else httpPermit
                    permit.withPermit {
                        try {
                            val result = withTimeoutOrNull(15_000L) {
                                router.spider(site).searchContent(keyword = cleanTarget, quick = true, pg = "1")
                            }
                            result?.list?.forEach { vod ->
                                send(vod)
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "快搜换源失败: ${site.name}")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    fun observeHistories(configUrl: String? = null): Flow<List<History>> =
        if (configUrl.isNullOrBlank()) {
            historyDao.observeAll()
        } else {
            historyDao.observeByConfig(configUrl)
        }

    suspend fun getHistory(siteKey: String, vodId: String, configUrl: String = ""): History? = withContext(Dispatchers.IO) {
        if (siteKey == WebHomeInlineStore.KEY) {
            webHomeInlineStore.consumeInitialHistory(vodId)?.let { return@withContext it }
        }
        val targetConfigUrl = configUrl.ifBlank {
            settingsRepository.currentConfigUrl.ifBlank {
                settingsRepository.configUrlFlow.first()
            }
        }
        if (targetConfigUrl.isNotBlank()) {
            historyDao.get(History.key(siteKey, vodId, targetConfigUrl))
                ?: historyDao.find(targetConfigUrl, siteKey, vodId)
                ?: historyDao.get(History.key(siteKey, vodId))
        } else {
            historyDao.get(History.key(siteKey, vodId))
        }
    }

    suspend fun saveHistory(history: History) = withContext(Dispatchers.IO) {
        val finalConfigUrl = if (history.configUrl.isNotBlank()) {
            history.configUrl
        } else {
            settingsRepository.currentConfigUrl.ifBlank {
                settingsRepository.configUrlFlow.first()
            }
        }
        val finalConfigName = if (history.configName.isNotBlank()) {
            history.configName
        } else if (finalConfigUrl.isNotBlank()) {
            configDao.find(finalConfigUrl)?.desc.orEmpty()
        } else {
            ""
        }
        val finalKey = if (history.key.isBlank() || history.key == History.key(history.siteKey, history.vodId)) {
            History.key(history.siteKey, history.vodId, finalConfigUrl)
        } else {
            history.key
        }
        val toSave = history.copy(
            key = finalKey,
            configUrl = finalConfigUrl,
            configName = finalConfigName,
        )
        historyDao.upsert(toSave)
    }

    suspend fun deleteHistory(key: String) = withContext(Dispatchers.IO) {
        historyDao.delete(key)
        trackDao.deleteByKey(key)
    }

    suspend fun clearHistories(configUrl: String? = null) = withContext(Dispatchers.IO) {
        if (configUrl.isNullOrBlank()) {
            historyDao.clear()
            trackDao.clear()
        } else {
            historyDao.clearByConfig(configUrl)
        }
    }

    suspend fun getTrackPreferences(historyKey: String): List<TrackPreference> = withContext(Dispatchers.IO) {
        if (historyKey.isBlank()) emptyList() else trackDao.findByKey(historyKey)
    }

    suspend fun saveTrackPreference(preference: TrackPreference) = withContext(Dispatchers.IO) {
        if (preference.key.isNotBlank()) {
            trackDao.upsert(preference)
        }
    }

    suspend fun removeTrackPreference(historyKey: String, type: Int) = withContext(Dispatchers.IO) {
        if (historyKey.isNotBlank()) {
            trackDao.delete(historyKey, type)
        }
    }

    suspend fun copyHistoryAndPreferences(
        fromKey: String,
        toKey: String,
        newSite: Site,
        newVod: Vod,
    ) = withContext(Dispatchers.IO) {
        if (fromKey.isBlank() || toKey.isBlank() || fromKey == toKey) return@withContext
        val existing = historyDao.get(fromKey)
        if (existing != null) {
            val targetExisting = historyDao.get(toKey)
            if (targetExisting == null) {
                val copiedHistory = existing.copy(
                    key = toKey,
                    siteKey = newSite.key,
                    siteName = newSite.name,
                    vodId = newVod.vodId,
                    vodName = newVod.vodName,
                    vodPic = newVod.vodPic.ifBlank { existing.vodPic },
                    updatedAt = System.currentTimeMillis(),
                )
                historyDao.upsert(copiedHistory)
            }
        }
        val existingPrefs = trackDao.findByKey(fromKey)
        for (pref in existingPrefs) {
            trackDao.upsert(pref.copy(key = toKey, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun getConfigDesc(url: String): String = withContext(Dispatchers.IO) {
        if (url.isBlank()) "" else (configDao.find(url)?.desc ?: url)
    }
}
