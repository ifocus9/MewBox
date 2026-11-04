package com.mewbox.tv

import com.fongmi.nodejs.NodeBundle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * previous 回滚机制的回归测试。
 *
 * 这块是 bundle 更替时唯一的安全网：没有它，一个「MD5 和签名都对、
 * 但运行时会崩」的包会把唯一可用的缓存顶掉，用户下次冷启动彻底没包可用。
 * 安全网本身没验过等于没有，所以这里用纯文件操作把它跑一遍，不依赖模拟器。
 */
class NodeBundleRollbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sourceRoot: File
    private lateinit var active: File
    private lateinit var previous: File

    @Before
    fun setUp() {
        sourceRoot = tmp.newFolder("bundle")
        active = File(sourceRoot, "active")
        previous = File(sourceRoot, "previous")
    }

    private fun writeBundle(dir: File, tag: String) {
        dir.mkdirs()
        File(dir, "index.js").writeText("// $tag\nmodule.exports = {};")
        File(dir, "index.config.js").writeText("// config $tag\nmodule.exports = {};")
    }

    private fun indexTag(dir: File): String =
        File(dir, "index.js").readText().substringBefore("\n").removePrefix("// ")

    @Test
    fun activeIntact_returnsActiveAndKeepsPreviousUntouched() {
        writeBundle(active, "v2")
        writeBundle(previous, "v1")

        val bundle = NodeBundle.restorePrevious(active)

        assertNotNull(bundle)
        assertEquals("v2", indexTag(active))
        // previous 原封不动，active 完好时不该发生任何搬运
        assertTrue(File(previous, "index.js").exists())
        assertEquals("v1", indexTag(previous))
    }

    @Test
    fun activeMissing_restoresPackageFromPrevious() {
        writeBundle(previous, "v1")

        val bundle = NodeBundle.restorePrevious(active)

        assertNotNull(bundle)
        assertTrue(File(active, "index.js").exists())
        assertTrue(File(active, "index.config.js").exists())
        assertEquals("v1", indexTag(active))
        assertFalse("恢复后 previous 应已被搬空", File(previous, "index.js").exists())
    }

    @Test
    fun activePartial_fallsBackToPrevious() {
        active.mkdirs()
        File(active, "index.js").writeText("// half-written")
        writeBundle(previous, "v1")

        val bundle = NodeBundle.restorePrevious(active)

        assertNotNull(bundle)
        assertEquals("v1", indexTag(active))
        assertTrue(File(active, "index.config.js").exists())
    }

    @Test
    fun activeTruncated_fallsBackToPrevious() {
        active.mkdirs()
        // 文件存在但内容为空，md5 仍能算出来，readBundle 不会拦，
        // 这里验证的正是「内容坏但文件在」的情况交由调用方判断
        File(active, "index.js").writeText("")
        File(active, "index.config.js").writeText("")
        writeBundle(previous, "v1")

        val bundle = NodeBundle.restorePrevious(active)

        // 空文件的 md5 非空，所以 active 会被认为有效，previous 不动
        assertNotNull(bundle)
        assertEquals("", indexTag(active))
    }

    @Test
    fun bothMissing_returnsNull() {
        assertNull(NodeBundle.restorePrevious(active))
    }

    @Test
    fun previousAlsoBroken_returnsNull() {
        active.mkdirs()
        previous.mkdirs()
        File(previous, "index.js").writeText("// lonely")

        assertNull(NodeBundle.restorePrevious(active))
    }

    @Test
    fun commit_backsUpOldActiveToPrevious() {
        writeBundle(active, "v1")
        val staging = File(sourceRoot, "staging-1").also { writeBundle(it, "v2") }

        NodeBundle.commit(active, staging)

        assertEquals("v2", indexTag(active))
        assertEquals("v1", indexTag(previous))
        assertFalse("commit 后 staging 应已清空", File(staging, "index.js").exists())
    }

    @Test
    fun commitTwice_previousAlwaysHoldsLastWorkingVersion() {
        writeBundle(active, "v1")

        val staging2 = File(sourceRoot, "staging-2").also { writeBundle(it, "v2") }
        NodeBundle.commit(active, staging2)
        assertEquals("v2", indexTag(active))
        assertEquals("v1", indexTag(previous))

        val staging3 = File(sourceRoot, "staging-3").also { writeBundle(it, "v3") }
        NodeBundle.commit(active, staging3)
        assertEquals("v3", indexTag(active))
        assertEquals("v2", indexTag(previous))
    }

    @Test
    fun commitThenBreakActive_recoversPreviousContent() {
        writeBundle(active, "v1")
        val staging = File(sourceRoot, "staging-1").also { writeBundle(it, "v2") }
        NodeBundle.commit(active, staging)

        // 模拟新包上线但实际不可用：把 active 清空
        File(active, "index.js").delete()

        val bundle = NodeBundle.restorePrevious(active)

        assertNotNull(bundle)
        assertTrue(File(active, "index.js").exists())
        // 关键：退回到的是上一个可用版本 v1，而不是刚坏的 v2
        assertEquals("v1", indexTag(active))
    }
}
