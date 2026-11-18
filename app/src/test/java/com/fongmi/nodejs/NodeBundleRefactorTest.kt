package com.fongmi.nodejs

import android.content.Context
import android.content.ContextWrapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NodeBundleRefactorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var mockFilesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        mockFilesDir = tmp.newFolder("files")
        context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = mockFilesDir
        }
    }

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun locator_supportsIndexJsAndLocalNodeAndRejectsContentUri() {
        assertTrue(NodeBundle.isConfig("https://example.com/dist/index.js"))
        assertTrue(NodeBundle.isConfig("https://example.com/dist/index.js?v=2#hash"))
        assertTrue(NodeBundle.isConfig("https://example.com/dist/index.js.md5"))
        assertTrue(NodeBundle.isConfig("local://node/c123456"))
        assertTrue(NodeBundle.isLocalNodeSource("local://node/c123456"))
        assertFalse(NodeBundle.isConfig("content://com.android.externalstorage/123"))

        assertEquals(
            "https://example.com/dist/index.js.md5?v=2#hash",
            NodeSourceLocator.normalizeMd5Source("https://example.com/dist/index.js?v=2#hash"),
        )
    }

    @Test
    fun prepareFromStream_supportsThreeLevelNestingAndCleansMacOsx() {
        val bytes = zipBytes(
            mapOf(
                "__MACOSX/._index.js" to "junk",
                "wrap/inner/dist/index.js" to "// v1\nmodule.exports = {};",
                "wrap/inner/dist/index.config.js" to "// cfg\nmodule.exports = {};",
                "wrap/inner/dist/lib/helper.js" to "exports.ok = true;",
            ),
        )

        val bundle = NodeBundle.prepareFromStream(
            context = context,
            localSourceUrl = "local://node/test1",
            streamOpener = { ByteArrayInputStream(bytes) },
        )

        assertTrue(bundle.isPending)
        assertTrue(bundle.index.isFile)
        assertTrue(bundle.config.isFile)
        assertTrue(File(bundle.root, "lib/helper.js").isFile)
        assertFalse(File(bundle.root, "wrap").exists())
        assertFalse(File(bundle.root, "__MACOSX").exists())

        // 已落盘后直接通过 local://node/test1 无网恢复
        val restored = NodeBundle.prepare(context, "local://node/test1")
        assertEquals(bundle.indexMd5, restored.indexMd5)
    }

    @Test
    fun prepareFromStream_missingConfigJs_throwsIOException() {
        val bytes = zipBytes(mapOf("index.js" to "module.exports = {};"))
        var caught: IOException? = null
        try {
            NodeBundle.prepareFromStream(context, "local://node/missing_cfg", { ByteArrayInputStream(bytes) })
        } catch (e: IOException) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(caught!!.message?.contains("index.config.js") == true)
    }

    @Test
    fun prepareRemote_indexJsEntry_toleratesMissingAndMismatchedConfigMd5() {
        val indexContent = "// remote index\nmodule.exports = {};"
        val configContent = "// remote config\nmodule.exports = { sites: [] };"
        val indexMd5 = Crypto.md5(indexContent)
        val indexDownloads = AtomicInteger(0)

        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/dist/index.js.md5" -> MockResponse().setResponseCode(200).setBody(indexMd5)
                        "/dist/index.config.js.md5" -> MockResponse().setResponseCode(200).setBody("deadbeefdeadbeefdeadbeefdeadbeef")
                        "/dist/index.js" -> {
                            indexDownloads.incrementAndGet()
                            MockResponse().setResponseCode(200).setBody(indexContent)
                        }
                        "/dist/index.config.js" -> MockResponse().setResponseCode(200).setBody(configContent)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }

        try {
            val entryUrl = server.url("/dist/index.js").toString()
            val first = NodeBundle.prepare(context, entryUrl)
            first.accept()

            assertEquals(indexMd5, first.indexMd5)
            assertEquals(Crypto.md5(configContent), first.configMd5)
            assertEquals(1, indexDownloads.get())

            // 第二次探测应命中 .remote-config-md5 缓存，不重下 index.js
            val second = NodeBundle.prepare(context, entryUrl)
            assertEquals(first.indexMd5, second.indexMd5)
            assertEquals(1, indexDownloads.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun prepareRemote_missingConfigJs_fallsBackToEmptyAndStoredConfig() {
        val indexV1 = "// v1"
        val indexV2 = "// v2"
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/v1/index.js.md5" -> MockResponse().setResponseCode(200).setBody(Crypto.md5(indexV1))
                        "/v1/index.js" -> MockResponse().setResponseCode(200).setBody(indexV1)
                        "/v2/index.js.md5" -> MockResponse().setResponseCode(200).setBody(Crypto.md5(indexV2))
                        "/v2/index.js" -> MockResponse().setResponseCode(200).setBody(indexV2)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }

        try {
            val bundle = NodeBundle.prepare(context, server.url("/v1/index.js.md5").toString())
            bundle.accept()
            assertTrue(bundle.config.readText().contains("{}"))

            // v1 验证旧配置复用：在同源 URL 升级 index.js 时若 config 404 应沿用 stored.config
            bundle.config.writeText("// user stored config\nmodule.exports = { keep: true };")
            val sourceUrl = server.url("/v1/index.js.md5").toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/v1/index.js.md5" -> MockResponse().setResponseCode(200).setBody(Crypto.md5(indexV2))
                        "/v1/index.js" -> MockResponse().setResponseCode(200).setBody(indexV2)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val upgraded = NodeBundle.prepare(context, sourceUrl)
            assertTrue(upgraded.config.readText().contains("keep: true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rollbackPending_restoresPreviousVersionAndPreservesSharedData() {
        val v1Bytes = zipBytes(mapOf("index.js" to "// v1", "index.config.js" to "// c1"))
        val v2Bytes = zipBytes(mapOf("index.js" to "// broken v2", "index.config.js" to "// c2"))

        val v1 = NodeBundle.prepareFromStream(context, "local://node/r1", { ByteArrayInputStream(v1Bytes) })
        v1.accept()
        val db = File(v1.data, "db.json")
        db.writeText("""{"baidu":{"cookie":"keep-me"}}""", Charsets.UTF_8)

        val v2 = NodeBundle.prepareFromStream(context, "local://node/r1", { ByteArrayInputStream(v2Bytes) })
        assertTrue(v2.isPending)
        assertEquals("// broken v2", v2.index.readText())

        val rolled = NodeBundle.rollbackPending(v2.root, v2.data)
        assertNotNull(rolled)
        assertEquals("// v1", rolled!!.index.readText())
        assertFalse(rolled.isPending)
        assertEquals("""{"baidu":{"cookie":"keep-me"}}""", db.readText(Charsets.UTF_8))
    }

    @Test
    fun pruneOrphanLocalFiles_deletesUnreferencedArchivesOnly() {
        val subsDir = File(mockFilesDir, "subscriptions").apply { mkdirs() }
        val vodDir = File(mockFilesDir, "local_configs").apply { mkdirs() }
        val htmlDir = File(mockFilesDir, "local_html").apply { mkdirs() }

        val orphanSub = File(subsDir, "stale.zip").apply { writeText("old") }
        val keptVod = File(vodDir, "cfg1.json").apply { writeText("{}") }
        val orphanVod = File(vodDir, "cfg_dead.json").apply { writeText("{}") }
        val keptHtml = File(htmlDir, "html1.html").apply { writeText("<html/>") }
        val orphanHtml = File(htmlDir, "html_dead.html").apply { writeText("<html/>") }

        NodeBundle.pruneOrphanLocalFiles(
            context,
            listOf(
                "local://vod/cfg1",
                "file://${keptHtml.absolutePath}",
            ),
        )

        assertFalse(orphanSub.exists())
        assertTrue(keptVod.isFile)
        assertFalse(orphanVod.exists())
        assertTrue(keptHtml.isFile)
        assertFalse(orphanHtml.exists())
    }
}
