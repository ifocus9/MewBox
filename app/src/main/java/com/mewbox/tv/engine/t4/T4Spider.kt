package com.mewbox.tv.engine.t4

import com.mewbox.tv.api.net.T4Service
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.catvod.Spider
import com.mewbox.tv.catvod.SpiderLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 把 Spider 标准方法映射为 T4 HTTP Query 请求（参数以 M0 实测为准）。 */
class T4Spider(
    private val site: Site,
    private val service: T4Service,
) : Spider {

    private suspend fun request(params: Map<String, String>): Result {
        val all = LinkedHashMap(params)
        if (site.ext.isNotEmpty()) all["extend"] = site.ext
        val text = withContext(Dispatchers.IO) { service.get(site.api, all).use { it.string() } }
        val result = ResultParser.parseResult(text)
        return result.copy(list = result.list.map { it.tag() })
    }

    private fun Vod.tag() = copy(siteKey = site.key, siteName = site.name)

    /** 首页：无参数请求（或 ac=home），返回 class / filters / 可选 list。 */
    override suspend fun homeContent(filter: Boolean): Result = request(emptyMap())

    override suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: Map<String, String>,
    ): Result = request(mapOf("ac" to "list", "t" to tid, "pg" to pg))

    override suspend fun detailContent(ids: List<String>): Result =
        request(mapOf("ac" to "detail", "ids" to ids.joinToString(",")))

    override suspend fun searchContent(keyword: String, quick: Boolean, pg: String): Result =
        request(mapOf("wd" to keyword, "quick" to if (quick) "1" else "0", "pg" to pg))

    /** play_id 整体透传，仅由 Retrofit 做 URL 编码。 */
    override suspend fun playerContent(flag: String, id: String, vipFlags: List<String>): Result =
        request(mapOf("play" to id, "flag" to flag))
}

@Singleton
class T4SpiderLoader @Inject constructor(
    private val service: T4Service,
) : SpiderLoader {
    private val cache = ConcurrentHashMap<String, Spider>()

    override fun supports(site: Site): Boolean = site.type == 4

    override fun getSpider(site: Site): Spider =
        cache.getOrPut("${site.key}|${site.api}|${site.ext}") { T4Spider(site, service) }

    override fun clear() = cache.clear()
}
