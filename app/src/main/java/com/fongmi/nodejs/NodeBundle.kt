package com.fongmi.nodejs

import android.content.Context
import com.mewbox.tv.api.net.SSLCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

class NodeBundle internal constructor(
    val root: File,
    val indexMd5: String,
    val configMd5: String,
    private var pending: Boolean = false,
    private val signingKey: String = "",
) {
    val index: File = File(root, "index.js")
    val config: File = File(root, "index.config.js")
    val data: File
        get() {
            val dir = File(root.parentFile, "data")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    companion object {
        private val MD5 = Pattern.compile("(?i)(?:^|\\s)([a-f0-9]{32})(?:\\s|$)")
        private const val MAX_INDEX_BYTES = 32L * 1024 * 1024
        private const val MAX_CONFIG_BYTES = 2L * 1024 * 1024
        private const val MAX_MD5_BYTES = 1024
        private const val MAX_MANIFEST_BYTES = 32 * 1024
        private const val MAX_ZIP_TOTAL_BYTES = 64L * 1024 * 1024
        private const val ZIP_MD5_FILE = "zip.md5"
        private const val PENDING = ".pending"
        private const val TRUSTED_KEY = "trusted-key.sha256"
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
         * 订阅源通常是局域网/自建小服务，主机不在线时表现为丢包而非 RST，
         * 所以这里禁掉 OkHttp 的自动重试，把单次等待压到 3s。
         */
        private val PROBE_CLIENT = SSLCompat.apply(OkHttpClient.Builder())
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /** 三个 sidecar 并发探测，避免串行累加超时 */
        private val PROBE_EXECUTOR = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "node-bundle-probe").apply { isDaemon = true }
        }
        private val SOURCE_LOCKS = ConcurrentHashMap<String, Any>()
        private val ACTIVE_LOAD = AtomicReference<LoadSession?>()

        @JvmStatic
        fun isZip(source: String?): Boolean {
            if (source == null) return false
            var value = source.split(Regex("[?#]"), 2)[0]
            try {
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
            }
            return value.lowercase(Locale.ROOT).endsWith(".zip")
        }

        @JvmStatic
        fun isConfig(url: String?): Boolean {
            if (url == null || url.regionMatches(0, "content://", 0, 10, ignoreCase = true)) return false
            var value = url.split(Regex("[?#]"), 2)[0]
            try {
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
            }
            val lower = value.lowercase(Locale.ROOT)
            return lower.endsWith("/index.js.md5") ||
                lower.equals("index.js.md5", ignoreCase = true) ||
                lower.endsWith(".zip")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun prepare(context: Context, source: String, onDownload: (() -> Unit)? = null): NodeBundle {
            val session = LoadSession()
            val previous = ACTIVE_LOAD.getAndSet(session)
            previous?.cancel()
            return try {
                if (!isConfig(source)) throw IOException("Node config must end with index.js.md5 or .zip")
                val key = Crypto.sha256(source)
                val lock = SOURCE_LOCKS.computeIfAbsent(key) { Any() }
                synchronized(lock) {
                    session.check()
                    prepareInternal(context, source, key, session, onDownload)
                }
            } finally {
                ACTIVE_LOAD.compareAndSet(session, null)
            }
        }

        @JvmStatic
        fun restore(context: Context, source: String): NodeBundle? {
            if (!isConfig(source)) return null
            val key = Crypto.sha256(source)
            val sourceRoot = File(context.filesDir, "nodejs/bundles/$key")
            val active = File(sourceRoot, "active")
            return restorePrevious(active)
        }

        @JvmStatic
        fun restoreLatest(context: Context): NodeBundle? {
            val bundlesDir = File(context.filesDir, "nodejs/bundles")
            if (!bundlesDir.isDirectory) return null
            val candidates = bundlesDir.listFiles { file -> file.isDirectory } ?: return null
            return candidates
                .sortedByDescending { it.lastModified() }
                .firstNotNullOfOrNull { dir ->
                    restorePrevious(File(dir, "active"))
                }
        }

        private fun prepareInternal(
            context: Context,
            source: String,
            key: String,
            session: LoadSession,
            onDownload: (() -> Unit)? = null,
        ): NodeBundle {
            val sourceRoot = File(context.filesDir, "nodejs/bundles/$key")
            val active = File(sourceRoot, "active")
            val stored = restorePrevious(active)
            pruneSourceRoot(sourceRoot)

            if (isZip(source)) {
                return prepareZipInternal(source, sourceRoot, active, stored, session, onDownload)
            }

            val indexLocation = companion(source, "index.js")
            val configLocation = companion(source, "index.config.js")
            val configMd5Location = companion(source, "index.config.js.md5")
            val manifestLocation = companion(source, NodeManifest.FILE_NAME)
            val indexMd5: String
            val configMd5: String
            val manifestText: String?
            try {
                val probe = probe(source, configMd5Location, manifestLocation, session)
                indexMd5 = probe.indexMd5
                configMd5 = probe.configMd5
                manifestText = probe.manifest
            } catch (e: Exception) {
                // 网络异常或超时时，若本地已有之前正常校验通过的缓存 bundle，则降级复用
                if (stored != null) {
                    session.check()
                    return stored
                }
                throw e
            }
            val trustedKey = readTrustedKey(sourceRoot)
            if (manifestText == null && trustedKey.isNotEmpty()) {
                throw IOException("Signed Node bundle cannot downgrade to unsigned")
            }
            val manifest = if (manifestText == null) null else NodeManifest.parse(manifestText)
            if (stored != null && stored.matches(indexMd5, configMd5) && verifyStoredManifest(stored, manifest, trustedKey)) {
                session.check()
                return stored
            }
            val staging = File(sourceRoot, "staging-" + UUID.randomUUID())
            try {
                if (!staging.mkdirs() && !staging.isDirectory) throw IOException("Unable to create Node staging directory")
                val downloads = File(sourceRoot, ".downloads")
                // 只有本地缓存失效、真的需要取远程资源包时才通知“下载中”
                if (indexLocation.startsWith("http") || configLocation.startsWith("http")) onDownload?.invoke()
                val index = copy(indexLocation, File(staging, "index.js"), MAX_INDEX_BYTES, downloads, session)
                val config = copy(configLocation, File(staging, "index.config.js"), MAX_CONFIG_BYTES, downloads, session)
                if (!Crypto.equals(index, indexMd5)) throw IOException("index.js MD5 mismatch")
                if (!Crypto.equals(config, configMd5)) throw IOException("index.config.js MD5 mismatch")
                val signingKey = verifyStagedManifest(manifest, index, config, trustedKey, staging)
                writeSidecars(staging, indexMd5, configMd5)
                session.check()
                commit(active, staging)
                session.check()
                return NodeBundle(active, indexMd5, configMd5, pending = true, signingKey = signingKey)
            } finally {
                Path.clear(staging)
            }
        }

        @JvmStatic
        fun cancelLoad() {
            ACTIVE_LOAD.getAndSet(null)?.cancel()
        }

        @JvmStatic
        @Throws(IOException::class)
        fun companion(source: String, name: String): String {
            var end = source.length
            val query = source.indexOf('?')
            val fragment = source.indexOf('#')
            if (query >= 0) end = minOf(end, query)
            if (fragment >= 0) end = minOf(end, fragment)
            val path = source.substring(0, end)
            val slash = path.lastIndexOf('/')
            if (slash < 0) throw IOException("Node config URL has no parent path")
            return path.substring(0, slash + 1) + name + source.substring(end)
        }

        /**
         * 并发探测三个 sidecar（index.js.md5 / index.config.js.md5 / index.manifest.json）。
         *
         * 原先串行执行，单次 connect 30s 且 OkHttp 默认重试一次，订阅源不可达时
         * 最坏要白等 180s 才走到降级分支 —— 这就是冷启动卡两三分钟的根源。
         * 改并发 + 探测专用短超时后，最坏耗时收敛到 [PROBE_TIMEOUT_SECONDS] 以内。
         */
        private fun probe(
            source: String,
            configLocation: String,
            manifestLocation: String,
            session: LoadSession,
        ): Probe {
            val index = AtomicReference<String?>()
            val config = AtomicReference<String?>()
            val manifest = AtomicReference<String?>()
            val failure = AtomicReference<Exception?>()
            val latch = CountDownLatch(3)
            fun submit(block: () -> Unit) {
                PROBE_EXECUTOR.execute {
                    try {
                        block()
                    } catch (e: Exception) {
                        failure.compareAndSet(null, e)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            submit { index.set(fetchMd5(source, session)) }
            submit { config.set(fetchMd5(configLocation, session)) }
            submit { manifest.set(fetchOptional(manifestLocation, MAX_MANIFEST_BYTES, session)) }
            if (!latch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                session.cancel()
                throw IOException("Node bundle probe timed out")
            }
            failure.get()?.let { throw it }
            return Probe(
                index.get() ?: throw IOException("Empty MD5 sidecar: $source"),
                config.get() ?: throw IOException("Empty MD5 sidecar: $configLocation"),
                manifest.get(),
            )
        }

        private class Probe(val indexMd5: String, val configMd5: String, val manifest: String?)

        private fun fetchMd5(location: String, session: LoadSession): String {
            open(location, session, false).use { stream ->
                requireNotNull(stream) { "MD5 stream is null" }
                return parseMd5(readLimited(stream, MAX_MD5_BYTES, session, "MD5 sidecar"))
            }
        }

        private fun fetchOptional(location: String, limit: Int, session: LoadSession): String? {
            val stream = open(location, session, true) ?: return null
            stream.use {
                return readLimited(it, limit, session, "Node manifest")
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun parseMd5(text: String?): String {
            val matcher = MD5.matcher(text?.trim().orEmpty())
            if (!matcher.find()) throw IOException("Invalid MD5 sidecar: $text")
            return matcher.group(1)?.lowercase(Locale.ROOT) ?: throw IOException("Invalid MD5 match")
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

        private fun writeSidecars(staging: File, indexMd5: String, configMd5: String) {
            Path.writeAtomically(File(staging, "index.js.md5"), "$indexMd5\n".toByteArray(StandardCharsets.US_ASCII))
            Path.writeAtomically(File(staging, "index.config.js.md5"), "$configMd5\n".toByteArray(StandardCharsets.US_ASCII))
            Path.writeAtomically(File(staging, PENDING), "$indexMd5:$configMd5".toByteArray(StandardCharsets.US_ASCII))
        }

        private fun readLimited(input: InputStream, limit: Int, session: LoadSession, name: String): String {
            input.use { stream ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(512)
                    var total = 0
                    var count: Int
                    while (stream.read(buffer).also { count = it } != -1) {
                        session.check()
                        total += count
                        if (total > limit) throw IOException("$name is too large")
                        output.write(buffer, 0, count)
                    }
                    return output.toString(StandardCharsets.UTF_8.name())
                }
            }
        }

        private fun copy(
            location: String,
            target: File,
            maxBytes: Long,
            downloads: File,
            session: LoadSession,
        ): File {
            session.check()
            if (location.startsWith("http://") || location.startsWith("https://")) {
                return downloadFile(location, target, maxBytes, downloads, session)
            }
            open(location, session, false).use { resource ->
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
            session: LoadSession,
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
                            copyFile(temp, target)
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

        private fun open(location: String, session: LoadSession, optional: Boolean): InputStream? {
            session.check()
            if (location.startsWith("http://") || location.startsWith("https://")) {
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
                    val body = response.body
                    FilterInputStreamWrapper(body.byteStream(), response, call, session)
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

        /**
         * 提交新资源包：先把旧 active 挪到 previous 备份，再把新包放上去。
         *
         * 不做备份的话，一个「MD5 和签名都对、但运行时会崩」的包会把唯一可用的
         * 缓存直接顶掉，用户下次冷启动就彻底没包可用。搬到一半失败时要把
         * previous 搬回来，保证 active 始终是完整可读的。
         */
        internal fun commit(active: File, staging: File) {
            val parent = active.parentFile ?: throw IOException("Node bundle has no parent directory")
            val previous = File(parent, "previous")
            Path.clear(previous)
            val hadActive = active.exists()
            if (hadActive) moveOrCopy(active, previous)
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

        /** 优先 rename（同分区零拷贝），失败再退化成复制 */
        private fun moveOrCopy(from: File, to: File) {
            if (!from.renameTo(to)) copyDirectory(from, to)
            Path.clear(from)
        }

        internal fun readBundle(root: File): NodeBundle? {
            if (!root.isDirectory) return null
            val index = File(root, "index.js")
            val config = File(root, "index.config.js")
            if (!index.isFile || !config.isFile) return null
            val indexMd5 = Crypto.md5(index)
            val configMd5 = Crypto.md5(config)
            if (indexMd5.isEmpty() || configMd5.isEmpty()) return null
            return NodeBundle(root, indexMd5, configMd5)
        }

        private fun copyDirectory(source: File, target: File) {
            if (source.isDirectory) {
                if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create dir: $target")
                source.listFiles()?.forEach { file ->
                    copyDirectory(file, File(target, file.name))
                }
            } else {
                copyFile(source, target)
            }
        }

        private fun copyFile(inStream: File, outStream: File) {
            FileInputStream(inStream).use { fis ->
                FileOutputStream(outStream).use { fos ->
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

        /**
         * 读取已落地的资源包。active 不完整时（上一次 commit 搬到一半崩了、
         * 或新包本身有问题）自动用 previous 顶上，避免直接无包可用。
         */
        internal fun restorePrevious(active: File): NodeBundle? {
            readBundle(active)?.let { return it }
            val parent = active.parentFile ?: return null
            val previous = File(parent, "previous")
            val fallback = readBundle(previous) ?: return null
            Timber.i("Node bundle active 不完整，回退到 previous 版本")
            Path.clear(active)
            moveOrCopy(previous, active)
            return readBundle(active)
        }

        private fun pruneSourceRoot(sourceRoot: File) {
            sourceRoot.listFiles()?.forEach { file ->
                if (file.name.startsWith("staging-") || file.name == ".downloads") {
                    Path.clear(file)
                }
            }
        }

        private fun readTrustedKey(sourceRoot: File): String {
            val file = File(sourceRoot, TRUSTED_KEY)
            if (!file.isFile) return ""
            return try {
                Path.read(file).trim()
            } catch (_: IOException) {
                ""
            }
        }

        private fun prepareZipInternal(
            source: String,
            sourceRoot: File,
            active: File,
            stored: NodeBundle?,
            session: LoadSession,
            onDownload: (() -> Unit)? = null,
        ): NodeBundle {
            val isRemote = source.startsWith("http://") || source.startsWith("https://")
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
                        // 网络异常时，若本地已有之前正常校验通过的缓存 bundle，则降级复用
                        if (stored != null) {
                            session.check()
                            Timber.w(e, "下载远程 zip 失败，降级复用本地已有的 bundle")
                            return stored
                        }
                        throw e
                    }
                    zipFile = tempRemoteFile
                } else {
                    val path = if (source.startsWith("file://", ignoreCase = true)) {
                        source.substring(7)
                    } else {
                        source
                    }
                    zipFile = File(path)
                    if (!zipFile.isFile || !zipFile.canRead()) {
                        throw IOException("无法读取本地压缩包文件: $zipFile")
                    }
                }

                session.check()
                val currentZipMd5 = Crypto.md5(zipFile)
                val cachedZipMd5 = readZipMd5(sourceRoot)

                // 若 zip 文件的 MD5 未变化且已有正常校验通过的缓存 bundle，则直接复用
                if (stored != null && currentZipMd5.isNotEmpty() && currentZipMd5.equals(cachedZipMd5, ignoreCase = true)) {
                    session.check()
                    return stored
                }

                val staging = File(sourceRoot, "staging-" + UUID.randomUUID())
                try {
                    if (!staging.mkdirs() && !staging.isDirectory) {
                        throw IOException("Unable to create Node staging directory")
                    }

                    unzipToDirectory(zipFile, staging, session)
                    flattenSingleSubdirectoryIfPresent(staging)

                    val index = File(staging, "index.js")
                    val config = File(staging, "index.config.js")
                    if (!index.isFile) throw IOException("压缩包内缺少 index.js")
                    if (!config.isFile) throw IOException("压缩包内缺少 index.config.js")

                    val indexMd5File = File(staging, "index.js.md5")
                    val indexMd5 = if (indexMd5File.isFile) {
                        val parsed = parseMd5(Path.read(indexMd5File))
                        if (!Crypto.equals(index, parsed)) throw IOException("index.js MD5 mismatch")
                        parsed
                    } else {
                        Crypto.md5(index).also {
                            Path.writeAtomically(indexMd5File, "$it\n".toByteArray(StandardCharsets.US_ASCII))
                        }
                    }

                    val configMd5File = File(staging, "index.config.js.md5")
                    val configMd5 = if (configMd5File.isFile) {
                        val parsed = parseMd5(Path.read(configMd5File))
                        if (!Crypto.equals(config, parsed)) throw IOException("index.config.js MD5 mismatch")
                        parsed
                    } else {
                        Crypto.md5(config).also {
                            Path.writeAtomically(configMd5File, "$it\n".toByteArray(StandardCharsets.US_ASCII))
                        }
                    }

                    val trustedKey = readTrustedKey(sourceRoot)
                    val manifestFile = File(staging, NodeManifest.FILE_NAME)
                    val manifestText = if (manifestFile.isFile) Path.read(manifestFile) else null
                    if (manifestText == null && trustedKey.isNotEmpty()) {
                        throw IOException("Signed Node bundle cannot downgrade to unsigned")
                    }
                    val manifest = if (manifestText == null) null else NodeManifest.parse(manifestText)
                    val signingKey = verifyStagedManifest(manifest, index, config, trustedKey, staging)

                    writeSidecars(staging, indexMd5, configMd5)
                    session.check()
                    commit(active, staging)

                    if (currentZipMd5.isNotEmpty()) {
                        Path.writeAtomically(File(sourceRoot, ZIP_MD5_FILE), currentZipMd5.toByteArray(StandardCharsets.US_ASCII))
                    }

                    session.check()
                    return NodeBundle(active, indexMd5, configMd5, pending = true, signingKey = signingKey)
                } finally {
                    Path.clear(staging)
                }
            } finally {
                tempRemoteFile?.delete()
            }
        }

        private fun unzipToDirectory(zipFile: File, targetDir: File, session: LoadSession) {
            val canonicalTarget = targetDir.canonicalFile
            var totalBytes = 0L
            val buffer = ByteArray(64 * 1024)

            ZipInputStream(FileInputStream(zipFile)).use { zis ->
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

        private fun flattenSingleSubdirectoryIfPresent(dir: File) {
            if (File(dir, "index.js").isFile) return
            val subDirs = dir.listFiles { f -> f.isDirectory } ?: return
            val candidate = subDirs.firstOrNull { File(it, "index.js").isFile } ?: return
            val children = candidate.listFiles() ?: return
            for (child in children) {
                val target = File(dir, child.name)
                if (target.exists()) Path.clear(target)
                if (!child.renameTo(target)) {
                    copyDirectory(child, target)
                    Path.clear(child)
                }
            }
            Path.clear(candidate)
        }

        private fun readZipMd5(sourceRoot: File): String {
            val file = File(sourceRoot, ZIP_MD5_FILE)
            if (!file.isFile) return ""
            return try {
                Path.read(file).trim()
            } catch (_: IOException) {
                ""
            }
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

        @JvmStatic
        fun prune(context: Context, sources: Iterable<String>?, currentSource: String?) {
            val root = File(context.filesDir, "nodejs/bundles")
            if (!root.isDirectory) return
            val validKeys = HashSet<String>()
            sources?.forEach { source ->
                if (isConfig(source)) validKeys.add(Crypto.sha256(source))
            }
            if (currentSource != null && isConfig(currentSource)) {
                validKeys.add(Crypto.sha256(currentSource))
            }
            root.listFiles()?.forEach { bundleDir ->
                if (bundleDir.isDirectory && !validKeys.contains(bundleDir.name)) {
                    Path.clear(bundleDir)
                }
            }
        }
    }

    @Synchronized
    fun accept() {
        if (!pending) return
        File(root, PENDING).delete()
        if (signingKey.isNotEmpty()) {
            try {
                Path.writeAtomically(File(root.parentFile, TRUSTED_KEY), "$signingKey\n".toByteArray(StandardCharsets.US_ASCII))
            } catch (_: IOException) {
            }
        }
        pending = false
    }

    fun matches(indexMd5: String, configMd5: String): Boolean {
        return this.indexMd5.equals(indexMd5, ignoreCase = true) && this.configMd5.equals(configMd5, ignoreCase = true)
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

    private class FilterInputStreamWrapper(
        private val delegate: InputStream,
        private val response: Response,
        private val call: Call,
        private val session: LoadSession,
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
