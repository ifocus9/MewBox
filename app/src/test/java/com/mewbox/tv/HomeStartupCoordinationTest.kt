package com.mewbox.tv

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.api.net.HostMapping
import com.mewbox.tv.api.net.LocalMessageBridge
import com.mewbox.tv.api.net.T4Service
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.TrackPreference
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.bean.VodClass
import com.mewbox.tv.bean.VodFilter
import com.mewbox.tv.bean.VodFilterValue
import com.mewbox.tv.catvod.Spider
import com.mewbox.tv.catvod.SpiderLoader
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.HistoryDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.db.TrackDao
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.domain.WebHomeInlineStore
import com.mewbox.tv.engine.nodejs.NodeRuntime
import com.mewbox.tv.ui.home.HomeViewModel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStartupCoordinationTest {

    private lateinit var mainExecutor: java.util.concurrent.ExecutorService
    private lateinit var server: MockWebServer
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        server = MockWebServer().apply { start() }
        store = ViewModelStore()
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
        runCatching { server.shutdown() }
    }

    @Test
    fun inFlightConfigImport_gatesHomeLoadUntilSitesSettled_andTriggersOnceWithFilters() = runBlocking {
        val configUrl = server.url("/config.json").toString()
        val requestStarted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/config.json") {
                    requestStarted.countDown()
                    allowResponse.await(5, TimeUnit.SECONDS)
                    return MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "sites": [
                                { "key": "douban", "name": "豆瓣[荐]", "type": 4, "api": "http://127.0.0.1/douban" }
                              ]
                            }
                            """.trimIndent()
                        )
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val siteDao = FakeSiteDao(
            listOf(
                Site(key = "site_old", name = "旧站点", type = 4, api = "http://127.0.0.1/old", isHome = true)
            )
        )
        val configDao = FakeConfigDao(
            listOf(Config(url = configUrl, name = "测试订阅", home = "douban", id = ""))
        )
        val settings = SettingsRepository(sharedTempContext, HostMapping())

        val oldCalls = AtomicInteger(0)
        val doubanCalls = AtomicInteger(0)
        val router = createFakeRouter(
            mapOf(
                "site_old" to FakeHomeSpider(oldCalls, withFilters = false),
                "douban" to FakeHomeSpider(doubanCalls, withFilters = true),
            )
        )
        val nodeRuntime = NodeRuntime(sharedTempContext, LocalMessageBridge(sharedTempContext), settings)
        val vodConfig = VodConfig(
            context = sharedTempContext,
            service = T4Service(OkHttpClient()),
            siteDao = siteDao,
            configDao = configDao,
            settings = settings,
            router = router,
            nodeRuntime = nodeRuntime,
        )
        val repo = createRepo(siteDao, configDao, settings, vodConfig, router)

        // 在 IO 调度器上启动配置导入任务并阻塞在网络层，模拟冷启动配置尚在同步中
        val importJob = async(Dispatchers.IO) { vodConfig.import(configUrl, name = "测试订阅") }
        assertTrue("配置请求未发出", requestStarted.await(5, TimeUnit.SECONDS))
        assertTrue("配置导入期间 isConfiguring 必须为 true", vodConfig.isConfiguring.value)

        val vm = HomeViewModel(repo, siteDao, configDao, vodConfig, settings, router, nodeRuntime)
        store.put("test_vm", vm)

        // 模拟 NodeRuntime 先一步清空 loading（真实冷启动竞态触发点）：此时仍在 configuring 中，首页不得抢跑发请求
        nodeRuntime.clearLoadingForce()
        delay(80)
        assertEquals("配置未完成前不得用旧缓存站点触发首页加载", 0, oldCalls.get())
        assertEquals("配置未完成前不得提前触发豆瓣首页加载", 0, doubanCalls.get())

        // 放行配置响应并等待导入与首页加载收敛
        allowResponse.countDown()
        assertEquals(1, importJob.await())
        assertFalse(vodConfig.isConfiguring.value)

        withTimeout(5_000) {
            vm.uiState.first { state ->
                !state.isLoading && state.currentSite?.key == "douban" && state.currentFilters.isNotEmpty()
            }
        }
        delay(80)

        assertEquals("旧站点不应收到任何首页请求", 0, oldCalls.get())
        assertEquals("新首页豆瓣应只触发一次首页加载", 1, doubanCalls.get())
        assertEquals(1, vm.uiState.value.currentFilters.size)
        assertEquals("hot_gaia", vm.uiState.value.selectedClassId)
    }

    @Test
    fun jsonConfigSwitch_waitsForSitesToSettle_andTriggersSingleHomeLoadWithFilters() = runBlocking {
        val siteDao = FakeSiteDao(
            listOf(Site(key = "site_old", name = "旧站点", type = 4, api = "http://127.0.0.1/old", isHome = true))
        )
        val configDao = FakeConfigDao(
            listOf(Config(url = "http://127.0.0.1:1/unused.json", name = "本地", home = "douban", id = ""))
        )
        val settings = SettingsRepository(sharedTempContext, HostMapping())
        val oldCalls = AtomicInteger(0)
        val doubanCalls = AtomicInteger(0)
        val router = createFakeRouter(
            mapOf(
                "site_old" to FakeHomeSpider(oldCalls, withFilters = false),
                "douban" to FakeHomeSpider(doubanCalls, withFilters = true),
            )
        )
        val nodeRuntime = NodeRuntime(sharedTempContext, LocalMessageBridge(sharedTempContext), settings)
        val vodConfig = VodConfig(
            context = sharedTempContext,
            service = T4Service(OkHttpClient()),
            siteDao = siteDao,
            configDao = configDao,
            settings = settings,
            router = router,
            nodeRuntime = nodeRuntime,
        )
        val repo = createRepo(siteDao, configDao, settings, vodConfig, router)

        val vm = HomeViewModel(repo, siteDao, configDao, vodConfig, settings, router, nodeRuntime)
        store.put("json_vm", vm)
        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.currentSite?.key == "site_old" }
        }
        assertEquals(1, oldCalls.get())

        val count = vodConfig.import(
            """
            {
              "sites": [
                { "key": "douban", "name": "豆瓣[荐]", "type": 4, "api": "http://127.0.0.1/douban" }
              ]
            }
            """.trimIndent()
        )
        assertEquals(1, count)

        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.currentSite?.key == "douban" && it.currentFilters.isNotEmpty() }
        }
        delay(80)

        assertEquals("切换站点过程中旧站点不应再次加载", 1, oldCalls.get())
        assertEquals("切换后豆瓣应只加载一次", 1, doubanCalls.get())
        assertTrue(vm.uiState.value.currentFilters.isNotEmpty())
    }

    @Test
    fun reloadSubscription_onNetworkFailure_triggersSingleFallbackHomeLoad() = runBlocking {
        val failUrl = server.url("/fail-config.json").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500)
        }

        val siteDao = FakeSiteDao(
            listOf(Site(key = "douban", name = "豆瓣[荐]", type = 4, api = "http://127.0.0.1/douban", isHome = true))
        )
        val configDao = FakeConfigDao(
            listOf(Config(url = failUrl, name = "失败源", home = "douban", id = ""))
        )
        val settings = SettingsRepository(sharedTempContext, HostMapping())
        val doubanCalls = AtomicInteger(0)
        val router = createFakeRouter(mapOf("douban" to FakeHomeSpider(doubanCalls, withFilters = true)))
        val nodeRuntime = NodeRuntime(sharedTempContext, LocalMessageBridge(sharedTempContext), settings)
        val vodConfig = VodConfig(
            context = sharedTempContext,
            service = T4Service(OkHttpClient()),
            siteDao = siteDao,
            configDao = configDao,
            settings = settings,
            router = router,
            nodeRuntime = nodeRuntime,
        )
        val repo = createRepo(siteDao, configDao, settings, vodConfig, router)

        val vm = HomeViewModel(repo, siteDao, configDao, vodConfig, settings, router, nodeRuntime)
        store.put("fail_vm", vm)
        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.currentSite?.key == "douban" && it.currentFilters.isNotEmpty() }
        }
        assertEquals(1, doubanCalls.get())

        vm.reloadSubscription()
        withTimeout(5_000) {
            vm.uiState.first {
                !it.isRefreshingSubscription &&
                    !it.isLoading &&
                    it.message?.startsWith("刷新订阅失败") == true &&
                    doubanCalls.get() >= 2
            }
        }
        delay(80)

        assertEquals("订阅刷新失败回退时只应触发一次首页重建加载，不能双重触发", 2, doubanCalls.get())
        assertFalse(vodConfig.isConfiguring.value)
        assertTrue(vm.uiState.value.currentFilters.isNotEmpty())
    }

    @Test
    fun silentWarmUp_doesNotSetOrClobberLoadingState() = runBlocking {
        val fgStarted = CountDownLatch(1)
        val allowFgFinish = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/fg/index.js.md5") {
                    fgStarted.countDown()
                    allowFgFinish.await(5, TimeUnit.SECONDS)
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val settings = SettingsRepository(sharedTempContext, HostMapping())
        val nodeRuntime = NodeRuntime(sharedTempContext, LocalMessageBridge(sharedTempContext), settings)
        val fgUrl = server.url("/fg/index.js.md5").toString()
        val bgUrl = server.url("/bg/index.js.md5").toString()

        assertNull(nodeRuntime.loading.value)

        // 前台加载先占位 loading 并挂起在探针请求上
        val fgJob = async(Dispatchers.IO) {
            runCatching {
                nodeRuntime.ensureStarted(source = fgUrl, notify = false, showLoading = true)
            }
        }
        assertTrue(fgStarted.await(5, TimeUnit.SECONDS))
        assertEquals("Node.js 下载中…", nodeRuntime.loading.value)

        // 并发静默预热（showLoading=false）进入 ensureStarted，绝不能递增 sequence 抢走前台任务的 loading 清理令牌
        val bgJob = async(Dispatchers.IO) {
            runCatching {
                nodeRuntime.ensureStarted(source = bgUrl, notify = false, showLoading = false)
            }
        }
        delay(80)
        assertEquals("showLoading=false 不得改动前台 loading 文案", "Node.js 下载中…", nodeRuntime.loading.value)

        allowFgFinish.countDown()
        fgJob.await()
        assertNull("并发静默预热后，前台 loading 收尾必须正常清空为 null", nodeRuntime.loading.value)

        bgJob.await()
        assertNull(nodeRuntime.loading.value)
    }

    private fun createRepo(
        siteDao: SiteDao,
        configDao: ConfigDao,
        settings: SettingsRepository,
        vodConfig: VodConfig,
        router: SpiderRouter,
    ): SiteRepository = SiteRepository(
        siteDao = siteDao,
        configDao = configDao,
        historyDao = FakeHistoryDao(),
        trackDao = FakeTrackDao(),
        settingsRepository = settings,
        vodConfig = vodConfig,
        router = router,
        webHomeInlineStore = WebHomeInlineStore(),
        context = sharedTempContext,
    )

    private fun createFakeRouter(spiders: Map<String, Spider>): SpiderRouter =
        SpiderRouter(
            setOf(
                object : SpiderLoader {
                    override fun supports(site: Site): Boolean = spiders.containsKey(site.key)
                    override fun getSpider(site: Site): Spider =
                        spiders.getValue(site.key)
                }
            )
        )

    private class FakeHomeSpider(
        private val homeCallCount: AtomicInteger,
        private val withFilters: Boolean,
    ) : Spider {
        override suspend fun homeContent(filter: Boolean): Result {
            homeCallCount.incrementAndGet()
            val classes = listOf(
                VodClass(typeId = "hot_gaia", typeName = "热门"),
                VodClass(typeId = "movie", typeName = "电影"),
            )
            val filters = if (withFilters) {
                mapOf(
                    "hot_gaia" to listOf(
                        VodFilter(
                            key = "sort",
                            name = "排序",
                            init = "T",
                            values = listOf(VodFilterValue(n = "综合", v = "T")),
                        )
                    )
                )
            } else {
                emptyMap()
            }
            return Result(
                classes = classes,
                filters = filters,
                list = listOf(Vod(vodId = "vod_1", vodName = "测试影片")),
            )
        }

        override suspend fun categoryContent(
            tid: String,
            pg: String,
            filter: Boolean,
            extend: Map<String, String>,
        ): Result = Result()

        override suspend fun detailContent(ids: List<String>): Result = Result()
        override suspend fun searchContent(keyword: String, quick: Boolean, pg: String): Result = Result()
        override suspend fun playerContent(flag: String, id: String, vipFlags: List<String>): Result = Result()
    }

    private class FakeSiteDao(initial: List<Site>) : SiteDao {
        private val state = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<Site>> = state
        override suspend fun getAll(): List<Site> = state.value
        override suspend fun getHome(): Site? = state.value.firstOrNull { it.isHome }
        override suspend fun upsert(sites: List<Site>) {
            val map = state.value.associateBy { it.key }.toMutableMap()
            sites.forEach { map[it.key] = it }
            state.value = map.values.toList()
        }
        override suspend fun update(site: Site) {
            state.value = state.value.map { if (it.key == site.key) site else it }
        }
        override suspend fun deleteNotIn(keys: List<String>) {
            val keep = keys.toSet()
            state.value = state.value.filter { it.key in keep }
        }
        override suspend fun clear() {
            state.value = emptyList()
        }
        override suspend fun setHome(key: String) {
            state.value = state.value.map { it.copy(isHome = it.key == key) }
        }
        override suspend fun replaceAll(sites: List<Site>) {
            state.value = sites
        }
    }

    private class FakeConfigDao(initial: List<Config> = emptyList()) : ConfigDao {
        private val state = MutableStateFlow(initial)
        override suspend fun insert(config: Config) {
            val list = state.value.filterNot { it.url == config.url } + config
            state.value = list.sortedByDescending { it.updatedAt }
        }
        override suspend fun find(url: String): Config? = state.value.firstOrNull { it.url == url }
        override fun observeByUrl(url: String): Flow<Config?> = state.map { list -> list.firstOrNull { it.url == url } }
        override suspend fun findById(id: String): Config? = state.value.firstOrNull { it.id == id }
        override suspend fun updateName(url: String, name: String) {
            state.value = state.value.map { if (it.url == url) it.copy(name = name) else it }
        }
        override suspend fun updateHome(url: String, home: String) {
            state.value = state.value.map { if (it.url == url) it.copy(home = home) else it }
        }
        override fun observeAll(): Flow<List<Config>> = state
        override suspend fun getAll(): List<Config> = state.value
        override suspend fun latest(): Config? = state.value.maxByOrNull { it.updatedAt }
        override suspend fun delete(url: String) {
            state.value = state.value.filterNot { it.url == url }
        }
    }

    private class FakeHistoryDao : HistoryDao {
        private val state = MutableStateFlow<List<History>>(emptyList())
        override fun observeAll(): Flow<List<History>> = state
        override fun observeByConfig(configId: String): Flow<List<History>> = state
        override suspend fun get(key: String): History? = null
        override suspend fun find(configId: String, siteKey: String, vodId: String): History? = null
        override suspend fun upsert(history: History) {}
        override suspend fun delete(key: String) {}
        override suspend fun clearByConfig(configId: String) {}
        override suspend fun clearTrackPreferencesByConfig(configId: String) {}
        override suspend fun clear() {}
    }

    private class FakeTrackDao : TrackDao {
        override suspend fun findByKey(key: String): List<TrackPreference> = emptyList()
        override suspend fun find(key: String, type: Int): TrackPreference? = null
        override suspend fun upsert(preference: TrackPreference) {}
        override suspend fun delete(key: String, type: Int) {}
        override suspend fun deleteByKey(key: String) {}
        override suspend fun clear() {}
    }

    companion object {
        private val sharedTempDir: File by lazy {
            Files.createTempDirectory("mewbox-home-coord-test").toFile().apply { deleteOnExit() }
        }

        private val sharedTempContext: Context by lazy {
            object : ContextWrapper(null) {
                override fun getFilesDir(): File = sharedTempDir
                override fun getCacheDir(): File = File(sharedTempDir, "cache").apply { mkdirs() }
                override fun getApplicationContext(): Context = this
            }
        }
    }
}
