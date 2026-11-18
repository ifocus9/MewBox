package com.fongmi.nodejs

import android.content.Context
import com.mewbox.tv.api.net.SSLCompat
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Node.js 包下载、探测与解压安装器：
 * - 远程目录源：并发探测 sidecar，`index.js` 强验 MD5，`index.config.js(.md5)` 缺失或失配（无签名约束时）降级放行；
 * - ZIP 源（远程 / `file://` / SAF `InputStream`）：直解至 staging（64MB 上限 + Zip-Slip 校验 + 3 层嵌套自动展平），
 *   本地 ZIP 严格要求同时包含 `index.js` 与 `index.config.js`。
 */
internal object NodeBundleInstaller {

    private const val MAX_INDEX_BYTES = 32L * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 2L * 1024 * 1024
    private const val MAX_MD5_BYTES = 1024
    private const val MAX_MANIFEST_BYTES = 32 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_FLATTEN_DEPTH = 3
    private const val PROBE_TIMEOUT_SECONDS = 10L

    /** 拉 bundle 正文用：大文件，容忍慢网 */
    private val CLIENT = SSLCompat.apply(OkHttpClient.Builder())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 探测 sidecar 用：只取几百字节，等不起长超时。
     * 禁用自动重试，单次等待收敛在 3s，避免离线订阅卡死冷启动。
     */
    private val PROBE_CLIENT = SSLCompat.apply(OkHttpClient.Builder())
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val PROBE_EXECUTOR = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "node-bundle-probe").apply { isDaemon = true }
    }

    private val SOURCE_LOCKS = ConcurrentHashMap<String, Any>()
    private val ACTIVE_LOAD = AtomicReference<NodeBundle.LoadSession?>()

    fun cancelLoad() {
        ACTIVE_LOAD.getAndSet(null)?.cancel()
    }

    @Throws(Exception::class)
    fun prepare(context: Context, source: String, onDownload: (() -> Unit)? = null): NodeBundle {
        return withSession(source) { session, key ->
            val sharedData = NodeBundleStore.sharedDataDir(context)
            NodeBundleStore.migrateLegacyDataIfNeeded(context, sharedData)
            val sourceRoot = File(NodeBundleStore.bundlesRoot(context), key)
            val active = File(sourceRoot, "active")
            val stored = NodeBundleStore.restorePrevious(active, sharedData)
            NodeBundleStore.pruneSourceRoot(sourceRoot)

            when {
                NodeSourceLocator.isLocalNodeSource(source) ->
                    stored ?: throw IOException("本地 Node 资源包不存在或已损坏，请重新导入压缩包")
                NodeSourceLocator.isZip(source) ->
                    installZipFromLocation(source, sourceRoot, active, stored, sharedData, session, onDownload)
                else ->
                    installRemote(source, sourceRoot, active, stored, sharedData, session, onDownload)
            }
        }
    }

    @Throws(Exception::class)
    fun prepareFromStream(
        context: Context,
        localSourceUrl: String,
        streamOpener: () -> InputStream,
        onDownload: (() -> Unit)? = null,
    ): NodeBundle {
        if (!NodeSourceLocator.isLocalNodeSource(localSourceUrl) && !NodeSourceLocator.isZip(localSourceUrl)) {
            throw IOException("Unsupported stream bundle source: $localSourceUrl")
        }
        return withSession(localSourceUrl) { session, key ->
            val sharedData = NodeBundleStore.sharedDataDir(context)
            NodeBundleStore.migrateLegacyDataIfNeeded(context, sharedData)
            val sourceRoot = File(NodeBundleStore.bundlesRoot(context), key)
            val active = File(sourceRoot, "active")
            val stored = NodeBundleStore.restorePrevious(active, sharedData)
            NodeBundleStore.pruneSourceRoot(sourceRoot)

            onDownload?.invoke()
            val staging = File(sourceRoot, "staging-" + UUID.randomUUID())
            try {
                if (!staging.mkdirs() && !staging.isDirectory) {
                    throw IOException("Unable to create Node staging directory")
                }
                val zipMd5 = streamOpener().use { raw ->
                    unzipStreamWithMd5(raw, staging, session)
                }
                val cachedZipMd5 = NodeBundleStore.readZipMd5(sourceRoot)
                if (stored != null && zipMd5.isNotEmpty() && zipMd5.equals(cachedZipMd5, ignoreCase = true) && !stored.isPending) {
                    session.check()
                    return@withSession stored
                }
                finalizeExtractedZip(sourceRoot, active, sharedData, staging, zipMd5, session)
            } finally {
                Path.clear(staging)
            }
        }
    }

    private inline fun withSession(
        source: String,
        block: (session: NodeBundle.LoadSession, key: String) -> NodeBundle,
    ): NodeBundle {
        val session = NodeBundle.LoadSession()
        val previous = ACTIVE_LOAD.getAndSet(session)
        previous?.cancel()
        return try {
            if (!NodeSourceLocator.isConfig(source)) {
                throw IOException("Node config must end with index.js.md5, index.js or .zip")
            }
            val key = NodeSourceLocator.bundleKey(source)
            val lock = SOURCE_LOCKS.computeIfAbsent(key) { Any() }
            synchronized(lock) {
                session.check()
                block(session, key)
            }
        } finally {
            ACTIVE_LOAD.compareAndSet(session, null)
        }
    }

    private fun installRemote(
        rawSource: String,
        sourceRoot: File,
        active: File,
        stored: NodeBundle?,
        sharedData: File,
        session: NodeBundle.LoadSession,
        onDownload: (() -> Unit)?,
    ): NodeBundle {
        val md5Source = NodeSourceLocator.normalizeMd5Source(rawSource)
        val indexLocation = NodeSourceLocator.companion(md5Source, "index.js")
        val configLocation = NodeSourceLocator.companion(md5Source, "index.config.js")
        val configMd5Location = NodeSourceLocator.companion(md5Source, "index.config.js.md5")
        val manifestLocation = NodeSourceLocator.companion(md5Source, NodeManifest.FILE_NAME)

        val probe = try {
            probeRemote(md5Source, configMd5Location, manifestLocation, session)
        } catch (e: Exception) {
            if (stored != null) {
                session.check()
                Timber.w(e, "远程 Node 探测失败，降级复用本地缓存")
                return stored
            }
            throw e
        }

        val trustedKey = NodeBundleStore.readTrustedKey(sourceRoot)
        if (probe.manifest == null && trustedKey.isNotEmpty()) {
            throw IOException("Signed Node bundle cannot downgrade to unsigned")
        }
        val manifest = probe.manifest?.let { NodeManifest.parse(it) }

        val configCheck = resolveCachedConfigMatch(
            stored = stored,
            sourceRoot = sourceRoot,
            probedIndexMd5 = probe.indexMd5,
            probedConfigMd5 = probe.configMd5,
            configLocation = configLocation,
            manifest = manifest,
            trustedKey = trustedKey,
            session = session,
        )
        if (configCheck.reusable && stored != null) {
            session.check()
            return stored
        }

        val staging = File(sourceRoot, "staging-" + UUID.randomUUID())
        try {
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw IOException("Unable to create Node staging directory")
            }
            val downloads = File(sourceRoot, ".downloads")
            if (NodeSourceLocator.isHttpSource(indexLocation) || NodeSourceLocator.isHttpSource(configLocation)) {
                onDownload?.invoke()
            }

            val index = copyResource(indexLocation, File(staging, "index.js"), MAX_INDEX_BYTES, downloads, session)
            if (!Crypto.equals(index, probe.indexMd5)) throw IOException("index.js MD5 mismatch")

            val stagedConfig = stageRemoteConfig(
                configLocation = configLocation,
                targetConfig = File(staging, "index.config.js"),
                preFetchedBytes = configCheck.downloadedConfigBytes,
                stored = stored,
                downloads = downloads,
                session = session,
            )
            val actualConfigMd5 = Crypto.md5(stagedConfig)
            val probedConfigMd5 = probe.configMd5
            if (probedConfigMd5 != null && !probedConfigMd5.equals(actualConfigMd5, ignoreCase = true)) {
                if (manifest != null || trustedKey.isNotEmpty()) {
                    throw IOException("index.config.js MD5 mismatch")
                }
                Timber.w(
                    "远程 index.config.js.md5 (%s) 与实际内容 (%s) 不一致，已兼容放行",
                    probedConfigMd5,
                    actualConfigMd5,
                )
            }

            val signingKey = verifyStagedManifest(manifest, index, stagedConfig, trustedKey, staging)
            NodeBundleStore.writeSidecars(staging, probe.indexMd5, actualConfigMd5)
            session.check()
            NodeBundleStore.commit(active, staging)
            NodeBundleStore.writeRemoteConfigMd5(sourceRoot, if (manifest == null) probedConfigMd5 else null)
            session.check()
            return NodeBundle(active, probe.indexMd5, actualConfigMd5, pending = true, signingKey = signingKey, sharedDataDir = sharedData)
        } finally {
            Path.clear(staging)
        }
    }

    private data class CachedConfigCheck(
        val reusable: Boolean,
        val downloadedConfigBytes: ByteArray? = null,
    )

    private fun resolveCachedConfigMatch(
        stored: NodeBundle?,
        sourceRoot: File,
        probedIndexMd5: String,
        probedConfigMd5: String?,
        configLocation: String,
        manifest: NodeManifest?,
        trustedKey: String,
        session: NodeBundle.LoadSession,
    ): CachedConfigCheck {
        if (stored == null || !stored.indexMd5.equals(probedIndexMd5, ignoreCase = true)) {
            return CachedConfigCheck(reusable = false)
        }
        if (!verifyStoredManifest(stored, manifest, trustedKey)) {
            return CachedConfigCheck(reusable = false)
        }
        if (probedConfigMd5 != null) {
            val matchesLocal = stored.configMd5.equals(probedConfigMd5, ignoreCase = true)
            val matchesStaleSidecar = manifest == null &&
                NodeBundleStore.readRemoteConfigMd5(sourceRoot).equals(probedConfigMd5, ignoreCase = true)
            return CachedConfigCheck(reusable = matchesLocal || matchesStaleSidecar)
        }

        // sidecar 缺失：尝试拉取较小的 index.config.js 比对内容；拉取失败时复用本地旧包
        val downloaded = fetchOptionalBytes(configLocation, MAX_CONFIG_BYTES.toInt(), session)
            ?: return CachedConfigCheck(reusable = true)
        val remoteMd5 = Crypto.md5(downloaded)
        return if (remoteMd5.equals(stored.configMd5, ignoreCase = true)) {
            CachedConfigCheck(reusable = true)
        } else {
            CachedConfigCheck(reusable = false, downloadedConfigBytes = downloaded)
        }
    }

    private fun stageRemoteConfig(
        configLocation: String,
        targetConfig: File,
        preFetchedBytes: ByteArray?,
        stored: NodeBundle?,
        downloads: File,
        session: NodeBundle.LoadSession,
    ): File {
        if (preFetchedBytes != null && preFetchedBytes.isNotEmpty()) {
            Path.writeAtomically(targetConfig, preFetchedBytes)
            return targetConfig
        }
        return try {
            copyResource(configLocation, targetConfig, MAX_CONFIG_BYTES, downloads, session)
        } catch (e: Exception) {
            session.check()
            if (stored != null && stored.config.isFile && stored.config.length() > 0L) {
                Timber.w(e, "下载远程 index.config.js 失败，复用本地旧配置")
                NodeBundleStore.copyFile(stored.config, targetConfig)
            } else {
                Timber.w(e, "下载远程 index.config.js 失败且无可用缓存，降级写入空配置 {}")
                Path.writeAtomically(targetConfig, "module.exports = {};\n".toByteArray(StandardCharsets.UTF_8))
            }
            targetConfig
        }
    }

    private class ProbeResult(
        val indexMd5: String,
        val configMd5: String?,
        val manifest: String?,
    )

    private fun probeRemote(
        indexMd5Url: String,
        configMd5Url: String,
        manifestUrl: String,
        session: NodeBundle.LoadSession,
    ): ProbeResult {
        val indexRef = AtomicReference<String?>()
        val configRef = AtomicReference<String?>()
        val manifestRef = AtomicReference<String?>()
        val failure = AtomicReference<Exception?>()
        val latch = CountDownLatch(3)

        fun submit(required: Boolean, block: () -> Unit) {
            PROBE_EXECUTOR.execute {
                try {
                    block()
                } catch (e: Exception) {
                    if (required) failure.compareAndSet(null, e)
                } finally {
                    latch.countDown()
                }
            }
        }

        submit(required = true) { indexRef.set(fetchMd5(indexMd5Url, session)) }
        submit(required = false) { configRef.set(fetchOptionalMd5(configMd5Url, session)) }
        submit(required = false) { manifestRef.set(fetchOptionalText(manifestUrl, MAX_MANIFEST_BYTES, session)) }

        if (!latch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            session.cancel()
            throw IOException("Node bundle probe timed out")
        }
        failure.get()?.let { throw it }
        return ProbeResult(
            indexMd5 = indexRef.get() ?: throw IOException("Empty MD5 sidecar: $indexMd5Url"),
            configMd5 = configRef.get(),
            manifest = manifestRef.get(),
        )
    }

    private fun installZipFromLocation(
        source: String,
        sourceRoot: File,
        active: File,
        stored: NodeBundle?,
        sharedData: File,
        session: NodeBundle.LoadSession,
        onDownload: (() -> Unit)?,
    ): NodeBundle {
        val isRemote = NodeSourceLocator.isHttpSource(source)
        val zipFile: File
        var tempRemoteFile: File? = null
        try {
            if (isRemote) {
                onDownload?.invoke()
                val downloads = File(sourceRoot, ".downloads").apply { if (!exists()) mkdirs() }
                tempRemoteFile = File.createTempFile("bundle-", ".zip", downloads)
                try {
                    downloadFile(source, tempRemoteFile, MAX_INDEX_BYTES, downloads, session)
                } catch (e: Exception) {
                    if (stored != null) {
                        session.check()
                        Timber.w(e, "下载远程 zip 失败，降级复用本地已有的 bundle")
                        return stored
                    }
                    throw e
                }
                zipFile = tempRemoteFile
            } else {
                val path = if (source.startsWith("file://", ignoreCase = true)) source.substring(7) else source
                zipFile = File(path)
                if (!zipFile.isFile || !zipFile.canRead()) {
                    throw IOException("无法读取本地压缩包文件: $zipFile")
                }
            }

            session.check()
            val currentZipMd5 = Crypto.md5(zipFile)
            val cachedZipMd5 = NodeBundleStore.readZipMd5(sourceRoot)
            if (stored != null && currentZipMd5.isNotEmpty() && currentZipMd5.equals(cachedZipMd5, ignoreCase = true)) {
                session.check()
                return stored
            }

            val staging = File(sourceRoot, "staging-" + UUID.randomUUID())
            try {
                if (!staging.mkdirs() && !staging.isDirectory) {
                    throw IOException("Unable to create Node staging directory")
                }
                FileInputStream(zipFile).use { fis ->
                    unzipStream(fis, staging, session)
                }
                return finalizeExtractedZip(sourceRoot, active, sharedData, staging, currentZipMd5, session)
            } finally {
                Path.clear(staging)
            }
        } finally {
            tempRemoteFile?.delete()
        }
    }

    private fun finalizeExtractedZip(
        sourceRoot: File,
        active: File,
        sharedData: File,
        staging: File,
        zipMd5: String,
        session: NodeBundle.LoadSession,
    ): NodeBundle {
        flattenNestedBundleDir(staging, MAX_FLATTEN_DEPTH)

        val index = File(staging, "index.js")
        val config = File(staging, "index.config.js")
        if (!index.isFile) throw IOException("压缩包内缺少 index.js")
        if (!config.isFile) throw IOException("压缩包内缺少 index.config.js")

        val trustedKey = NodeBundleStore.readTrustedKey(sourceRoot)
        val manifestFile = File(staging, NodeManifest.FILE_NAME)
        val manifestText = if (manifestFile.isFile) Path.read(manifestFile) else null
        if (manifestText == null && trustedKey.isNotEmpty()) {
            throw IOException("Signed Node bundle cannot downgrade to unsigned")
        }
        val manifest = manifestText?.let { NodeManifest.parse(it) }

        val indexMd5File = File(staging, "index.js.md5")
        val indexMd5 = if (indexMd5File.isFile) {
            val parsed = NodeSourceLocator.parseMd5(Path.read(indexMd5File))
            if (!Crypto.equals(index, parsed)) throw IOException("index.js MD5 mismatch")
            parsed
        } else {
            Crypto.md5(index)
        }

        val configMd5File = File(staging, "index.config.js.md5")
        val configMd5 = if (configMd5File.isFile) {
            val parsed = runCatching { NodeSourceLocator.parseMd5(Path.read(configMd5File)) }.getOrNull()
            if (parsed != null && Crypto.equals(config, parsed)) {
                parsed
            } else {
                if (manifest != null || trustedKey.isNotEmpty()) {
                    throw IOException("index.config.js MD5 mismatch")
                }
                Timber.w("ZIP 内 index.config.js.md5 与实际内容不一致，已按实际文件重新计算")
                Crypto.md5(config)
            }
        } else {
            Crypto.md5(config)
        }

        val signingKey = verifyStagedManifest(manifest, index, config, trustedKey, staging)
        NodeBundleStore.writeSidecars(staging, indexMd5, configMd5)
        session.check()
        NodeBundleStore.commit(active, staging)
        NodeBundleStore.writeZipMd5(sourceRoot, zipMd5)
        session.check()
        return NodeBundle(active, indexMd5, configMd5, pending = true, signingKey = signingKey, sharedDataDir = sharedData)
    }

    private fun unzipStreamWithMd5(raw: InputStream, targetDir: File, session: NodeBundle.LoadSession): String {
        val md5 = MessageDigest.getInstance("MD5")
        val digestStream = DigestInputStream(BufferedInputStream(raw, 64 * 1024), md5)
        // ZipInputStream.close() 会级联关闭传入流；套一层不透传 close 的包装，
        // 保证在 Android ParcelFileDescriptor.AutoCloseInputStream 上也能安全读完尾部中央目录并由外层 use 关闭。
        unzipStream(NonClosingInputStream(digestStream), targetDir, session)
        val remainder = ByteArray(4096)
        while (digestStream.read(remainder) != -1) {
            session.check()
        }
        val digest = md5.digest()
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xff))
        }
        return hex.toString()
    }

    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() = Unit
    }

    private fun unzipStream(input: InputStream, targetDir: File, session: NodeBundle.LoadSession) {
        val canonicalTarget = targetDir.canonicalFile
        var totalBytes = 0L
        val buffer = ByteArray(64 * 1024)

        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                session.check()
                val destFile = File(targetDir, entry.name).canonicalFile
                if (!destFile.path.startsWith(canonicalTarget.path + File.separator) && destFile != canonicalTarget) {
                    throw SecurityException("Zip entry is outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (!destFile.isDirectory && !destFile.mkdirs() && !destFile.isDirectory) {
                        throw IOException("Failed to create dir: $destFile")
                    }
                } else {
                    val parent = destFile.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                        throw IOException("Failed to create dir: $parent")
                    }
                    FileOutputStream(destFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            session.check()
                            totalBytes += len
                            if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                                throw IOException("Node zip bundle exceeds size limit (64MB)")
                            }
                            fos.write(buffer, 0, len)
                        }
                        fos.flush()
                        fos.fd.sync()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 最多向下探测 [maxDepth] 层子目录寻找 `index.js`，将其所在目录展平至 [root]。 */
    private fun flattenNestedBundleDir(root: File, maxDepth: Int) {
        Path.clear(File(root, "__MACOSX"))
        if (File(root, "index.js").isFile) return
        val candidate = findBundleCandidateDir(root, maxDepth) ?: return
        val children = candidate.listFiles() ?: return
        for (child in children) {
            val target = File(root, child.name)
            if (target.exists()) Path.clear(target)
            if (!child.renameTo(target)) {
                NodeBundleStore.copyDirectory(child, target)
                Path.clear(child)
            }
        }
        // 删除原嵌套目录及仅含该嵌套目录的空壳父目录
        var cursor: File? = candidate
        while (cursor != null && cursor != root) {
            val parent = cursor.parentFile
            if (cursor == candidate || cursor.listFiles().isNullOrEmpty()) {
                Path.clear(cursor)
            } else {
                break
            }
            cursor = parent
        }
    }

    private fun findBundleCandidateDir(root: File, maxDepth: Int): File? {
        var level = listOf(root)
        repeat(maxDepth) {
            val next = level.flatMap { dir ->
                dir.listFiles { f -> f.isDirectory && f.name != "__MACOSX" }?.toList().orEmpty()
            }
            if (next.isEmpty()) return null
            next.firstOrNull { File(it, "index.js").isFile && File(it, "index.config.js").isFile }?.let { return it }
            next.firstOrNull { File(it, "index.js").isFile }?.let { return it }
            level = next
        }
        return null
    }

    private fun verifyStagedManifest(
        manifest: NodeManifest?,
        index: File,
        config: File,
        trustedKey: String,
        staging: File,
    ): String {
        if (manifest == null) return ""
        manifest.verify(index, config, trustedKey)
        val signingKey = manifest.fingerprint()
        Path.writeAtomically(File(staging, NodeManifest.FILE_NAME), manifest.raw().toByteArray(StandardCharsets.UTF_8))
        return signingKey
    }

    private fun verifyStoredManifest(stored: NodeBundle, manifest: NodeManifest?, trustedKey: String): Boolean {
        if (manifest == null) return trustedKey.isEmpty()
        return try {
            manifest.verify(stored.index, stored.config, trustedKey)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun fetchMd5(location: String, session: NodeBundle.LoadSession): String {
        openStream(location, session, optional = false).use { stream ->
            requireNotNull(stream) { "MD5 stream is null" }
            val text = readBytesLimited(stream, MAX_MD5_BYTES, session, "MD5 sidecar").toString(StandardCharsets.UTF_8)
            return NodeSourceLocator.parseMd5(text)
        }
    }

    private fun fetchOptionalMd5(location: String, session: NodeBundle.LoadSession): String? {
        val stream = openStream(location, session, optional = true) ?: run {
            Timber.w("远程 index.config.js.md5 不存在或不可达，将尝试降级放行: %s", location)
            return null
        }
        return stream.use { s ->
            try {
                val text = readBytesLimited(s, MAX_MD5_BYTES, session, "Config MD5 sidecar").toString(StandardCharsets.UTF_8)
                NodeSourceLocator.parseMd5(text)
            } catch (e: Exception) {
                session.check()
                Timber.w(e, "远程 index.config.js.md5 格式无效，将尝试降级放行: %s", location)
                null
            }
        }
    }

    private fun fetchOptionalText(location: String, limit: Int, session: NodeBundle.LoadSession): String? {
        val bytes = fetchOptionalBytes(location, limit, session) ?: return null
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun fetchOptionalBytes(location: String, limit: Int, session: NodeBundle.LoadSession): ByteArray? {
        val stream = openStream(location, session, optional = true) ?: return null
        return stream.use { s ->
            try {
                readBytesLimited(s, limit, session, "Optional resource")
            } catch (e: Exception) {
                session.check()
                Timber.w(e, "读取可选远程资源失败: %s", location)
                null
            }
        }
    }

    private fun readBytesLimited(input: InputStream, limit: Int, session: NodeBundle.LoadSession, name: String): ByteArray {
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(1024)
            var total = 0
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                session.check()
                total += count
                if (total > limit) throw IOException("$name is too large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun copyResource(
        location: String,
        target: File,
        maxBytes: Long,
        downloads: File,
        session: NodeBundle.LoadSession,
    ): File {
        session.check()
        if (NodeSourceLocator.isHttpSource(location)) {
            return downloadFile(location, target, maxBytes, downloads, session)
        }
        openStream(location, session, optional = false).use { resource ->
            requireNotNull(resource) { "Resource is null: $location" }
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Unable to create Node staging directory")
            }
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var count: Int
                while (resource.read(buffer).also { count = it } != -1) {
                    session.check()
                    total += count
                    if (maxBytes > 0 && total > maxBytes) throw IOException("Node bundle file is too large")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("Empty Node bundle file")
                output.flush()
                output.fd.sync()
            }
            return target
        }
    }

    private fun downloadFile(
        location: String,
        target: File,
        maxBytes: Long,
        downloads: File,
        session: NodeBundle.LoadSession,
    ): File {
        val request = Request.Builder().url(location).build()
        val call = CLIENT.newCall(request)
        session.attach(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed HTTP ${response.code}: $location")
                val body = response.body ?: throw IOException("Empty response body: $location")
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                    throw IOException("Unable to create directory: $parent")
                }
                val temp = File.createTempFile("down-", ".tmp", if (downloads.isDirectory || downloads.mkdirs()) downloads else parent)
                try {
                    temp.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var count: Int
                            while (input.read(buffer).also { count = it } != -1) {
                                session.check()
                                total += count
                                if (maxBytes > 0 && total > maxBytes) throw IOException("File too large (> $maxBytes bytes)")
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
                    session.check()
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        NodeBundleStore.copyFile(temp, target)
                        temp.delete()
                    }
                    return target
                } finally {
                    if (temp.exists()) temp.delete()
                }
            }
        } finally {
            session.detach(call)
        }
    }

    private fun openStream(location: String, session: NodeBundle.LoadSession, optional: Boolean): InputStream? {
        session.check()
        if (NodeSourceLocator.isHttpSource(location)) {
            val request = Request.Builder().url(location).build()
            val call = PROBE_CLIENT.newCall(request)
            session.attach(call)
            return try {
                val response = call.execute()
                if (optional && (response.code == 404 || response.code == 204)) {
                    response.close()
                    return null
                }
                if (!response.isSuccessful) {
                    response.close()
                    if (optional) return null
                    throw IOException("HTTP ${response.code} for $location")
                }
                FilterInputStreamWrapper(response.body.byteStream(), response, call, session)
            } catch (e: IOException) {
                session.detach(call)
                if (optional) return null
                throw e
            }
        }
        val file = File(location.replace("file://", ""))
        if (file.isFile && file.canRead()) return FileInputStream(file)
        if (optional) return null
        throw IOException("Unable to read local file: $file")
    }

    private class FilterInputStreamWrapper(
        private val delegate: InputStream,
        private val response: Response,
        private val call: Call,
        private val session: NodeBundle.LoadSession,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            try {
                delegate.close()
            } finally {
                response.close()
                session.detach(call)
            }
        }
    }
}
