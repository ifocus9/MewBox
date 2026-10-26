package com.mewbox.tv.api.config

import com.mewbox.tv.api.net.T4Service
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.Site
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.SiteDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    /** 导入订阅：`input` 可以是订阅 URL，也可以是 JSON 文本。返回导入的站点数。 */
    suspend fun import(input: String): Int {
        val text = input.trim()
        require(text.isNotEmpty()) { "请输入订阅地址或 JSON" }
        val isJson = text.startsWith("{")
        val body = if (isJson) text
        else withContext(Dispatchers.IO) { service.get(text).use { it.string() } }

        val incoming = ResultParser.parseSites(body)
        require(incoming.isNotEmpty()) { "未解析到任何站点（需要包含 sites 数组）" }

        val old = siteDao.getAll().associateBy { it.key }
        val merged = merge(incoming, old)
        siteDao.upsert(merged)
        siteDao.deleteNotIn(merged.map { it.key })

        if (siteDao.getHome() == null) {
            merged.firstOrNull { it.enabled && !it.hide && router.isSupported(it) }
                ?.let { siteDao.setHome(it.key) }
        }
        if (!isJson) {
            settings.setConfigUrl(text)
            configDao.insert(Config(text))
        }
        router.clear()
        return merged.size
    }

    suspend fun setHome(key: String) = siteDao.setHome(key)

    suspend fun update(site: Site) = siteDao.update(site)

    companion object {
        /** 配置中的结构字段取最新；用户可改的开关沿用本地值。 */
        fun merge(incoming: List<Site>, old: Map<String, Site>): List<Site> =
            incoming.map { new ->
                val prev = old[new.key] ?: return@map new
                new.copy(
                    enabled = prev.enabled,
                    searchable = prev.searchable,
                    quickSearch = prev.quickSearch,
                    changeable = prev.changeable,
                    isHome = prev.isHome,
                )
            }
    }
}
