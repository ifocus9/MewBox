package com.fongmi.nodejs

import java.util.concurrent.ConcurrentHashMap

/**
 * 按 `:nodejs` 进程代际（generation）跟踪已初始化的爬虫站点：
 * - 同一进程代内每个站点最多触发一次 `/init`（兼容 2xx 与未注册 `/init` 的 404）
 * - `:nodejs` 重启（切源、后台被杀重建、传输恢复）后 generation 推进，自动按需补跑当前访问站点的 `/init`
 * - 按站点粒度加锁：同站点并发请求共享一次 `/init`，多源并发搜索互不阻塞
 */
internal class NodeSpiderInitTracker {
    private val stateLock = Any()
    private val siteLocks = ConcurrentHashMap<String, Any>()
    private var trackedGeneration: Long = -1L
    private val initializedApis = HashSet<String>()

    fun isInitialized(generation: Long, baseApi: String): Boolean {
        synchronized(stateLock) {
            return trackedGeneration == generation && initializedApis.contains(baseApi)
        }
    }

    fun markInitialized(generation: Long, baseApi: String) {
        synchronized(stateLock) {
            if (trackedGeneration != generation) {
                trackedGeneration = generation
                initializedApis.clear()
            }
            initializedApis.add(baseApi)
        }
    }

    fun lockFor(baseApi: String): Any = siteLocks.computeIfAbsent(baseApi) { Any() }

    fun clear() {
        synchronized(stateLock) {
            trackedGeneration = -1L
            initializedApis.clear()
        }
        siteLocks.clear()
    }
}
