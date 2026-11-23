package com.mewbox.tv.api.config

import android.content.Context
import android.net.Uri
import com.fongmi.nodejs.NodeBundle
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.HistoryDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 订阅管理统一协调器：
 * 收口 [SettingsViewModel] 与 [HtmlSourceViewModel] 共享的
 * 保存/改名/导入/切换/删除及垃圾清扫逻辑，消除重复实现。
 */
@Singleton
class SubscriptionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vodConfig: VodConfig,
    private val localImporter: LocalSourceImporter,
    private val configDao: ConfigDao,
    private val historyDao: HistoryDao,
    private val settings: SettingsRepository,
) {
    /** 订阅动作完成后的结果摘要 */
    sealed class ActionResult {
        data class Imported(
            val message: String,
            val loadedUrl: String,
            val isHtml: Boolean,
        ) : ActionResult()

        data class Renamed(val message: String) : ActionResult()

        data object NoOp : ActionResult()
    }

    fun isNodeSource(url: String?, pickedUri: Uri? = null): Boolean {
        if (pickedUri != null) return false
        return !url.isNullOrBlank() && NodeBundle.isConfig(url.trim())
    }

    suspend fun saveConfig(
        url: String,
        name: String,
        pickedUri: Uri? = null,
        isEdit: Boolean = false,
        oldUrl: String = "",
    ): ActionResult {
        val targetUrl = url.trim()
        val targetName = name.trim()
        val trimmedOldUrl = oldUrl.trim()

        if (pickedUri != null) {
            val oldConfig = if (isEdit && trimmedOldUrl.isNotEmpty()) configDao.find(trimmedOldUrl) else null
            val outcome = localImporter.importFromUri(
                uri = pickedUri,
                customName = targetName,
                replaceOldUrl = if (isEdit) trimmedOldUrl else "",
                existingConfigId = oldConfig?.id.orEmpty(),
            )
            vodConfig.pruneOrphanStorage()
            val msg = formatImportMessage(outcome)
            return ActionResult.Imported(
                message = msg,
                loadedUrl = outcome.loadedUrl,
                isHtml = outcome.isHtml,
            )
        }

        if (targetUrl.isEmpty()) return ActionResult.NoOp

        if (isEdit && trimmedOldUrl == targetUrl) {
            val finalName = if (isManagedLocalUrl(targetUrl) && targetName.isBlank()) {
                configDao.find(targetUrl)?.name?.ifBlank { "本地订阅" } ?: "本地订阅"
            } else {
                targetName
            }
            configDao.updateName(targetUrl, finalName)
            val msg = if (finalName.isBlank()) "已清除名称，将使用订阅链接" else "订阅名称已更新"
            return ActionResult.Renamed(msg)
        }

        val oldConfig = if (isEdit && trimmedOldUrl.isNotEmpty()) configDao.find(trimmedOldUrl) else null
        val existing = configDao.find(targetUrl)
        val finalName = when {
            targetName.isNotBlank() -> targetName
            else -> existing?.name ?: oldConfig?.name.orEmpty()
        }
        val count = vodConfig.import(
            input = targetUrl,
            name = finalName,
            configId = oldConfig?.id.orEmpty(),
            replaceUrl = if (isEdit && trimmedOldUrl != targetUrl) trimmedOldUrl else "",
        )
        if (isEdit && trimmedOldUrl != targetUrl) {
            vodConfig.pruneOrphanStorage()
        }
        val msg = if (count == 0) "已导入网页订阅源" else "导入成功：$count 个站点"
        return ActionResult.Imported(
            message = msg,
            loadedUrl = targetUrl,
            isHtml = (count == 0),
        )
    }

    suspend fun switchConfig(url: String, customName: String = "", configId: String = "", replaceUrl: String = ""): ActionResult {
        val targetUrl = url.trim()
        if (targetUrl.isEmpty()) return ActionResult.NoOp
        val count = vodConfig.import(
            input = targetUrl,
            name = customName,
            configId = configId,
            replaceUrl = replaceUrl,
        )
        val msg = if (count == 0) "已切换至网页订阅源" else "订阅切换成功（共 $count 个站点）"
        return ActionResult.Imported(
            message = msg,
            loadedUrl = targetUrl,
            isHtml = (count == 0),
        )
    }

    /** 删除非当前激活订阅，返回错误文案（null 表示删除成功）。 */
    suspend fun deleteConfig(url: String): String? {
        if (settings.configUrlFlow.first() == url) {
            return "当前激活的订阅不可删除"
        }
        val config = configDao.find(url)
        configDao.delete(url)
        if (config != null && config.id.isNotBlank()) {
            historyDao.clearWithTracksByConfig(config.id)
        }
        vodConfig.pruneOrphanStorage()
        return null
    }

    private fun isManagedLocalUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.startsWith("local://", ignoreCase = true)) return true
        if (!trimmed.startsWith("file://", ignoreCase = true)) return false
        val path = runCatching { URI(trimmed).path }.getOrNull()
            ?: trimmed.removePrefix("file://").substringBefore('?').substringBefore('#')
        if (path.isBlank()) return false
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        val managedDirs = listOf("local_html", "local_configs", "subscriptions")
        return managedDirs.any { dirName ->
            val dirCanonical = runCatching { File(context.filesDir, dirName).canonicalPath }.getOrNull()
                ?: return@any false
            canonical == dirCanonical || canonical.startsWith("$dirCanonical${File.separator}")
        }
    }

    private fun formatImportMessage(outcome: VodConfig.ImportOutcome): String {
        return when {
            outcome.rolledBack -> "新包启动失败，已自动回滚至上一可用版本"
            outcome.siteCount == 0 -> "已导入本地网页订阅源"
            else -> "导入成功：${outcome.siteCount} 个站点"
        }
    }
}
