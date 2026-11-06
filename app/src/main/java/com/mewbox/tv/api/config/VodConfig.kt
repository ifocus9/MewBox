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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 点播配置总控：拉取 config.json、与本地站点状态合并、维护默认首页源。 */
@Singleton
class VodConfig @Inject constructor(
    private val service: T4Service,
    private val siteDao: SiteDao,
    private val configDao: ConfigDao,
    private val settings: SettingsRepository,
    private val router: SpiderRouter,
    private val nodeRuntime: NodeRuntime,
) {
    private val _configReloadFlow = MutableSharedFlow<Long>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(0L)
    }
    /** 订阅配置刷新/重载通知流 */
    val configReloadFlow: SharedFlow<Long> = _configReloadFlow.asSharedFlow()

    /** 导入订阅：`input` 可以是订阅 URL，也可以是 JSON 文本。返回导入的站点数。 */
    suspend fun import(input: String, name: String = ""): Int {
        val text = input.trim()
        require(text.isNotEmpty()) { "请输入订阅地址或 JSON" }
        val isJson = text.startsWith("{")
        // 加载新配置前先清空旧 Spider 实例与 NodeRuntime，避免旧资源与并发干扰
        router.clear()

        val body = when {
            isJson -> {
                nodeRuntime.clear()
                text
            }
            nodeRuntime.isConfig(text) -> {
                val json = nodeRuntime.loadConfig(text)
                nodeRuntime.accept()
                json
            }
            else -> {
                nodeRuntime.clear()
                withContext(Dispatchers.IO) { service.get(text).use { it.string() } }
            }
        }

        val incoming = ResultParser.parseSites(body)
        require(incoming.isNotEmpty()) { "未解析到任何站点（需要包含 sites 数组）" }

        // 查询当前配置记录（如果存在），获取其之前偏好的 home 站点
        val existingConfig = if (!isJson) configDao.find(text) else null
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
                )
            )
        }

        // 发送配置刷新事件，触发首页等页面刷新
        _configReloadFlow.tryEmit(System.currentTimeMillis())
        return finalSites.size
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

    companion object {
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
