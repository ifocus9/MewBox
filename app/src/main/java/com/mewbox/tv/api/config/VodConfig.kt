package com.mewbox.tv.api.config

import android.content.Context
import android.net.Uri
import com.fongmi.nodejs.NodeBundle
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.api.net.T4Service
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.Site
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.engine.nodejs.NodeRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 点播配置总控：协调订阅配置加载（VOD JSON / Node.js / HTML 网页源）、站点表原子切换、冷启动续接与垃圾清扫。 */
@Singleton
class VodConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: T4Service,
    private val siteDao: SiteDao,
    private val configDao: ConfigDao,
    private val settings: SettingsRepository,
    private val router: SpiderRouter,
    private val nodeRuntime: NodeRuntime,
) {
    /** 订阅导入/切换结果摘要 */
    data class ImportOutcome(
        val siteCount: Int,
        val rolledBack: Boolean = false,
        val loadedUrl: String = "",
        val isHtml: Boolean = false,
    )

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _configReloadFlow = MutableSharedFlow<Long>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(0L)
    }
    val configReloadFlow: SharedFlow<Long> = _configReloadFlow.asSharedFlow()

    private val _importingText = MutableStateFlow<String?>(null)
    val importingText: StateFlow<String?> = _importingText.asStateFlow()

    private val configStateLock = Any()
    private var activeConfigOps = 0
    private val _isConfiguring = MutableStateFlow(false)
    val isConfiguring: StateFlow<Boolean> = _isConfiguring.asStateFlow()

    private val sitesFlow: SharedFlow<List<Site>> =
        siteDao.observeAll().shareIn(backgroundScope, SharingStarted.WhileSubscribed(), replay = 1)

    fun observeSites(): Flow<List<Site>> = sitesFlow

    private fun beginConfiguring() {
        synchronized(configStateLock) {
            activeConfigOps++
            if (activeConfigOps == 1) _isConfiguring.value = true
        }
    }

    private fun endConfiguring() {
        synchronized(configStateLock) {
            if (activeConfigOps > 0) activeConfigOps--
            if (activeConfigOps == 0) _isConfiguring.value = false
        }
    }

    private suspend fun <T> withConfiguring(block: suspend () -> T): T {
        beginConfiguring()
        return try {
            block()
        } finally {
            endConfiguring()
        }
    }

    private data class SiteSyncKey(
        val key: String,
        val name: String,
        val type: Int,
        val api: String,
        val ext: String,
        val enabled: Boolean,
        val hide: Boolean,
        val isHome: Boolean,
    )

    private fun List<Site>.toSyncKeyMap(): Map<String, SiteSyncKey> =
        associate { site ->
            SiteSyncKey(site.key, site.name, site.type, site.api, site.ext, site.enabled, site.hide, site.isHome)
                .let { site.key to it }
        }

    private suspend fun awaitSitesSettled(finalSites: List<Site>) {
        val expected = finalSites.toSyncKeyMap()
        withTimeoutOrNull(300L) {
            sitesFlow.first { current -> current.toSyncKeyMap() == expected }
        }
    }

    fun observeActiveConfig(): Flow<Config?> =
        combine(configDao.observeAll(), settings.configUrlFlow) { configs, activeUrl ->
            val targetUrl = activeUrl.ifBlank { configs.firstOrNull()?.url.orEmpty() }
            configs.firstOrNull { it.url == targetUrl }
        }.distinctUntilChanged()

    /** 导入或切换订阅：`input` 可为 URL、本地路径 / `local://`、`{...}` JSON 文本。返回导入站点数（HTML 源返回 0）。 */
    suspend fun import(
        input: String,
        name: String = "",
        configId: String = "",
        replaceUrl: String = "",
    ): Int {
        val text = input.trim()
        require(text.isNotEmpty()) { "请输入订阅地址或 JSON" }
        return withConfiguring {
            importInternal(text, name, configId, replaceUrl).siteCount
        }
    }

    /** 流式导入本地 SAF Node.js ZIP 压缩包至 `local://node/<id>` 并立即启动加载站点。 */
    suspend fun importLocalNodeStream(
        localSourceUrl: String,
        name: String,
        configId: String,
        streamOpener: () -> InputStream,
        replaceUrl: String = "",
    ): ImportOutcome = withConfiguring {
        val existingConfig = configDao.find(localSourceUrl)
        val oldConfig = if (replaceUrl.isNotBlank()) configDao.find(replaceUrl) else null
        val resolvedId = existingConfig?.id?.takeIf { it.isNotBlank() }
            ?: oldConfig?.id?.takeIf { it.isNotBlank() }
            ?: configId.ifBlank { Config.newId() }

        router.clear()
        val loadResult = nodeRuntime.loadFromStream(localSourceUrl, streamOpener)
        val incoming = ResultParser.parseSites(loadResult.configJson)
        require(incoming.isNotEmpty()) { "未从 Node 压缩包中解析到任何启用站点" }
        nodeRuntime.accept()

        val preserveNameOnRollback = if (loadResult.rolledBack) {
            existingConfig?.name?.takeIf { it.isNotBlank() }
                ?: oldConfig?.name?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val finalName = preserveNameOnRollback ?: name.ifBlank { existingConfig?.name.orEmpty() }
        persistVodSites(
            sourceUrl = localSourceUrl,
            persistConfigRecord = true,
            resolvedId = resolvedId,
            incoming = incoming,
            existingConfig = existingConfig ?: oldConfig,
            finalName = finalName,
            replaceUrl = replaceUrl,
        )
        ImportOutcome(
            siteCount = incoming.size,
            rolledBack = loadResult.rolledBack,
            loadedUrl = localSourceUrl,
            isHtml = false,
        )
    }

    private suspend fun importInternal(
        text: String,
        name: String,
        configId: String,
        replaceUrl: String,
    ): ImportOutcome {
        val isJson = text.startsWith("{")
        val isNode = !isJson && nodeRuntime.isConfig(text)
        val isLocal = !isJson && !isNode && isLocalFile(text)

        val existingConfig = if (!isJson) configDao.find(text) else null
        val oldConfig = if (replaceUrl.isNotBlank() && replaceUrl != text) configDao.find(replaceUrl) else null
        val resolvedId = existingConfig?.id?.takeIf { it.isNotBlank() }
            ?: oldConfig?.id?.takeIf { it.isNotBlank() }
            ?: configId.ifBlank { Config.newId() }

        // 已确认为远程 HTML 网页源且无需覆盖本地存档时直接切换（本地 file:// 网页源可能刚发生内容覆盖，须重读标题）
        val canFastSwitchHtml = !isJson && !isNode && existingConfig?.isHtml == true && !isLocal
        if (canFastSwitchHtml) {
            val finalName = if (name.isNotBlank()) name else existingConfig.name
            commitHtmlConfig(text, resolvedId, finalName, replaceUrl)
            return ImportOutcome(siteCount = 0, loadedUrl = text, isHtml = true)
        }

        if (!isNode) {
            _importingText.value = if (isLocal) "正在读取本地配置…" else "正在连接订阅源…"
        }
        var rolledBack = false
        try {
            var contentType = ""
            val body = when {
                isJson -> {
                    router.clear()
                    nodeRuntime.clear()
                    text
                }
                isNode -> {
                    router.clear()
                    val loadResult = nodeRuntime.loadConfigResult(text)
                    rolledBack = loadResult.rolledBack
                    loadResult.configJson
                }
                isLocal -> {
                    router.clear()
                    nodeRuntime.clear()
                    if (isHtmlFile(text)) contentType = "text/html"
                    withContext(Dispatchers.IO) { readLocalContent(text) }
                }
                else -> {
                    withContext(Dispatchers.IO) {
                        service.get(text).use { rb ->
                            contentType = rb.contentType()?.toString().orEmpty()
                            rb.string()
                        }
                    }
                }
            }

            val incoming = ResultParser.parseSites(body)
            if (incoming.isEmpty() && !isJson && !isNode && (isHtmlResponse(contentType, body) || isHtmlFile(text))) {
                val extractedTitle = extractHtmlTitle(body)
                val finalName = when {
                    name.isNotBlank() -> name
                    !existingConfig?.name.isNullOrBlank() -> existingConfig.name
                    extractedTitle.isNotBlank() -> extractedTitle
                    else -> ""
                }
                commitHtmlConfig(text, resolvedId, finalName, replaceUrl)
                return ImportOutcome(siteCount = 0, loadedUrl = text, isHtml = true)
            }
            require(incoming.isNotEmpty()) { "未解析到任何站点（需要包含 sites 数组或有效的 HTML 页面）" }
            if (isNode) nodeRuntime.accept()

            if (!isJson && !isNode) {
                router.clear()
                nodeRuntime.clear()
            }

            val finalName = when {
                name.isNotBlank() -> name
                else -> existingConfig?.name.orEmpty()
            }
            persistVodSites(
                sourceUrl = text,
                persistConfigRecord = !isJson,
                resolvedId = resolvedId,
                incoming = incoming,
                existingConfig = existingConfig ?: oldConfig,
                finalName = finalName,
                replaceUrl = replaceUrl,
            )
            return ImportOutcome(
                siteCount = incoming.size,
                rolledBack = rolledBack,
                loadedUrl = text,
                isHtml = false,
            )
        } finally {
            if (!isNode) _importingText.value = null
        }
    }

    private suspend fun commitHtmlConfig(url: String, resolvedId: String, finalName: String, replaceUrl: String) {
        settings.setActiveConfig(url, resolvedId)
        configDao.insert(
            Config(
                url = url,
                name = finalName,
                home = "",
                updatedAt = System.currentTimeMillis(),
                type = Config.TYPE_HTML,
                id = resolvedId,
            ),
        )
        if (replaceUrl.isNotBlank() && replaceUrl != url) {
            configDao.delete(replaceUrl)
        }
        backgroundScope.launch { ensurePushBackendReady() }
        _configReloadFlow.tryEmit(System.currentTimeMillis())
    }

    private suspend fun persistVodSites(
        sourceUrl: String,
        persistConfigRecord: Boolean,
        resolvedId: String,
        incoming: List<Site>,
        existingConfig: Config?,
        finalName: String,
        replaceUrl: String,
    ) {
        val targetHomeKey = existingConfig?.home.orEmpty()
        val old = siteDao.getAll().associateBy { it.key }
        val merged = merge(incoming, old)
        val homeSite = merged.firstOrNull { it.key == targetHomeKey && it.enabled && !it.hide && router.isSupported(it) }
            ?: merged.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }
        val finalHomeKey = homeSite?.key.orEmpty()
        val finalSites = merged.map { site -> site.copy(isHome = (site.key == finalHomeKey)) }

        siteDao.replaceAll(finalSites)
        if (persistConfigRecord) {
            settings.setActiveConfig(sourceUrl, resolvedId)
            configDao.insert(
                Config(
                    url = sourceUrl,
                    name = finalName,
                    home = finalHomeKey,
                    updatedAt = System.currentTimeMillis(),
                    type = Config.TYPE_VOD,
                    id = resolvedId,
                ),
            )
            if (replaceUrl.isNotBlank() && replaceUrl != sourceUrl) {
                configDao.delete(replaceUrl)
            }
        }
        awaitSitesSettled(finalSites)
        _configReloadFlow.tryEmit(System.currentTimeMillis())
    }

    /** 重新拉取并刷新当前激活的订阅配置。返回导入/刷新的站点数。 */
    suspend fun reload(): Int = withConfiguring {
        val currentUrl = settings.configUrlFlow.first().ifBlank {
            configDao.latest()?.url.orEmpty()
        }
        require(currentUrl.isNotBlank()) { "未配置订阅地址，请先在设置中添加订阅" }
        val config = configDao.find(currentUrl)
        importInternal(currentUrl, config?.name.orEmpty(), "", "").siteCount
    }

    /**
     * 冷启动确认当前激活订阅就绪，并在收尾后异步清理孤儿缓存包与无主本地归档。
     */
    suspend fun ensureActiveConfig(): String? {
        var runHtmlPushSetup = false
        try {
            return withConfiguring {
                val currentUrl = settings.configUrlFlow.first().ifBlank {
                    configDao.latest()?.url.orEmpty()
                }
                if (currentUrl.isBlank()) {
                    nodeRuntime.clearLoadingForce()
                    return@withConfiguring null
                }

                val currentConfig = configDao.find(currentUrl)
                if (currentConfig != null && currentConfig.id.isNotBlank() && settings.currentConfigId != currentConfig.id) {
                    settings.setConfigId(currentConfig.id)
                }
                if (currentConfig?.isHtml == true) {
                    nodeRuntime.clearLoadingForce()
                    runHtmlPushSetup = true
                    return@withConfiguring null
                }

                val nodeSource = nodeRuntime.isConfig(currentUrl)
                val isLocalNode = nodeRuntime.isLocalNodeSource(currentUrl)
                val cacheReady = siteDao.getAll().isNotEmpty()
                if (cacheReady && !nodeSource) return@withConfiguring null

                // 本地 ZIP 包（local://node/*）冷启动免远程探测，直接秒启本地包；发生 .pending 异常时由 NodeClient 自动退回 previous
                if (isLocalNode && cacheReady) {
                    return@withConfiguring startLocalNodeOnBoot(currentUrl)
                }

                try {
                    val outcome = importInternal(currentUrl, currentConfig?.name.orEmpty(), "", "")
                    when {
                        outcome.rolledBack -> "新包启动失败，已回滚至上一可用版本"
                        nodeSource -> "Node.js 服务启动成功"
                        else -> null
                    }
                } catch (e: Exception) {
                    Timber.w(e, "启动时加载订阅配置失败: %s", currentUrl)
                    if (!nodeSource || !cacheReady) {
                        nodeRuntime.clearLoadingForce()
                        return@withConfiguring if (nodeSource) "启动失败：${NodeRuntime.describe(e)}" else null
                    }
                    try {
                        nodeRuntime.ensureStarted(currentUrl, notify = false)
                        "订阅刷新失败，已使用本地缓存"
                    } catch (e2: Exception) {
                        Timber.w(e2, "启动时降级拉起 Node.js 运行时失败: %s", currentUrl)
                        nodeRuntime.clearLoadingForce()
                        "启动失败：${NodeRuntime.describe(e2)}"
                    }
                }
            }
        } finally {
            backgroundScope.launch { pruneOrphanStorage() }
            if (runHtmlPushSetup) ensurePushBackendReady()
        }
    }

    private suspend fun startLocalNodeOnBoot(currentUrl: String): String? {
        return try {
            val started = nodeRuntime.ensureStarted(currentUrl, notify = false)
            if (started) {
                nodeRuntime.accept()
                "Node.js 服务启动成功"
            } else {
                nodeRuntime.clearLoadingForce()
                "启动失败：本地 Node 资源包不存在"
            }
        } catch (e: Exception) {
            Timber.w(e, "冷启动拉起本地 Node 包失败: %s", currentUrl)
            nodeRuntime.clearLoadingForce()
            "启动失败：${NodeRuntime.describe(e)}"
        }
    }

    /** 清理已不在订阅列表中的 `nodejs/bundles` 子目录、私有本地归档与历史 `subscriptions/` 残留文件。 */
    suspend fun pruneOrphanStorage() = withContext(Dispatchers.IO) {
        try {
            val configs = configDao.getAll()
            val urls = configs.map { it.url }
            val currentUrl = settings.configUrlFlow.first()
            NodeBundle.prune(context, urls, currentUrl)
            NodeBundle.pruneOrphanLocalFiles(context, if (currentUrl.isNotBlank()) urls + currentUrl else urls)
        } catch (e: Exception) {
            Timber.w(e, "清理孤儿订阅缓存失败")
        }
    }

    suspend fun isNodeSource(): Boolean {
        val url = settings.configUrlFlow.first().ifBlank { configDao.latest()?.url.orEmpty() }
        return url.isNotBlank() && nodeRuntime.isConfig(url)
    }

    suspend fun setHome(key: String) {
        siteDao.setHome(key)
        val currentUrl = settings.configUrlFlow.first()
        if (currentUrl.isNotBlank()) configDao.updateHome(currentUrl, key)
    }

    suspend fun update(site: Site) = siteDao.update(site)

    suspend fun hasPushRuntimeAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (nodeRuntime.isRunning() || nodeRuntime.hasCachedBundle(null)) return@withContext true
        configDao.getAll().any { !it.isHtml && nodeRuntime.isConfig(it.url) }
    }

    /** 当处于 HTML 网页源时，静默确保底层 Node.js 推送引擎（push_agent）或点播站点表就绪。 */
    suspend fun ensurePushBackendReady() = withContext(Dispatchers.IO) {
        try {
            val existingSites = siteDao.getAll()
            val hasPushSite = existingSites.any { isPushSite(it) }
            if (nodeRuntime.isRunning() && hasPushSite) return@withContext

            val allConfigs = configDao.getAll()
            val nodeConfig = allConfigs.firstOrNull { !it.isHtml && nodeRuntime.isConfig(it.url) }
            if (nodeConfig != null || nodeRuntime.hasCachedBundle(null)) {
                val targetSource = nodeConfig?.url
                if (!hasPushSite) {
                    val json = nodeRuntime.fetchConfigJson(targetSource)
                    val incoming = ResultParser.parseSites(json.orEmpty())
                    if (incoming.isNotEmpty()) {
                        val merged = merge(incoming, existingSites.associateBy { it.key })
                        val homeKey = merged.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }?.key.orEmpty()
                        siteDao.replaceAll(merged.map { it.copy(isHome = (it.key == homeKey)) })
                    }
                } else if (!nodeRuntime.isRunning()) {
                    nodeRuntime.ensureStarted(source = targetSource, notify = false, showLoading = false)
                }
                return@withContext
            }

            if (existingSites.isEmpty()) {
                val vodConfig = allConfigs.firstOrNull { !it.isHtml && it.url.isNotBlank() } ?: return@withContext
                val body = if (isLocalFile(vodConfig.url)) {
                    readLocalContent(vodConfig.url)
                } else {
                    service.get(vodConfig.url).use { it.string() }
                }
                val incoming = ResultParser.parseSites(body)
                if (incoming.isNotEmpty()) {
                    val homeKey = incoming.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }?.key.orEmpty()
                    siteDao.replaceAll(incoming.map { it.copy(isHome = (it.key == homeKey)) })
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "HTML 源静默准备底层推送引擎失败")
        }
    }

    private fun readLocalContent(text: String): String {
        return when {
            text.startsWith(LOCAL_VOD_SCHEME, ignoreCase = true) -> {
                val id = text.removePrefix(LOCAL_VOD_SCHEME).trim()
                val file = vodConfigFile(context, id)
                if (!file.isFile) throw FileNotFoundException("本地订阅配置不存在，请重新导入")
                file.readText(Charsets.UTF_8)
            }
            text.startsWith("content://", ignoreCase = true) -> {
                context.contentResolver.openInputStream(Uri.parse(text))?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw FileNotFoundException("无法打开本地订阅文件: $text")
            }
            else -> {
                val filePath = if (text.startsWith("file://", ignoreCase = true)) text.substring(7) else text
                val file = File(filePath)
                if (!file.exists()) throw FileNotFoundException("本地订阅文件不存在: $filePath")
                file.readText(Charsets.UTF_8)
            }
        }
    }

    companion object {
        const val PUSH_KEY = "push_agent"
        const val LOCAL_VOD_SCHEME = "local://vod/"
        const val LOCAL_NODE_SCHEME = "local://node/"

        fun vodConfigFile(context: Context, configId: String): File =
            File(File(context.filesDir, "local_configs"), "$configId.json")

        fun htmlConfigFile(context: Context, configId: String): File =
            File(File(context.filesDir, "local_html"), "$configId.html")

        fun isLocalFile(url: String): Boolean = ConfigHtmlDetector.isLocalFile(url)

        fun isHtmlFile(url: String): Boolean = ConfigHtmlDetector.isHtmlFile(url)

        fun isPushSite(site: Site): Boolean {
            return site.key.equals(PUSH_KEY, ignoreCase = true) ||
                site.key.equals("nodejs_push_agent", ignoreCase = true) ||
                site.key.endsWith("_push_agent", ignoreCase = true) ||
                site.api.contains("/push_agent", ignoreCase = true) ||
                site.api.contains("/spider/push/", ignoreCase = true)
        }

        fun isHtmlResponse(contentType: String, body: String): Boolean =
            ConfigHtmlDetector.isHtmlResponse(contentType, body)

        fun extractHtmlTitle(body: String): String = ConfigHtmlDetector.extractHtmlTitle(body)

        fun merge(incoming: List<Site>, old: Map<String, Site>): List<Site> =
            incoming.map { new ->
                val prev = old[new.key] ?: return@map new
                new.copy(
                    enabled = prev.enabled,
                    searchable = if (new.searchable != 0) prev.searchable.coerceAtLeast(1) else 0,
                    quickSearch = new.quickSearch,
                    changeable = new.changeable,
                    isHome = false,
                )
            }
    }
}
