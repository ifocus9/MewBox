package com.mewbox.tv.catvod

import com.mewbox.tv.bean.Site
import javax.inject.Inject
import javax.inject.Singleton

/** 按 Site 属性把请求路由到对应引擎；业务层只依赖它，不直接依赖任何具体协议。 */
@Singleton
class SpiderRouter @Inject constructor(
    private val loaders: @JvmSuppressWildcards Set<SpiderLoader>,
) {
    fun isSupported(site: Site): Boolean = loaders.any { it.supports(site) }

    fun spider(site: Site): Spider =
        loaders.firstOrNull { it.supports(site) }?.getSpider(site)
            ?: throw UnsupportedOperationException("不支持的站点类型: ${site.type} (${site.name})")

    fun clear() = loaders.forEach { it.clear() }
}
