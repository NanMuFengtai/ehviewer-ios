package com.hippo.ehviewer.jm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hippo.ehviewer.util.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "JmFavorites"

/**
 * 「这本我收藏了吗」的内存索引。
 *
 * 禁漫接口没有「查单本收藏状态」的接口，所以登录后把收藏列表前几页的 id 拉下来存着，
 * 详情页用它判断按钮该显示「收藏」还是「已收藏」；点收藏后再手动改这个集合。
 * 最多翻 5 页（100 本），够覆盖绝大多数人的收藏夹；超出的本子第一次会显示成未收藏。
 */
object JmFavorites {
    private const val MAX_PAGES = 5

    private val mutex = Mutex()

    var ids: Set<Long> by mutableStateOf(emptySet())
        private set

    fun contains(albumId: Long): Boolean = albumId in ids

    fun mark(albumId: Long, on: Boolean) {
        ids = if (on) ids + albumId else ids - albumId
    }

    suspend fun refresh() {
        if (!JmAuth.loggedIn) return
        mutex.withLock {
            runCatching {
                val loaded = linkedSetOf<Long>()
                for (page in 1..MAX_PAGES) {
                    val result = JmApi.favorites(page)
                    result.list.forEach { loaded += it.albumId }
                    if (result.list.size < 20) break
                }
                ids = loaded
                AppLog.i(TAG, "favorites index ${loaded.size}")
            }.onFailure { AppLog.w(TAG, "favorites index failed", it) }
        }
    }

    fun clear() {
        ids = emptySet()
    }
}
