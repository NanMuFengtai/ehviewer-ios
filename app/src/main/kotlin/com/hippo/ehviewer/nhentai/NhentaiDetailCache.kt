package com.hippo.ehviewer.nhentai

/**
 * nhentai 详情缓存。
 *
 * 详情接口一次就返回全部页表，重复请求纯属浪费：从阅读器返回、从历史再点进来、
 * 阅读器自己拿页表，都应该复用同一份。
 *
 * 只放内存，12 条 LRU，30 分钟过期 —— 够覆盖一次使用流程，又不会吃到旧数据。
 */
object NhentaiDetailCache {
    private const val MAX_ENTRIES = 12
    private const val TTL_MS = 30 * 60 * 1000L

    private data class Entry(val detail: NhentaiApi.Detail, val at: Long)

    private val lock = Any()
    private val map = object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > MAX_ENTRIES
    }

    fun get(id: Long): NhentaiApi.Detail? = synchronized(lock) {
        val entry = map[id] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            map.remove(id)
            null
        } else {
            entry.detail
        }
    }

    fun put(detail: NhentaiApi.Detail) {
        synchronized(lock) { map[detail.id] = Entry(detail, System.currentTimeMillis()) }
    }

    fun invalidate(id: Long) {
        synchronized(lock) { map.remove(id) }
    }
}
