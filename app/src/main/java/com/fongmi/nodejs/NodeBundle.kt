package com.fongmi.nodejs

import android.content.Context
import okhttp3.Call
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Node.js 运行时资源包模型及统一门面，底层职责委托至
 * [NodeSourceLocator]、[NodeBundleStore] 与 [NodeBundleInstaller]。
 */
class NodeBundle internal constructor(
    val root: File,
    val indexMd5: String,
    val configMd5: String,
    @Volatile private var pending: Boolean = false,
    private val signingKey: String = "",
    private val sharedDataDir: File? = null,
) {
    val index: File = File(root, "index.js")
    val config: File = File(root, "index.config.js")
    val isPending: Boolean
        get() = pending

    val data: File
        get() {
            // 优先使用全局共享 data 目录，避免切换或编辑订阅后丢失网盘 Cookie 等本地状态
            val dir = sharedDataDir ?: File(root.parentFile, "data")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    @Synchronized
    fun accept() {
        if (!pending) return
        File(root, NodeBundleStore.PENDING_MARKER).delete()
        if (signingKey.isNotEmpty()) {
            try {
                Path.writeAtomically(
                    File(root.parentFile, NodeBundleStore.TRUSTED_KEY_FILE),
                    "$signingKey\n".toByteArray(StandardCharsets.US_ASCII),
                )
            } catch (_: IOException) {
            }
        }
        pending = false
    }

    fun matches(indexMd5: String, configMd5: String): Boolean {
        return this.indexMd5.equals(indexMd5, ignoreCase = true) &&
            this.configMd5.equals(configMd5, ignoreCase = true)
    }

    fun isSameVersion(other: NodeBundle?): Boolean {
        return other != null && matches(other.indexMd5, other.configMd5)
    }

    class LoadSession {
        private val canceled = AtomicBoolean()
        private val calls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

        fun cancel() {
            canceled.set(true)
            for (call in calls) {
                try {
                    call.cancel()
                } catch (_: Exception) {
                }
            }
        }

        fun attach(call: Call) {
            calls.add(call)
        }

        fun detach(call: Call) {
            calls.remove(call)
        }

        @Throws(InterruptedIOException::class)
        fun check() {
            if (canceled.get() || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("Node bundle loading canceled")
            }
        }
    }

    companion object {
        @JvmStatic
        fun isZip(source: String?): Boolean = NodeSourceLocator.isZip(source)

        @JvmStatic
        fun isConfig(url: String?): Boolean = NodeSourceLocator.isConfig(url)

        @JvmStatic
        fun isLocalNodeSource(source: String?): Boolean = NodeSourceLocator.isLocalNodeSource(source)

        @JvmStatic
        @Throws(IOException::class)
        fun companion(source: String, name: String): String = NodeSourceLocator.companion(source, name)

        @JvmStatic
        @Throws(IOException::class)
        fun parseMd5(text: String?): String = NodeSourceLocator.parseMd5(text)

        @JvmStatic
        @Throws(Exception::class)
        fun prepare(context: Context, source: String, onDownload: (() -> Unit)? = null): NodeBundle =
            NodeBundleInstaller.prepare(context, source, onDownload)

        @JvmStatic
        @Throws(Exception::class)
        fun prepareFromStream(
            context: Context,
            localSourceUrl: String,
            streamOpener: () -> InputStream,
            onDownload: (() -> Unit)? = null,
        ): NodeBundle = NodeBundleInstaller.prepareFromStream(context, localSourceUrl, streamOpener, onDownload)

        @JvmStatic
        fun restore(context: Context, source: String): NodeBundle? = NodeBundleStore.restore(context, source)

        @JvmStatic
        fun restoreLatest(context: Context): NodeBundle? = NodeBundleStore.restoreLatest(context)

        @JvmStatic
        fun cancelLoad() = NodeBundleInstaller.cancelLoad()

        @JvmStatic
        internal fun commit(active: File, staging: File) = NodeBundleStore.commit(active, staging)

        @JvmStatic
        internal fun restorePrevious(active: File, sharedDataDir: File? = null): NodeBundle? =
            NodeBundleStore.restorePrevious(active, sharedDataDir)

        @JvmStatic
        internal fun rollbackPending(active: File, sharedDataDir: File? = null): NodeBundle? =
            NodeBundleStore.rollbackPending(active, sharedDataDir)

        @JvmStatic
        fun prune(context: Context, sources: Iterable<String>?, currentSource: String?) =
            NodeBundleStore.prune(context, sources, currentSource)

        @JvmStatic
        fun pruneOrphanLocalFiles(context: Context, activeUrls: Iterable<String>) =
            NodeBundleStore.pruneOrphanLocalFiles(context, activeUrls)
    }
}
