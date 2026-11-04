package com.mewbox.tv.engine.nodejs

import com.mewbox.tv.bean.Site
import com.mewbox.tv.catvod.Spider
import com.mewbox.tv.catvod.SpiderLoader
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeSpiderLoader @Inject constructor(
    private val runtime: NodeRuntime,
) : SpiderLoader {
    private val spiders = ConcurrentHashMap<String, Spider>()

    override fun supports(site: Site): Boolean =
        site.type == 3 || site.api.startsWith("node:")

    /** 所有 Node 站点共用同一个单线程 node 服务，必须单独限流 */
    override val sharesRuntime: Boolean = true

    override fun getSpider(site: Site): Spider =
        spiders.getOrPut("${site.key}|${site.api}|${site.ext}") {
            NodeSpider(site, runtime.client)
        }

    override fun clear() {
        spiders.clear()
    }
}

