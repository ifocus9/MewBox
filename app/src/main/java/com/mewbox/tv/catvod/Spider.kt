package com.mewbox.tv.catvod

import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site

/** 所有爬虫/源引擎的统一契约。 */
interface Spider {
    suspend fun init(ext: String = "") {}
    suspend fun homeContent(filter: Boolean = true): Result
    suspend fun homeVideoContent(): Result = Result()
    suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean = true,
        extend: Map<String, String> = emptyMap(),
    ): Result

    suspend fun detailContent(ids: List<String>): Result
    suspend fun searchContent(keyword: String, quick: Boolean = false, pg: String = "1"): Result
    suspend fun playerContent(flag: String, id: String, vipFlags: List<String> = emptyList()): Result
    suspend fun action(action: String): Result = Result()
    fun destroy() {}
}

/** 引擎加载器：按站点属性判断是否支持并提供 Spider 实例。 */
interface SpiderLoader {
    fun supports(site: Site): Boolean
    fun getSpider(site: Site): Spider
    fun clear() {}

    /** 该引擎的站点是否共享同一个下游运行时（如 Node.js 单线程服务），搜索限流时需要单独分组 */
    val sharesRuntime: Boolean get() = false
}

/** 本地守护进程生命周期接口（为内置 Node.js 9988 服务预留）。 */
interface LocalRuntimeService {
    suspend fun start()
    suspend fun stop()
    val isRunning: Boolean
}
