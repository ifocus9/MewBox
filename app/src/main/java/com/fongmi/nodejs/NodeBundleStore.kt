package com.fongmi.nodejs

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Node.js 包磁盘存储管理：
 * - 全局共享 `nodejs/data` 与 `nodejs/profile.json`（切换订阅保留网盘 Cookie）
 * - 每源隔离目录 `bundles/<key>/{active, previous}` 与原子提交 / 回滚
 * - 孤儿缓存及无主本地归档清理
 */
internal object NodeBundleStore {

    const val PENDING_MARKER = ".pending"
    const val TRUSTED_KEY_FILE = "trusted-key.sha256"
    const val ZIP_MD5_FILE = "zip.md5"
    const val REMOTE_CONFIG_MD5_FILE = ".remote-config-md5"

    private val MIGRATION_LOCK = Any()

    fun sharedDataDir(context: Context): File = File(context.filesDir, "nodejs/data")

    fun bundlesRoot(context: Context): File = File(context.filesDir, "nodejs/bundles")

    fun sourceRoot(context: Context, source: String): File =
        File(bundlesRoot(context), NodeSourceLocator.bundleKey(source))

    fun readBundle(root: File, sharedDataDir: File? = null): NodeBundle? {
        if (!root.isDirectory) return null
        val index = File(root, "index.js")
        val config = File(root, "index.config.js")
        if (!index.isFile || !config.isFile) return null
        val indexMd5 = Crypto.md5(index)
        val configMd5 = Crypto.md5(config)
        if (indexMd5.isEmpty() || configMd5.isEmpty()) return null
        val isPending = File(root, PENDING_MARKER).isFile
        return NodeBundle(
            root = root,
            indexMd5 = indexMd5,
            configMd5 = configMd5,
            pending = isPending,
            sharedDataDir = sharedDataDir,
        )
    }

    /**
     * 提交新资源包：清理旧运行临时重定向目录后将旧 active 备至 previous，再落盘新包。
     */
    fun commit(active: File, staging: File) {
        val parent = active.parentFile ?: throw IOException("Node bundle has no parent directory")
        val previous = File(parent, "previous")
        Path.clear(previous)
        val hadActive = active.exists()
        if (hadActive) {
            Path.clear(File(active, "Temp"))
            moveOrCopy(active, previous)
        }
        try {
            moveOrCopy(staging, active)
        } catch (e: IOException) {
            if (hadActive && previous.isDirectory) {
                Path.clear(active)
                moveOrCopy(previous, active)
            }
            throw e
        }
    }

    /** active 损坏或丢失时，自动从 previous 恢复稳定包。 */
    fun restorePrevious(active: File, sharedDataDir: File? = null): NodeBundle? {
        readBundle(active, sharedDataDir)?.let { return it }
        return promotePrevious(active, sharedDataDir, reason = "active 不完整")
    }

    /**
     * 运行期安全回滚：仅当 active 携带未验收 `.pending` 标记且存在完整 `previous` 时退回旧包。
     */
    fun rollbackPending(active: File, sharedDataDir: File? = null): NodeBundle? {
        if (!File(active, PENDING_MARKER).isFile) return null
        val restored = promotePrevious(active, sharedDataDir, reason = "新包启动失败") ?: return null
        File(active, PENDING_MARKER).delete()
        active.parentFile?.let { sourceRoot ->
            File(sourceRoot, ZIP_MD5_FILE).delete()
            File(sourceRoot, REMOTE_CONFIG_MD5_FILE).delete()
        }
        return readBundle(active, sharedDataDir)
    }

    private fun promotePrevious(active: File, sharedDataDir: File?, reason: String): NodeBundle? {
        val parent = active.parentFile ?: return null
        val previous = File(parent, "previous")
        readBundle(previous, sharedDataDir) ?: return null
        Timber.i("Node bundle %s，回退到 previous 版本", reason)
        Path.clear(active)
        moveOrCopy(previous, active)
        return readBundle(active, sharedDataDir)
    }

    fun restore(context: Context, source: String): NodeBundle? {
        if (!NodeSourceLocator.isConfig(source)) return null
        val sharedData = sharedDataDir(context)
        migrateLegacyDataIfNeeded(context, sharedData)
        val active = File(sourceRoot(context, source), "active")
        return restorePrevious(active, sharedData)
    }

