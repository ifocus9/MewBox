package com.mewbox.tv.api.douban

import com.mewbox.tv.api.douban.DoubanApiException.Kind
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 豆瓣想看分页仓库（进程内内存缓存，不落盘）。
 *
 * - 游标 = 服务端 start + 实际返回条数：容忍翻页期间豆瓣清单增删导致的偏移漂移；
 * - interestId 去重：漂移造成的重复页会被静默丢弃；
 * - Mutex 串行化 + 两次请求最小间隔 800ms：防快速连按连发；
 * - 限流（403 / code 103）指数退避自动重试最多 2 次，签名失效不重试；
 * - MAX_PAGES = 40（约 2000 条）兜底，防异常 total 导致死循环。
 */
@Singleton
class DoubanRepository @Inject constructor(
    private val api: DoubanApi,
) {
    data class PageResult(
        val newItems: List<WishItem>,
        val total: Int,
        val hasMore: Boolean,
        val reachedPageLimit: Boolean = false,
    )

    private val mutex = Mutex()
    private val seenIds = mutableSetOf<Long>()
    // COW 快照语义：IO 线程分页追加与主线程 UI 读取并发安全，数据量（≤2000 条）下开销可忽略
    private val cached = CopyOnWriteArrayList<WishItem>()

    private var nextStart = 0
    private var total = 0
    private var pages = 0
    private var hasMoreInternal = false
    private var lastRequestAt = 0L

    /** 最近一次首页加载（同步）成功完成的时刻，供同步按钮 30s 节流判断。 */
    @Volatile
    var lastSyncAt: Long = 0L
        private set

    /** 当前已加载条目（内存缓存快照）。 */
    val items: List<WishItem> get() = cached.toList()

    val totalCount: Int get() = total

    /** 重置游标与缓存后拉首页（同步 / 首次进入共用）。 */
    suspend fun loadFirstPage(uid: String): PageResult = mutex.withLock {
        seenIds.clear()
        cached.clear()
        nextStart = 0
        total = 0
        pages = 0
        hasMoreInternal = false
        val result = fetchLocked(uid)
        lastSyncAt = System.currentTimeMillis()
        result
    }

    /** 按游标取下一页；hasMore=false 时直接短路返回。 */
    suspend fun loadMore(uid: String): PageResult = mutex.withLock {
        if (pages == 0 || !hasMoreInternal) return PageResult(emptyList(), total, hasMore = false)
        fetchLocked(uid)
    }

    private suspend fun fetchLocked(uid: String): PageResult {
        throttleLocked()
        val response = fetchWithBackoff(uid, nextStart)
        pages++
        val newItems = response.interests
            .mapNotNull { it.toWishItem() }
            .filter { seenIds.add(it.interestId) }
        cached.addAll(newItems)
        total = response.total
        nextStart = response.start + response.interests.size
        hasMoreInternal = newItems.isNotEmpty() && cached.size < total && pages < MAX_PAGES
        Timber.d(
            "豆瓣想看分页 start=%s 返回=%s 新增=%s 累计=%s/%s hasMore=%s",
            response.start, response.interests.size, newItems.size, cached.size, total, hasMoreInternal,
        )
        return PageResult(
            newItems = newItems,
            total = total,
            hasMore = hasMoreInternal,
            reachedPageLimit = pages >= MAX_PAGES && cached.size < total,
        )
    }

    /** 限流指数退避（1s → 2s → …），最多自动重试 2 次；其余错误直接上抛。 */
    private suspend fun fetchWithBackoff(uid: String, start: Int): DoubanInterestsResponse {
        var attempt = 0
        while (true) {
            try {
                return api.interests(uid, start)
            } catch (e: DoubanApiException) {
                if (e.kind != Kind.RATE_LIMITED || attempt >= MAX_AUTO_RETRY) throw e
                val backoff = MIN_BACKOFF_MS shl attempt
                Timber.w(e, "豆瓣限流，%sms 后自动重试（第 %s 次）", backoff, attempt + 1)
                delay(backoff)
                attempt++
            }
        }
    }

    private suspend fun throttleLocked() {
        val wait = lastRequestAt + MIN_REQUEST_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    companion object {
        private const val MIN_REQUEST_INTERVAL_MS = 800L
        private const val MAX_AUTO_RETRY = 2
        private const val MIN_BACKOFF_MS = 1000L
        private const val MAX_PAGES = 40
    }
}
