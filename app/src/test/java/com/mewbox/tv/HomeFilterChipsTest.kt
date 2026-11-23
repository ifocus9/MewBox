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
import com.mewbox.tv.ui.home.ActiveFilterChip
import com.mewbox.tv.ui.home.HomeUiState
import com.mewbox.tv.ui.home.HomeViewModel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFilterChipsTest {

    private lateinit var mainExecutor: ExecutorService
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        store = ViewModelStore()
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    @Test
    fun activeFilterChips_filtersDefaultInitValues_andMapsDisplayLabels() {
        val filters = listOf(
            VodFilter(
                key = "sort",
                name = "排序",
                init = "T",
                values = listOf(
                    VodFilterValue(n = "综合", v = "T"),
                    VodFilterValue(n = "最新", v = "R"),
                ),
            ),
            VodFilter(
                key = "type",
                name = "类型",
                init = null,
                values = listOf(
                    VodFilterValue(n = "动作", v = "action"),
                    VodFilterValue(n = "科幻", v = "scifi"),
                ),
            ),
            VodFilter(
                key = "year",
                name = "年份",
                init = "",
                values = listOf(VodFilterValue(n = "2026", v = "2026")),
            ),
            VodFilter(
                key = "tag",
                name = "标签",
                init = null,
                values = listOf(VodFilterValue(n = "", v = "4k")),
            ),
        )

        val uiState = HomeUiState(
            selectedClassId = "movie",
            filtersByClass = mapOf("movie" to filters),
            selectedFiltersByClass = mapOf(
                "movie" to mapOf(
                    "sort" to "T",
                    "type" to "action",
                    "year" to "2026",
                    "tag" to "4k",
                    "stale" to "ignored",
                ),
            ),
        )

        assertEquals(
            listOf(
                ActiveFilterChip(filterKey = "type", filterName = "类型", valueLabel = "动作"),
                ActiveFilterChip(filterKey = "year", filterName = "年份", valueLabel = "2026"),
                ActiveFilterChip(filterKey = "tag", filterName = "标签", valueLabel = "4k"),
            ),
            uiState.activeFilterChips,
        )
    }

    @Test
    fun clearFilter_removesNonInitDimension_andRestoresInitDefaultDimension() = runBlocking {
        val spider = FakeFilterSpider()
        val site = Site(key = "site_a", name = "测试源", type = 4, api = "http://127.0.0.1/a", isHome = true)
        val siteDao = FakeSiteDao(listOf(site))
        val configDao = FakeConfigDao(listOf(Config(url = "http://127.0.0.1/c.json", name = "测试", home = "site_a", id = "")))
        val settings = SettingsRepository(sharedTempContext, HostMapping())
        val router = SpiderRouter(
            setOf(
                object : SpiderLoader {
                    override fun supports(site: Site): Boolean = site.key == "site_a"
                    override fun getSpider(site: Site): Spider = spider
                },
            ),
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
        val repo = SiteRepository(
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

        val vm = HomeViewModel(repo, siteDao, configDao, vodConfig, settings, router, nodeRuntime)
        store.put("filter_vm", vm)

        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.selectedClassId == "movie" && it.items.isNotEmpty() }
        }
        assertTrue(vm.uiState.value.activeFilterChips.isEmpty())

        vm.selectFilter("year", VodFilterValue(n = "2026", v = "2026"))
        vm.selectFilter("type", VodFilterValue(n = "动作", v = "action"))
        vm.selectFilter("sort", VodFilterValue(n = "最新", v = "R"))

        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.activeFilterChips.size == 3 }
        }
        assertEquals(
            listOf("2026", "动作", "最新"),
            vm.uiState.value.activeFilterChips.map { it.valueLabel },
        )

        vm.clearFilter("year")
        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.activeFilterChips.size == 2 }
        }
        assertFalse(vm.uiState.value.currentSelectedFilters.containsKey("year"))

        vm.clearFilter("sort")
        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.activeFilterChips.size == 1 }
        }
        assertEquals("T", vm.uiState.value.currentSelectedFilters["sort"])
        assertEquals(listOf("动作"), vm.uiState.value.activeFilterChips.map { it.valueLabel })
        assertEquals(mapOf("type" to "action", "sort" to "T"), spider.lastExtend)

        vm.clearFilter("type")
        withTimeout(5_000) {
            vm.uiState.first { !it.isLoading && it.activeFilterChips.isEmpty() }
        }
        assertEquals(mapOf("sort" to "T"), vm.uiState.value.currentSelectedFilters)
        // 恢复为初始默认筛选条件时命中分类缓存，不再重复发出 categoryContent 请求
        assertEquals(mapOf("type" to "action", "sort" to "T"), spider.lastExtend)
    }

    private class FakeFilterSpider : Spider {
        @Volatile
        var lastExtend: Map<String, String> = emptyMap()

        override suspend fun homeContent(filter: Boolean): Result = Result(
            classes = listOf(VodClass(typeId = "movie", typeName = "电影")),
            filters = mapOf(
                "movie" to listOf(
                    VodFilter(
                        key = "year",
                        name = "年份",
                        init = null,
                        values = listOf(VodFilterValue(n = "2026", v = "2026")),
                    ),
                    VodFilter(
                        key = "type",
                        name = "类型",
                        init = "",
                        values = listOf(VodFilterValue(n = "动作", v = "action")),
                    ),
                    VodFilter(
                        key = "sort",
                        name = "排序",
                        init = "T",
                        values = listOf(
                            VodFilterValue(n = "综合", v = "T"),
                            VodFilterValue(n = "最新", v = "R"),
                        ),
                    ),
                ),
            ),
        )

        override suspend fun categoryContent(
            tid: String,
            pg: String,
            filter: Boolean,
            extend: Map<String, String>,
        ): Result {
            lastExtend = extend
            return Result(
                page = 1,
                pageCount = 1,
                list = listOf(Vod(vodId = "v1", vodName = "测试电影")),
            )
        }

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
            Files.createTempDirectory("mewbox-home-filter-test").toFile().apply { deleteOnExit() }
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
