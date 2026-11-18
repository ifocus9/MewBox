package com.mewbox.tv

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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
import com.mewbox.tv.catvod.Spider
import com.mewbox.tv.catvod.SpiderLoader
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.HistoryDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.engine.nodejs.NodeRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var mockFilesDir: File
    private lateinit var testContext: Context
    private lateinit var siteDao: FakeSiteDao
    private lateinit var configDao: FakeConfigDao
    private lateinit var historyDao: FakeHistoryDao
    private lateinit var settings: SettingsRepository
    private lateinit var vodConfig: VodConfig
    private lateinit var importer: LocalSourceImporter
    private lateinit var coordinator: SubscriptionCoordinator

    @Before
    fun setUp() {
        mockFilesDir = tmp.newFolder("files")
        testContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = mockFilesDir
            override fun getCacheDir(): File = File(mockFilesDir, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        siteDao = FakeSiteDao()
        configDao = FakeConfigDao()
        historyDao = FakeHistoryDao()
        settings = SettingsRepository(InMemoryPreferencesDataStore(), HostMapping())
        val router = SpiderRouter(
            setOf(
                object : SpiderLoader {
                    override fun supports(site: Site): Boolean = true
                    override fun getSpider(site: Site): Spider = object : Spider {
                        override suspend fun homeContent(filter: Boolean): Result = Result()
                        override suspend fun homeVideoContent(): Result = Result()
                        override suspend fun categoryContent(tid: String, pg: String, filter: Boolean, extend: Map<String, String>): Result = Result()
                        override suspend fun detailContent(ids: List<String>): Result = Result()
                        override suspend fun searchContent(keyword: String, quick: Boolean, pg: String): Result = Result()
                        override suspend fun playerContent(flag: String, id: String, vipFlags: List<String>): Result = Result()
                    }
                },
            ),
        )
        val nodeRuntime = NodeRuntime(testContext, LocalMessageBridge(testContext), settings)
        vodConfig = VodConfig(
            context = testContext,
            service = T4Service(OkHttpClient()),
            siteDao = siteDao,
            configDao = configDao,
            settings = settings,
            router = router,
            nodeRuntime = nodeRuntime,
        )
        importer = LocalSourceImporter(testContext, vodConfig, configDao)
        coordinator = SubscriptionCoordinator(testContext, vodConfig, importer, configDao, historyDao, settings)
    }

    @Test
    fun importFromStream_localJson_archivesWritesDefaultNameAndReloads() = runBlocking {
        val sampleJson = """
            {
              "sites": [
                { "key": "site_a", "name": "站点A", "type": 4, "api": "https://a.test/vod" }
              ]
            }
        """.trimIndent()

        val outcome = importer.importFromStream(
            displayName = "my_tvbox.json",
            customName = "",
            streamOpener = { ByteArrayInputStream(sampleJson.toByteArray(Charsets.UTF_8)) },
        )

        assertEquals(1, outcome.siteCount)
        assertFalse(outcome.isHtml)
        assertTrue(outcome.loadedUrl.startsWith(VodConfig.LOCAL_VOD_SCHEME))

        val saved = configDao.find(outcome.loadedUrl)
        assertNotNull(saved)
        assertEquals("my_tvbox.json", saved!!.name)
        assertEquals(Config.TYPE_VOD, saved.type)
        assertTrue(VodConfig.vodConfigFile(testContext, saved.id).isFile)

        // 从历史切换回来 / 刷新均应正常读取 local_configs/<id>.json
        assertEquals(1, vodConfig.reload())
    }

    @Test
    fun importFromStream_localHtml_inPlaceReplacePreservesConfigIdAndPrunesOldVodFile() = runBlocking {
        val json = """{"sites":[{"key":"s1","name":"S1","type":4,"api":"https://a.test/vod"}]}"""
        val first = importer.importFromStream("first.json", "", { ByteArrayInputStream(json.toByteArray()) })
        val firstConfig = requireNotNull(configDao.find(first.loadedUrl))
        val preservedId = firstConfig.id

        val html = "<!DOCTYPE html><html><head><title>网页测试</title></head><body>Hi</body></html>"
        val replaced = importer.importFromStream(
            displayName = "search.html",
            customName = "",
            streamOpener = { ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)) },
            replaceOldUrl = first.loadedUrl,
            existingConfigId = preservedId,
        )
        vodConfig.pruneOrphanStorage()

        assertTrue(replaced.isHtml)
        assertEquals(0, replaced.siteCount)
        val replacedConfig = requireNotNull(configDao.find(replaced.loadedUrl))
        assertEquals(preservedId, replacedConfig.id)
        assertEquals("search.html", replacedConfig.name)
        assertTrue(VodConfig.htmlConfigFile(testContext, preservedId).isFile)
        assertFalse("原 local_configs 文件应在替换清理后删除", VodConfig.vodConfigFile(testContext, preservedId).exists())
        assertNull(configDao.find(first.loadedUrl))
    }

    @Test
    fun coordinator_renameLocalWontBlankOut_andDeletePrunesOrphanArchive() = runBlocking {
        val json = """{"sites":[{"key":"s1","name":"S1","type":4,"api":"https://a.test/vod"}]}"""
        val first = importer.importFromStream("local_a.json", "", { ByteArrayInputStream(json.toByteArray()) })
        val firstCfg = requireNotNull(configDao.find(first.loadedUrl))

        // 尝试清空本地订阅名称时保持默认回退名称，避免历史列表暴露 internal scheme
        val renameRes = coordinator.saveConfig(first.loadedUrl, "", pickedUri = null, isEdit = true, oldUrl = first.loadedUrl)
        assertTrue(renameRes is SubscriptionCoordinator.ActionResult.Renamed)
        assertEquals("local_a.json", configDao.find(first.loadedUrl)?.name)

        // 当前激活不可删
        assertEquals("当前激活的订阅不可删除", coordinator.deleteConfig(first.loadedUrl))

        // 新增第二个订阅激活后，删除第一个订阅，验证文件与历史同时清干净
        val second = importer.importFromStream("local_b.json", "自定名", { ByteArrayInputStream(json.toByteArray()) })
        assertTrue(second.loadedUrl != first.loadedUrl)
        assertNull(coordinator.deleteConfig(first.loadedUrl))
        assertNull(configDao.find(first.loadedUrl))
        assertEquals(listOf(firstCfg.id), historyDao.clearedConfigIds)
        assertFalse(VodConfig.vodConfigFile(testContext, firstCfg.id).exists())
        assertTrue(VodConfig.vodConfigFile(testContext, configDao.find(second.loadedUrl)!!.id).isFile)
    }

    @Test
    fun importFromStream_newZipFailure_cleansUpOrphanBundleDir() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("index.js"))
            zos.write("module.exports = {};".toByteArray())
            zos.closeEntry()
            // 故意缺少 index.config.js，触发 ZIP 校验失败
        }
        val zipBytes = out.toByteArray()

        var thrown = false
        try {
            importer.importFromStream("broken.zip", "", { ByteArrayInputStream(zipBytes) })
        } catch (_: Exception) {
            thrown = true
        }
        assertTrue(thrown)
        val bundlesRoot = File(mockFilesDir, "nodejs/bundles")
        assertTrue("首次导入 ZIP 失败不应遗留孤儿 bundle 目录", bundlesRoot.listFiles().isNullOrEmpty())
    }

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

    private class FakeSiteDao : SiteDao {
        private val state = MutableStateFlow<List<Site>>(emptyList())
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

    private class FakeConfigDao : ConfigDao {
        private val state = MutableStateFlow<List<Config>>(emptyList())
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
        val clearedConfigIds = mutableListOf<String>()
        private val state = MutableStateFlow<List<History>>(emptyList())
        override fun observeAll(): Flow<List<History>> = state
        override fun observeByConfig(configId: String): Flow<List<History>> = state
        override suspend fun get(key: String): History? = null
        override suspend fun find(configId: String, siteKey: String, vodId: String): History? = null
        override suspend fun upsert(history: History) {}
        override suspend fun delete(key: String) {}
        override suspend fun clearByConfig(configId: String) {
            clearedConfigIds.add(configId)
        }
        override suspend fun clearTrackPreferencesByConfig(configId: String) {}
        override suspend fun clear() {}
    }
}