    fun restoreLatest(context: Context): NodeBundle? {
        val sharedData = sharedDataDir(context)
        migrateLegacyDataIfNeeded(context, sharedData)
        val bundlesDir = bundlesRoot(context)
        if (!bundlesDir.isDirectory) return null
        val candidates = bundlesDir.listFiles { file -> file.isDirectory } ?: return null
        return candidates
            .sortedByDescending { it.lastModified() }
            .firstNotNullOfOrNull { dir ->
                restorePrevious(File(dir, "active"), sharedData)
            }
    }

    fun readTrustedKey(sourceRoot: File): String = readSmallFile(File(sourceRoot, TRUSTED_KEY_FILE))

    fun readZipMd5(sourceRoot: File): String = readSmallFile(File(sourceRoot, ZIP_MD5_FILE))

    fun writeZipMd5(sourceRoot: File, md5: String) {
        if (md5.isEmpty()) return
        Path.writeAtomically(File(sourceRoot, ZIP_MD5_FILE), md5.toByteArray(StandardCharsets.US_ASCII))
    }

    fun readRemoteConfigMd5(sourceRoot: File): String = readSmallFile(File(sourceRoot, REMOTE_CONFIG_MD5_FILE))

    fun writeRemoteConfigMd5(sourceRoot: File, md5: String?) {
        val target = File(sourceRoot, REMOTE_CONFIG_MD5_FILE)
        if (md5.isNullOrBlank()) {
            target.delete()
        } else {
            Path.writeAtomically(target, md5.toByteArray(StandardCharsets.US_ASCII))
        }
    }

    fun writeSidecars(staging: File, indexMd5: String, configMd5: String, markPending: Boolean = true) {
        Path.writeAtomically(File(staging, "index.js.md5"), "$indexMd5\n".toByteArray(StandardCharsets.US_ASCII))
        Path.writeAtomically(File(staging, "index.config.js.md5"), "$configMd5\n".toByteArray(StandardCharsets.US_ASCII))
        if (markPending) {
            Path.writeAtomically(File(staging, PENDING_MARKER), "$indexMd5:$configMd5".toByteArray(StandardCharsets.US_ASCII))
        }
    }

    fun pruneSourceRoot(sourceRoot: File) {
        sourceRoot.listFiles()?.forEach { file ->
            if (file.name.startsWith("staging-") || file.name == ".downloads") {
                Path.clear(file)
            }
        }
    }

    fun prune(context: Context, sources: Iterable<String>?, currentSource: String?) {
        val root = bundlesRoot(context)
        if (!root.isDirectory) return
        val validKeys = HashSet<String>()
        sources?.forEach { source ->
            if (NodeSourceLocator.isConfig(source)) validKeys.add(NodeSourceLocator.bundleKey(source))
        }
        if (currentSource != null && NodeSourceLocator.isConfig(currentSource)) {
            validKeys.add(NodeSourceLocator.bundleKey(currentSource))
        }
        root.listFiles()?.forEach { bundleDir ->
            if (!bundleDir.isDirectory) return@forEach
            if (!validKeys.contains(bundleDir.name)) {
                Path.clear(bundleDir)
            } else {
                pruneSourceRoot(bundleDir)
            }
        }
    }

    /** 清理已不在订阅列表中的本地配置文件与历史遗留 `filesDir/subscriptions/` 残留文件。 */
    fun pruneOrphanLocalFiles(context: Context, activeUrls: Iterable<String>) {
        val urlSet = activeUrls.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val keptVodIds = urlSet
            .filter { it.startsWith("local://vod/", ignoreCase = true) }
            .map { it.removePrefix("local://vod/").trim() }
            .toSet()
        val keptPaths = urlSet
            .filter { it.startsWith("file://", ignoreCase = true) }
            .mapNotNull { fileUrl ->
                val rawPath = runCatching { URI(fileUrl).path }.getOrNull()
                    ?: fileUrl.removePrefix("file://").substringBefore('?').substringBefore('#')
                rawPath.takeIf { it.isNotBlank() }?.let { runCatching { File(it).canonicalPath }.getOrNull() }
            }
            .toSet()

        pruneDirExcept(File(context.filesDir, "local_configs")) { file ->
            keptVodIds.contains(file.name.removeSuffix(".json"))
        }
        pruneDirExcept(File(context.filesDir, "local_html")) { file ->
            keptPaths.contains(runCatching { file.canonicalPath }.getOrNull())
        }
        pruneDirExcept(File(context.filesDir, "subscriptions")) { file ->
            keptPaths.contains(runCatching { file.canonicalPath }.getOrNull())
        }
    }

