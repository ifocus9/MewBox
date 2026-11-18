package com.mewbox.tv.api.config

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fongmi.nodejs.Crypto
import com.fongmi.nodejs.Path
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Config
import com.mewbox.tv.db.ConfigDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地订阅 SAF 流式导入器：
 * 通过魔数探测（ZIP / VOD JSON / HTML）将选中文档直落应用私有存储，
 * 自动写入默认文件名至 `Config.name`，并支持按 `Config.id` 原位替换保留历史与回滚链。
 */
@Singleton
class LocalSourceImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vodConfig: VodConfig,
    private val configDao: ConfigDao,
) {

    /** 从系统 SAF [uri] 流式读取并导入本地订阅包 / 配置 / 网页源。 */
    suspend fun importFromUri(
        uri: Uri,
        customName: String = "",
        replaceOldUrl: String = "",
        existingConfigId: String = "",
    ): VodConfig.ImportOutcome {
        val displayName = readDisplayName(uri).ifBlank { "本地订阅" }
        return importFromStream(
            displayName = displayName,
            customName = customName,
            streamOpener = {
                context.contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("无法读取所选本地文件")
            },
            replaceOldUrl = replaceOldUrl,
            existingConfigId = existingConfigId,
        )
    }

    /** 基于任意输入流提供器的核心导入入口（兼顾 SAF 与纯 JVM 单测）。 */
    internal suspend fun importFromStream(
        displayName: String,
        customName: String,
        streamOpener: () -> InputStream,
        replaceOldUrl: String = "",
        existingConfigId: String = "",
    ): VodConfig.ImportOutcome = withContext(Dispatchers.IO) {
        val cleanDisplay = displayName.ifBlank { "本地订阅" }
        val resolvedName = customName.trim().ifBlank { cleanDisplay }
        val oldConfig = if (replaceOldUrl.isNotBlank()) configDao.find(replaceOldUrl) else null
        val targetId = oldConfig?.id?.takeIf { it.isNotBlank() }
            ?: existingConfigId.ifBlank { Config.newId() }

        val isZip = sniffZipMagic(streamOpener)
        if (isZip) {
            return@withContext importLocalNodeZip(
                targetId = targetId,
                resolvedName = resolvedName,
                streamOpener = streamOpener,
                replaceOldUrl = replaceOldUrl,
            )
        }
        if (cleanDisplay.endsWith(".zip", ignoreCase = true)) {
            throw IOException("无法识别本地压缩包格式，请确认文件为有效 ZIP")
        }

        val text = readTextBounded(streamOpener)
        val sites = ResultParser.parseSites(text)
        if (sites.isNotEmpty()) {
            return@withContext importLocalVodJson(
                targetId = targetId,
                resolvedName = resolvedName,
                content = text,
                replaceOldUrl = replaceOldUrl,
            )
        }

        if (
            ConfigHtmlDetector.isHtmlResponse("", text) ||
            cleanDisplay.endsWith(".html", ignoreCase = true) ||
            cleanDisplay.endsWith(".htm", ignoreCase = true)
        ) {
            return@withContext importLocalHtml(
                targetId = targetId,
                resolvedName = resolvedName,
                content = text,
                replaceOldUrl = replaceOldUrl,
            )
        }

        throw IllegalArgumentException("未能识别该本地文件（支持 .zip Node包、.json 配置或 .html 网页）")
    }

    private suspend fun importLocalNodeZip(
        targetId: String,
        resolvedName: String,
        streamOpener: () -> InputStream,
        replaceOldUrl: String,
    ): VodConfig.ImportOutcome {
        val localSourceUrl = "${VodConfig.LOCAL_NODE_SCHEME}$targetId"
        val bundleDir = File(context.filesDir, "nodejs/bundles/${Crypto.sha256(localSourceUrl)}")
        val preExisted = bundleDir.exists()
        return try {
            vodConfig.importLocalNodeStream(
                localSourceUrl = localSourceUrl,
                name = resolvedName,
                configId = targetId,
                streamOpener = streamOpener,
                replaceUrl = replaceOldUrl,
            )
        } catch (e: Exception) {
            if (!preExisted) {
                Path.clear(bundleDir)
            }
            throw e
        }
    }

    private suspend fun importLocalVodJson(
        targetId: String,
        resolvedName: String,
        content: String,
        replaceOldUrl: String,
    ): VodConfig.ImportOutcome {
        val targetFile = VodConfig.vodConfigFile(context, targetId)
        val hadFile = targetFile.isFile
        val backupBytes = if (hadFile) runCatching { targetFile.readBytes() }.getOrNull() else null
        val targetUrl = "${VodConfig.LOCAL_VOD_SCHEME}$targetId"

        Path.writeAtomically(targetFile, content.toByteArray(StandardCharsets.UTF_8))
        return try {
            val count = vodConfig.import(
                input = targetUrl,
                name = resolvedName,
                configId = targetId,
                replaceUrl = replaceOldUrl,
            )
            VodConfig.ImportOutcome(siteCount = count, loadedUrl = targetUrl, isHtml = false)
        } catch (e: Exception) {
            restoreOrDeleteFile(targetFile, hadFile, backupBytes)
            throw e
        }
    }

    private suspend fun importLocalHtml(
        targetId: String,
        resolvedName: String,
        content: String,
        replaceOldUrl: String,
    ): VodConfig.ImportOutcome {
        val targetFile = VodConfig.htmlConfigFile(context, targetId)
        val hadFile = targetFile.isFile
        val backupBytes = if (hadFile) runCatching { targetFile.readBytes() }.getOrNull() else null
        val targetUrl = "file://${targetFile.absolutePath}"

        Path.writeAtomically(targetFile, content.toByteArray(StandardCharsets.UTF_8))
        return try {
            vodConfig.import(
                input = targetUrl,
                name = resolvedName,
                configId = targetId,
                replaceUrl = replaceOldUrl,
            )
            VodConfig.ImportOutcome(siteCount = 0, loadedUrl = targetUrl, isHtml = true)
        } catch (e: Exception) {
            restoreOrDeleteFile(targetFile, hadFile, backupBytes)
            throw e
        }
    }

    private fun restoreOrDeleteFile(file: File, hadBefore: Boolean, backup: ByteArray?) {
        if (hadBefore && backup != null) {
            runCatching { Path.writeAtomically(file, backup) }
        } else {
            file.delete()
        }
    }

    private fun sniffZipMagic(streamOpener: () -> InputStream): Boolean {
        return streamOpener().use { input ->
            val header = ByteArray(4)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset < 4) return false
            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            val b2 = header[2].toInt() and 0xFF
            val b3 = header[3].toInt() and 0xFF
            b0 == 0x50 && b1 == 0x4B && (
                (b2 == 0x03 && b3 == 0x04) ||
                    (b2 == 0x05 && b3 == 0x06) ||
                    (b2 == 0x07 && b3 == 0x08)
                )
        }
    }

    private fun readTextBounded(streamOpener: () -> InputStream): String {
        return streamOpener().use { input ->
            ByteArrayOutputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                var count: Int
                while (input.read(buf).also { count = it } != -1) {
                    total += count
                    if (total > MAX_TEXT_IMPORT_BYTES) {
                        throw IOException("本地文本配置文件过大（上限 16MB）")
                    }
                    out.write(buf, 0, count)
                }
                out.toString(StandardCharsets.UTF_8.name())
            }
        }
    }

    private fun readDisplayName(uri: Uri): String {
        var name = ""
        if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (col >= 0) name = cursor.getString(col).orEmpty()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "读取本地文件名失败: %s", uri)
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast(':')
        }
        return name.trim()
    }

    companion object {
        private const val MAX_TEXT_IMPORT_BYTES = 16L * 1024 * 1024
    }
}
