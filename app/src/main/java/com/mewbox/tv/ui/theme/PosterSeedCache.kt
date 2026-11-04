package com.mewbox.tv.ui.theme

/**
 * 海报种子色 LRU 内存缓存（移植自 AVBox PosterSeedCache & HomeBackdrop）
 */
class PosterSeedCache(private val capacity: Int = 48) {

    private val seeds = object : LinkedHashMap<String, Int?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>?): Boolean = size > capacity
    }

    @Synchronized
    fun put(key: String, seed: Int?) {
        seeds[key] = seed
    }

    @Synchronized
    fun get(key: String): Int? = seeds[key]

    @Synchronized
    fun has(key: String): Boolean = seeds.containsKey(key)
}

object PosterSeedMemory {
    private val cache = PosterSeedCache()

    fun has(pic: String?): Boolean = !pic.isNullOrEmpty() && cache.has(pic)

    fun seedOf(pic: String?): Int? = pic?.takeIf { it.isNotEmpty() }?.let { cache.get(it) }

    fun put(pic: String?, seed: Int?) {
        val key = pic?.takeIf { it.isNotEmpty() } ?: return
        cache.put(key, seed)
    }
}
