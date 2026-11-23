package com.mewbox.tv

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.mewbox.tv.api.config.LocalSourceImporter
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.SubscriptionCoordinator
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.api.net.HostMapping
import com.mewbox.tv.api.net.LocalMessageBridge
import com.mewbox.tv.api.net.T4Service
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.TrackPreference
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionSwitchCoordinationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var mainExecutor: ExecutorService
    private lateinit var server: MockWebServer
    private lateinit var store: ViewModelStore
    private lateinit var mockFilesDir: File
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        server = MockWebServer().apply { start() }
        store = ViewModelStore()
        mockFilesDir = tmp.newFolder("files")
        testContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = mockFilesDir
            override fun getCacheDir(): File = File(mockFilesDir, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
        runCatching { server.shutdown() }
    }

    @Test
    fun switchExistingHtmlConfig_observeActiveConfigEmitsOnceWithoutTransientNull() = runBlocking {
        val vodUrl = "https://vod.example.com/config.json"
        val htmlUrl = "https://web.example.com/index.html"
        val siteDao = FakeSiteDao(
            listOf(Site(key = "s1", name = "站点1", type = 4, api = "http://127.0.0.1/s1", isHome = true)),
        )
        val configDao = FakeConfigDao(
            listOf(
                Config(url = vodUrl, name = "点播源", home = "s1", type = Config.TYPE_VOD, updatedAt = 200L),
                Config(url = htmlUrl, name = "在线网页", type = Config.TYPE_HTML, updatedAt = 100L),
            ),
        )
        val settings = SettingsRepository(InMemoryPreferencesDataStore(), HostMapping())
        settings.setActiveConfig(vodUrl, "id_vod")
        val vodConfig = createVodConfig(siteDao, configDao, settings)

        val emissions = mutableListOf<Config?>()
        val collectorJob = launch {
            vodConfig.observeActiveConfig().collect { emissions.add(it) }
        }
        withTimeout(2_000) {
            while (emissions.isEmpty()) delay(10)
        }
        emissions.clear()

        assertEquals(0, vodConfig.import(htmlUrl))
        delay(80)
        collectorJob.cancel()

        assertEquals(1, emissions.size)
        assertEquals(htmlUrl, emissions.single()?.url)
        assertTrue(emissions.single()?.isHtml == true)
    }

    @Test
    fun homeViewModelSwitchSubscription_emitsToGlobalChannelWithoutLeavingStaleUiMessage() = runBlocking {
        val initialVodUrl = server.url("/initial.json").toString()
        val targetVodUrl = server.url("/next.json").toString()
        val targetHtmlUrl = server.url("/web.html").toString()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/next.json" -> MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"sites":[{"key":"next_site","name":"下一站","type":4,"api":"http://127.0.0.1/next"}]}""")
                "/web.html" -> MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<!DOCTYPE html><html><head><title>网页</title></head><body></body></html>")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val siteDao = FakeSiteDao(
            listOf(Site(key = "init_site", name = "初始站", type = 4, api = "http://127.0.0.1/init", isHome = true)),
        )
        val configDao = FakeConfigDao(
            listOf(Config(url = initialVodUrl, name = "初始源", home = "init_site", type = Config.TYPE_VOD)),
        )
        val settings = SettingsRepository(InMemoryPreferencesDataStore(), HostMapping())
        settings.setActiveConfig(initialVodUrl, "init_id")
        val router = createFakeRouter(setOf("init_site", "next_site"))
        val nodeRuntime = NodeRuntime(testContext, LocalMessageBridge(testContext), settings)
        val vodConfig = VodConfig(
            context = testContext,
            service = T4Service(OkHttpClient()),
            siteDao = siteDao,
            configDao = configDao,
            settings = settings,
            router = router,
            nodeRuntime = nodeRuntime,
        )
        val repo = SiteRepository(
            siteDao = siteDao,
            configDao = configDao,
            historyDao = FakeHistoryDao(),
            trackDao = FakeTrackDao(),
            settingsRepository = settings,
            vodConfig = vodConfig,
            router = router,
            webHomeInlineStore = WebHomeInlineStore(),
            context = testContext,
        )
        val vm = HomeViewModel(repo, siteDao, configDao, vodConfig, settings, router, nodeRuntime)
        store.put("home_switch_vm", vm)

        val globalMessages = mutableListOf<String>()
        val msgJob = launch {
            vodConfig.subscriptionMessages.collect { globalMessages.add(it) }
        }

        // 先切到 HTML 源
        vm.switchSubscription(targetHtmlUrl)
        withTimeout(3_000) {
            while (globalMessages.isEmpty()) delay(10)
        }
        assertEquals("已切换至网页订阅源", globalMessages.last())
        assertNull("切往网页源后 HomeUiState.message 不得残留提示", vm.uiState.value.message)

        // 再切回 VOD 源
        vm.switchSubscription(targetVodUrl)
        withTimeout(3_000) {
            while (globalMessages.size < 2) delay(10)
        }
        assertEquals("订阅切换成功（共 1 个站点）", globalMessages.last())
        assertNull("切换 VOD 源后 HomeUiState.message 不得残留提示", vm.uiState.value.message)
        msgJob.cancel()
    }

    @Test
    fun coordinatorSwitchConfig_formatsDistinctSwitchMessagesForHtmlAndVod() = runBlocking {
        val htmlUrl = server.url("/music.html").toString()
        val vodUrl = server.url("/vod.json").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/music.html" -> MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<!DOCTYPE html><html><head><title>音乐</title></head><body></body></html>")
                "/vod.json" -> MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"sites":[{"key":"s1","name":"S1","type":4,"api":"http://127.0.0.1/s1"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val siteDao = FakeSiteDao()
        val configDao = FakeConfigDao()
        val historyDao = FakeHistoryDao()
        val settings = SettingsRepository(InMemoryPreferencesDataStore(), HostMapping())
        val vodConfig = createVodConfig(siteDao, configDao, settings)
        val importer = LocalSourceImporter(testContext, vodConfig, configDao)
        val coordinator = SubscriptionCoordinator(testContext, vodConfig, importer, configDao, historyDao, settings)

        val htmlRes = coordinator.switchConfig(htmlUrl) as SubscriptionCoordinator.ActionResult.Imported
        assertTrue(htmlRes.isHtml)
        assertEquals("已切换至网页订阅源", htmlRes.message)

        val vodRes = coordinator.switchConfig(vodUrl) as SubscriptionCoordinator.ActionResult.Imported
        assertEquals(false, vodRes.isHtml)
        assertEquals("订阅切换成功（共 1 个站点）", vodRes.message)
    }

    @Test
    fun ensureActiveConfig_afterImportOrSwitch_isNoOpAndDoesNotRefetch() = runBlocking {
        val requests = AtomicInteger(0)
        val htmlUrl = server.url("/boot.html").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<!DOCTYPE html><html><head><title>网页</title></head><body></body></html>")
            }
        }

        val siteDao = FakeSiteDao()
        val configDao = FakeConfigDao()
        val settings = SettingsRepository(InMemoryPreferencesDataStore(), HostMapping())
        val vodConfig = createVodConfig(siteDao, configDao, settings)

        assertEquals(0, vodConfig.import(htmlUrl))
        assertEquals(1, requests.get())

        // 已有切换/导入记录后，冷启动守护 ensureActiveConfig 不应重复执行
        assertNull(vodConfig.ensureActiveConfig())
        assertEquals(1, requests.get())
    }

    private fun createVodConfig(
        siteDao: SiteDao,
        configDao: ConfigDao,
        settings: SettingsRepository,
    ): VodConfig {
        val router = createFakeRouter(setOf("s1"))
        val nodeRuntime = NodeRuntime(testContext, LocalMessageBridge(testContext), settings)
        return VodConfig(
            context = testContext,
            service = T4Service(OkHttpClient()),
            siteDao = siteDao,
            configDao = configDao,
            settings = settings,
            router = router,
            nodeRuntime = nodeRuntime,
        )
    }

    private fun createFakeRouter(supportedKeys: Set<String>): SpiderRouter =
        SpiderRouter(
            setOf(
                object : SpiderLoader {
                    override fun supports(site: Site): Boolean = site.key in supportedKeys
                    override fun getSpider(site: Site): Spider = object : Spider {
                        override suspend fun homeContent(filter: Boolean): Result = Result()
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
                },
            ),
        )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val updated = transform(state.value)
                state.value = updated
                updated
            }
    }

    private class FakeSiteDao(initial: List<Site> = emptyList()) : SiteDao {
        private val state = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<Site>> = state
        override suspend fun getAll(): List<Site> = state.value
        override suspend fun getHome(): Site? = state.value.firstOrNull { it.isHome }
        override suspend fun upsert(sites: List<Site>) {
            state.value = sites
        }
        override suspend fun update(site: Site) {
            state.value = state.value.map { if (it.key == site.key) site else it }
        }
        override suspend fun deleteNotIn(keys: List<String>) {}
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
            state.value = (state.value.filterNot { it.url == config.url } + config).sortedByDescending { it.updatedAt }
        }
        override suspend fun find(url: String): Config? = state.value.firstOrNull { it.url == url }
        override fun observeByUrl(url: String): Flow<Config?> = state.map { l -> l.firstOrNull { it.url == url } }
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
}
