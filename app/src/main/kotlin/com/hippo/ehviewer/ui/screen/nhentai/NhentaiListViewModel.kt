package com.hippo.ehviewer.ui.screen.nhentai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.nhentai.NhentaiApi
import com.hippo.ehviewer.nhentai.NhentaiCdn
import com.hippo.ehviewer.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.tarsin.coroutines.runSuspendCatching

private const val TAG = "NhentaiList"

/**
 * nhentai 列表页的状态。
 *
 * 放在 ViewModel 里，作用域是导航返回栈里的这条记录：点进本子再返回时
 * 列表不会被重建，既不会重新请求第一页，滚动位置也还在原处。
 */
class NhentaiListViewModel(val args: NhentaiListArgs) : ViewModel() {

    var items by mutableStateOf<List<NhentaiApi.GalleryItem>>(emptyList())
        private set

    var activeQuery by mutableStateOf("")
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var hasMore by mutableStateOf(true)
        private set

    /** 第一页是否已经请求过（成功或失败都算），避免返回时重复请求。 */
    var loadedOnce by mutableStateOf(false)
        private set

    private var page = 0
    private var job: Job? = null

    fun ensureLoaded() {
        if (!loadedOnce) reload()
    }

    fun reload() = start(reset = true)

    fun loadMore() {
        if (items.isEmpty() || !hasMore) return
        start(reset = false)
    }

    fun search(query: String) {
        activeQuery = query
        items = emptyList()
        start(reset = true)
    }

    private fun start(reset: Boolean) {
        if (reset) {
            job?.cancel()
            loading = false
            page = 0
            hasMore = true
        }
        if (loading) return
        loading = true
        error = null
        val next = page + 1
        job = viewModelScope.launch {
            runSuspendCatching { fetch(next) }
                .onSuccess { (list, more) ->
                    page = next
                    val merged = (if (reset) list else items + list).distinctBy { it.id }
                    if (merged.size != (if (reset) list.size else items.size + list.size)) {
                        AppLog.w(TAG, "drop duplicated galleries when loading page $next (kept ${merged.size})")
                    }
                    items = merged
                    hasMore = more && list.isNotEmpty()
                    loadedOnce = true
                }
                .onFailure { throwable ->
                    AppLog.e(TAG, "load page $next failed mode=${args.mode} query=$activeQuery", throwable)
                    error = throwable.message ?: throwable.javaClass.simpleName
                    hasMore = false
                    loadedOnce = true
                }
            loading = false
        }
    }

    private suspend fun fetch(target: Int): Pair<List<NhentaiApi.GalleryItem>, Boolean> {
        NhentaiCdn.refresh()
        val query = activeQuery
        return when {
            query.isNotBlank() -> NhentaiApi.search(query, target).let { it.result to (target < it.num_pages) }
            args.mode == NhentaiApi.MODE_POPULAR -> if (target == 1) {
                NhentaiApi.popular() to false
            } else {
                emptyList<NhentaiApi.GalleryItem>() to false
            }
            args.mode == NhentaiApi.MODE_TAGGED -> NhentaiApi.tagged(args.tagId, target).let { it.result to (target < it.num_pages) }
            args.mode == NhentaiApi.MODE_FAVORITES -> NhentaiApi.favorites(target).let { it.result to (target < it.num_pages) }
            else -> NhentaiApi.list(target).let { it.result to (target < it.num_pages) }
        }
    }
}