    private fun pruneDirExcept(dir: File, keep: (File) -> Boolean) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (!keep(child)) {
                Path.clear(child)
            }
        }
    }

    /**
     * 兼容升级迁移：旧版本将 db.json 与 profile.json 隔离在 bundles/<sha256>/ 下，
     * 当全局共享文件尚不存在时自动从旧包中迁出，优先保留包含网盘 Cookie 配置的 db.json。
     */
    fun migrateLegacyDataIfNeeded(context: Context, sharedData: File) {
        synchronized(MIGRATION_LOCK) {
            val bundlesDir = bundlesRoot(context)
            if (!bundlesDir.isDirectory) return
            val bundleDirs = bundlesDir.listFiles { file -> file.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (bundleDirs.isEmpty()) return

            migrateLegacyDbFile(bundleDirs, File(sharedData, "db.json"))
            migrateLegacyExtraData(bundleDirs, sharedData)
            val sharedProfile = File(sharedData.parentFile, "profile.json")
            migrateLegacyProfile(bundleDirs, sharedProfile)
        }
    }

    private fun migrateLegacyDbFile(bundleDirs: List<File>, targetDb: File) {
        if (targetDb.isFile && targetDb.length() > 0L) return
        val chosen = bundleDirs
            .map { File(it, "data/db.json") }
            .filter { it.isFile && it.length() > 0L }
            .sortedWith(
                compareByDescending<File> { containsCookieSetting(it) }
                    .thenByDescending { it.lastModified() },
            )
            .firstOrNull()
            ?: return
        copyFileSafely(chosen, targetDb, "db.json")
    }

    private fun containsCookieSetting(dbFile: File): Boolean {
        return try {
            dbFile.readText(StandardCharsets.UTF_8).contains("\"cookie\"", ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    private fun migrateLegacyExtraData(bundleDirs: List<File>, sharedData: File) {
        for (bundleDir in bundleDirs) {
            val legacyDataDir = File(bundleDir, "data")
            if (!legacyDataDir.isDirectory) continue
            val children = legacyDataDir.listFiles() ?: continue
            for (child in children) {
                if (child.name == "db.json") continue
                val target = File(sharedData, child.name)
                if (target.exists()) continue
                try {
                    if (!sharedData.exists() && !sharedData.mkdirs()) return
                    copyDirectory(child, target)
                } catch (e: IOException) {
                    Timber.w(e, "迁移旧 Node 数据文件失败: %s", child.name)
                }
            }
        }
    }

    private fun migrateLegacyProfile(bundleDirs: List<File>, targetProfile: File) {
        if (targetProfile.isFile && targetProfile.length() > 0L) return
        val chosen = bundleDirs
            .map { File(it, "profile.json") }
            .firstOrNull { it.isFile && it.length() > 0L }
            ?: return
        copyFileSafely(chosen, targetProfile, "profile.json")
    }

    private fun copyFileSafely(source: File, target: File, label: String) {
        try {
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return
            val temp = File(parent, "${target.name}.migrate-${UUID.randomUUID()}")
            copyFile(source, temp)
            if (!temp.renameTo(target)) {
                copyFile(temp, target)
                temp.delete()
            }
            Timber.i("已将旧 Node %s 迁移至全局共享目录: %s", label, target.absolutePath)
        } catch (e: IOException) {
            Timber.w(e, "迁移旧 Node %s 失败", label)
        }
    }

    private fun readSmallFile(file: File): String {
        if (!file.isFile) return ""
        return try {
            Path.read(file).trim()
        } catch (_: IOException) {
            ""
        }
    }

    internal fun moveOrCopy(from: File, to: File) {
        if (!from.renameTo(to)) copyDirectory(from, to)
        Path.clear(from)
    }

    internal fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create dir: $target")
            source.listFiles()?.forEach { file ->
                copyDirectory(file, File(target, file.name))
            }
        } else {
            copyFile(source, target)
        }
    }

    internal fun copyFile(inFile: File, outFile: File) {
        FileInputStream(inFile).use { fis ->
            FileOutputStream(outFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                var len: Int
                while (fis.read(buf).also { len = it } != -1) {
                    fos.write(buf, 0, len)
                }
                fos.flush()
                fos.fd.sync()
            }
        }
    }
}
