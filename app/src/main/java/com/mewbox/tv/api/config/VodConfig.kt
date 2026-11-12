package com.mewbox.tv.api.config

import com.mewbox.tv.api.net.T4Service
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.Site
import com.fongmi.nodejs.NodeClient
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.engine.nodejs.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 点播配置总控：拉取 config.json 或 HTML 网页源、与本地站点状态合并、维护默认首页源。 */
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
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _configReloadFlow = MutableSharedFlow<Long>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(0L)
    }
    /** 订阅配置刷新/重载通知流 */
    val configReloadFlow: SharedFlow<Long> = _configReloadFlow.asSharedFlow()

    private val _importingText = MutableStateFlow<String?>(null)
    /** 普通/HTML 订阅源导入与探测期间的全局加载文案 */
    val importingText: StateFlow<String?> = _importingText.asStateFlow()

    /** 观察当前激活的订阅记录 */
    fun observeActiveConfig(): Flow<Config?> =
        combine(configDao.observeAll(), settings.configUrlFlow) { configs, activeUrl ->
            val targetUrl = activeUrl.ifBlank { configs.firstOrNull()?.url.orEmpty() }
            configs.firstOrNull { it.url == targetUrl }
        }.distinctUntilChanged()

    /** 导入订阅：`input` 可以是订阅 URL（JSON / Node.js / HTML 网页），也可以是 JSON 文本。返回导入的站点数（HTML 源返回 0）。 */
    suspend fun import(input: String, name: String = ""): Int {
        val text = input.trim()
        require(text.isNotEmpty()) { "请输入订阅地址或 JSON" }
        val isJson = text.startsWith("{")
        val isNode = !isJson && nodeRuntime.isConfig(text)
        val isLocal = !isJson && !isNode && isLocalFile(text)

        // 若目标地址在本地已确认为 HTML 网页源，直接切换并交给 WebView 加载，无需重复发起阻塞探测请求
        val existingConfig = if (!isJson) configDao.find(text) else null
        if (!isJson && !isNode && existingConfig?.isHtml == true) {
            val finalName = if (name.isNotBlank()) name else existingConfig.name
            settings.setConfigUrl(text)
            configDao.insert(
                existingConfig.copy(
                    name = finalName,
                    updatedAt = System.currentTimeMillis(),
                    type = Config.TYPE_HTML,
                )
            )
            backgroundScope.launch { ensurePushBackendReady() }
            _configReloadFlow.tryEmit(System.currentTimeMillis())
            return 0
        }

        if (!isNode) {
            _importingText.value = if (isLocal) "正在读取本地配置…" else "正在连接订阅源…"
        }
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
                    val json = nodeRuntime.loadConfig(text)
                    nodeRuntime.accept()
                    json
                }
                isLocal -> {
                    router.clear()
                    nodeRuntime.clear()
                    if (isHtmlFile(text)) {
                        contentType = "text/html"
                    }
                    withContext(Dispatchers.IO) {
                        readLocalContent(text)
                    }
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
                // HTML 网页源与底层点播/Node.js 引擎共存：
                // 保留已有的 siteDao 站点（含 push_agent 推送源）与 NodeRuntime 进程，供网页调用 pan.play / playVod / search
                settings.setConfigUrl(text)
                configDao.insert(
                    Config(
                        url = text,
                        name = finalName,
                        home = "",
                        updatedAt = System.currentTimeMillis(),
                        type = Config.TYPE_HTML,
                    )
                )
                backgroundScope.launch { ensurePushBackendReady() }
                _configReloadFlow.tryEmit(System.currentTimeMillis())
                return 0
            }
            require(incoming.isNotEmpty()) { "未解析到任何站点（需要包含 sites 数组或有效的 HTML 页面）" }

            if (!isJson && !isNode) {
                router.clear()
                nodeRuntime.clear()
            }

            val targetHomeKey = existingConfig?.home.orEmpty()

            val old = siteDao.getAll().associateBy { it.key }
            val merged = merge(incoming, old)

            // 确定唯一的首页站点：优先匹配该订阅源之前保存的 home 站点，若不存在则取第一个可用站点
            val homeSite = merged.firstOrNull { it.key == targetHomeKey && it.enabled && !it.hide && router.isSupported(it) }
                ?: merged.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }
            val finalHomeKey = homeSite?.key.orEmpty()

            val finalSites = merged.map { site ->
                site.copy(isHome = (site.key == finalHomeKey))
            }

            // 事务性原子替换所有站点，确保 Room 只发射一次最终自洽状态
            siteDao.replaceAll(finalSites)

            if (!isJson) {
                settings.setConfigUrl(text)
                val finalName = when {
                    name.isNotBlank() -> name
                    else -> existingConfig?.name.orEmpty()
                }
                configDao.insert(
                    Config(
                        url = text,
                        name = finalName,
                        home = finalHomeKey,
                        updatedAt = System.currentTimeMillis(),
                        type = Config.TYPE_VOD,
                    )
                )
            }

            // 发送配置刷新事件，触发首页等页面刷新
            _configReloadFlow.tryEmit(System.currentTimeMillis())
            return finalSites.size
        } finally {
            if (!isNode) {
                _importingText.value = null
            }
        }
    }

    /** 重新拉取并刷新当前激活的订阅配置。返回导入/刷新的站点数。 */
    suspend fun reload(): Int {
        val currentUrl = settings.configUrlFlow.first().ifBlank {
            configDao.latest()?.url.orEmpty()
        }
        require(currentUrl.isNotBlank()) { "未配置订阅地址，请先在设置中添加订阅" }
        val config = configDao.find(currentUrl)
        val name = config?.name.orEmpty()
        return import(currentUrl, name)
    }

    /**
     * 应用启动时确保当前激活的订阅环境已就绪。
     *
     * Node.js 源走完整 [reload]：重新比对 index.js.md5 / index.config.js.md5，
     * 有新版本就下载并启动新包，避免用户不手动刷新就一直用旧资源包。
     *
     * 这一段之前是会阻塞到分钟级的，根因是 sidecar 探测串行 + 30s 超时 + 自动重试。
     * 现在探测改成并发 + 3s 短超时（[NodeBundle.PROBE_CLIENT]），所以留在冷启动
     * 同步做是安全的：源可达时只多几百毫秒，不可达时最多等 3 秒就降级到本地缓存。
     *
     * 刻意**不做后台异步检查**：更新时机必须可预测。你自己维护源，改完 bundle
     * 开一次 app 就该看到效果；后台偷偷更新既不知道什么时候生效，又可能在
     * 你正看片的时候重启运行时把所有源打断。冷启动时没有任何用户操作可打断，
     * 这才是更新该发生的地方。
     *
     * 普通源仅在本地没有站点数据时才拉取，避免每次启动都重下整份配置。
     *
     * @return 需要在首页提示的文案（无提示时返回 null）
     */
    suspend fun ensureActiveConfig(): String? {
        val currentUrl = settings.configUrlFlow.first().ifBlank {
            configDao.latest()?.url.orEmpty()
        }
        if (currentUrl.isBlank()) {
            nodeRuntime.clearLoadingForce()
            return null
        }

        val currentConfig = configDao.find(currentUrl)
        if (currentConfig?.isHtml == true) {
            nodeRuntime.clearLoadingForce()
            ensurePushBackendReady()
            return null
        }

        val nodeSource = nodeRuntime.isConfig(currentUrl)
        val cacheReady = siteDao.getAll().isNotEmpty()
        if (cacheReady && !nodeSource) return null

        return try {
            reload()
            if (nodeSource) "Node.js 服务启动成功" else null
        } catch (e: Exception) {
            Timber.w(e, "启动时加载订阅配置失败: %s", currentUrl)
            // 无缓存可退：启动就是彻底失败了，必须给用户一句反馈，否则首页只会是个空壳
            if (!nodeSource || !cacheReady) {
                nodeRuntime.clearLoadingForce()
                return if (nodeSource) "启动失败：${NodeRuntime.describe(e)}" else null
            }
            // 有缓存就降级：用本地包把运行时装起来，别让首页白屏
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

    /** 当前激活的订阅是否为 Node.js 源（Node 源加载过程由全局 loading 提示，调用方不必再额外发提示） */
    suspend fun isNodeSource(): Boolean {
        val url = settings.configUrlFlow.first().ifBlank {
            configDao.latest()?.url.orEmpty()
        }
        return url.isNotBlank() && nodeRuntime.isConfig(url)
    }

    suspend fun setHome(key: String) {
        siteDao.setHome(key)
        val currentUrl = settings.configUrlFlow.first()
        if (currentUrl.isNotBlank()) {
            configDao.updateHome(currentUrl, key)
        }
    }

    suspend fun update(site: Site) = siteDao.update(site)

    suspend fun hasPushRuntimeAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (nodeRuntime.isRunning() || nodeRuntime.hasCachedBundle(null)) return@withContext true
        configDao.getAll().any { !it.isHtml && nodeRuntime.isConfig(it.url) }
    }

    /**
     * 当处于 HTML 网页源（如网盘检索页）时，静默确保底层 Node.js 推送引擎（push_agent）与站点表已就绪：
     * 1. 若本地存在已保存的 Node.js 订阅记录或缓存包，静默拉起 NodeRuntime，并在 siteDao 缺少 push_agent 时自动同步其 `/config` 站点。
     * 2. 若无 Node.js 源但存在普通点播订阅记录且 siteDao 为空，静默拉取恢复其站点列表。
     */
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
                        val old = existingSites.associateBy { it.key }
                        val merged = merge(incoming, old)
                        val homeSite = merged.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }
                        val homeKey = homeSite?.key.orEmpty()
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
                    val homeSite = incoming.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }
                    val homeKey = homeSite?.key.orEmpty()
                    siteDao.replaceAll(incoming.map { it.copy(isHome = (it.key == homeKey)) })
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "HTML 源静默准备底层推送引擎失败")
        }
    }

    /** 读取本地订阅文件文本内容（支持 file://、content:// 或本地绝对路径） */
    private fun readLocalContent(text: String): String {
        return if (text.startsWith("content://", ignoreCase = true)) {
            val uri = Uri.parse(text)
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw FileNotFoundException("无法打开本地订阅文件: $text")
        } else {
            val filePath = if (text.startsWith("file://", ignoreCase = true)) {
                text.substring(7)
            } else {
                text
            }
            val file = File(filePath)
            if (!file.exists()) {
                throw FileNotFoundException("本地订阅文件不存在: $filePath")
            }
            file.readText(Charsets.UTF_8)
        }
    }

    companion object {
        const val PUSH_KEY = "push_agent"

        /** 判断指定 URL 是否指向本地文件 */
        fun isLocalFile(url: String): Boolean {
            val trimmed = url.trim()
            return trimmed.startsWith("file://", ignoreCase = true) ||
                trimmed.startsWith("content://", ignoreCase = true) ||
                trimmed.startsWith("/")
        }

        /** 判断指定 URL 是否指向本地 HTML 文件 */
        fun isHtmlFile(url: String): Boolean {
            val clean = url.substringBefore('?').substringBefore('#').trim()
            return clean.endsWith(".html", ignoreCase = true) || clean.endsWith(".htm", ignoreCase = true)
        }

        fun isPushSite(site: Site): Boolean {
            return site.key.equals(PUSH_KEY, ignoreCase = true) ||
                site.key.equals("nodejs_push_agent", ignoreCase = true) ||
                site.key.endsWith("_push_agent", ignoreCase = true) ||
                site.api.contains("/push_agent", ignoreCase = true) ||
                site.api.contains("/spider/push/", ignoreCase = true)
        }
        private val HTML_TITLE_REGEX = Regex(
            pattern = "<title[^>]*>(.*?)</title>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * 判定订阅 URL 返回的内容是否为 HTML 网页源：
         * 1. Content-Type 包含 text/html 或 application/xhtml+xml，且响应正文以 `<` 开头
         * 2. 或正文去除 BOM 与空白后以常见 HTML 文档/标签前缀开头，或包含完整的 `<html ...>` 结构
         */
        fun isHtmlResponse(contentType: String, body: String): Boolean {
            val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (trimmed.isEmpty()) return false
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false

            val ctLower = contentType.lowercase()
            if ((ctLower.contains("text/html") || ctLower.contains("application/xhtml+xml")) && trimmed.startsWith("<")) {
                return true
            }

            val headSample = trimmed.take(2048).lowercase()
            if (
                headSample.startsWith("<!doctype html") ||
                headSample.startsWith("<html") ||
                headSample.startsWith("<head") ||
                headSample.startsWith("<body") ||
                headSample.startsWith("<meta") ||
                headSample.startsWith("<title") ||
                headSample.startsWith("<script")
            ) {
                return true
            }

            if (headSample.startsWith("<!--") && (headSample.contains("<html") || headSample.contains("<!doctype html"))) {
                return true
            }

            return headSample.contains("<html") &&
                (body.contains("</html>", ignoreCase = true) || headSample.contains("<head") || headSample.contains("<body"))
        }

        /** 提取 HTML 中的 `<title>` 文本作为订阅默认展示名称 */
        fun extractHtmlTitle(body: String): String {
            if (body.isBlank()) return ""
            val raw = HTML_TITLE_REGEX.find(body)?.groupValues?.getOrNull(1) ?: return ""
            val decoded = raw
                .replace("&nbsp;", " ", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
                .replace("&lt;", "<", ignoreCase = true)
                .replace("&gt;", ">", ignoreCase = true)
                .replace("&quot;", "\"", ignoreCase = true)
                .replace("&#39;", "'", ignoreCase = true)
                .replace("&apos;", "'", ignoreCase = true)
                .replace(WHITESPACE_REGEX, " ")
                .trim()
            return decoded.take(60)
        }

        /** 配置中的结构字段取最新；用户可改的开关沿用本地值（isHome 不沿用，由 Config.home 统一调度）。 */
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
