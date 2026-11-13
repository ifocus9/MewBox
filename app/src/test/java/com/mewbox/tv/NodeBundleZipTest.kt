package com.mewbox.tv

import android.content.Context
import android.content.ContextWrapper
import com.fongmi.nodejs.Crypto
import com.fongmi.nodejs.NodeBundle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NodeBundleZipTest {

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

    private fun createZip(zipFile: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    @Test
    fun prepare_withFlatZip_extractsAndGeneratesMd5Correctly() {
        val zipFile = tmp.newFile("bundle_flat.zip")
        createZip(
            zipFile,
            mapOf(
                "index.js" to "// index v1\nmodule.exports = {};",
                "index.config.js" to "// config v1\nmodule.exports = {};",
            ),
        )

        val bundle = NodeBundle.prepare(context, zipFile.absolutePath)

        assertNotNull(bundle)
        assertTrue(bundle.index.isFile)
        assertTrue(bundle.config.isFile)
        assertEquals(Crypto.md5(bundle.index), bundle.indexMd5)
        assertEquals(Crypto.md5(bundle.config), bundle.configMd5)
        assertTrue(File(bundle.root, "index.js.md5").isFile)
        assertTrue(File(bundle.root, "index.config.js.md5").isFile)
    }

    @Test
    fun prepare_withNestedDistDir_flattensSubdirectory() {
        val zipFile = tmp.newFile("bundle_nested.zip")
        createZip(
            zipFile,
            mapOf(
                "dist/index.js" to "// nested index\nmodule.exports = {};",
                "dist/index.config.js" to "// nested config\nmodule.exports = {};",
                "dist/package.json" to """{"name":"test"}""",
            ),
        )

        val bundle = NodeBundle.prepare(context, "file://${zipFile.absolutePath}")

        assertNotNull(bundle)
        assertTrue(bundle.index.isFile)
        assertTrue(bundle.config.isFile)
        assertTrue(File(bundle.root, "package.json").isFile)
        assertFalse(File(bundle.root, "dist").exists())
    }

    @Test
    fun prepare_missingRequiredFile_throwsIOException() {
        val zipFile = tmp.newFile("bundle_invalid.zip")
        createZip(
            zipFile,
            mapOf(
                "index.js" to "// lonely index",
            ),
        )

        var thrown = false
        try {
            NodeBundle.prepare(context, zipFile.absolutePath)
        } catch (e: IOException) {
            thrown = true
            assertTrue(e.message?.contains("index.config.js") == true)
        }
        assertTrue("缺少 index.config.js 应抛出 IOException", thrown)
    }

    @Test
    fun prepare_sameZipTwice_reusesStoredBundle() {
        val zipFile = tmp.newFile("bundle_reuse.zip")
        createZip(
            zipFile,
            mapOf(
                "index.js" to "// index reusable\nmodule.exports = {};",
                "index.config.js" to "// config reusable\nmodule.exports = {};",
            ),
        )

        val first = NodeBundle.prepare(context, zipFile.absolutePath)
        first.accept()

        val second = NodeBundle.prepare(context, zipFile.absolutePath)
        assertEquals(first.indexMd5, second.indexMd5)
        assertEquals(first.configMd5, second.configMd5)
    }

    @Test
    fun prepare_remoteZip_downloadsAndPreparesCorrectly() {
        val server = MockWebServer()
        val zipFile = tmp.newFile("remote_bundle.zip")
        createZip(
            zipFile,
            mapOf(
                "index.js" to "// remote index\nmodule.exports = {};",
                "index.config.js" to "// remote config\nmodule.exports = {};",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zipFile.readBytes())))
        server.start()

        val url = server.url("/dist.zip").toString()
        val bundle = NodeBundle.prepare(context, url)
        assertNotNull(bundle)
        assertTrue(bundle.index.isFile)
        assertEquals(Crypto.md5(bundle.index), bundle.indexMd5)

        // 模拟后续服务器异常断网，应自动降级复用已有的本地缓存 bundle
        server.enqueue(MockResponse().setResponseCode(500))
        val bundleReused = NodeBundle.prepare(context, url)
        assertNotNull(bundleReused)
        assertEquals(bundle.indexMd5, bundleReused.indexMd5)

        server.shutdown()
    }
}
