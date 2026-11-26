package com.hippo.ehviewer.ui.screen.jm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.jm.JmApi
import kotlinx.coroutines.launch

/** 禁漫列表页状态：分类、排序、搜索、分页都在这里，跟着导航记录存活。 */
class JmListViewModel : ViewModel() {

    var category by mutableStateOf(JmApi.CATEGORY_ALL)
        private set

    var order by mutableStateOf("mr")
        private set

    var query by mutableStateOf("")
        private set

    var items by mutableStateOf<List<JmApi.AlbumItem>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var page = 0
    private var endReached = false
    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        reload()
    }

    fun reload() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            runCatching { JmApi.list(1, category, order, query) }
                .onSuccess {
                    items = it.content
                    page = 1
                    endReached = it.content.isEmpty() || it.content.size < 30 ||
                        (it.total in 1..9999 && items.size >= it.total)
                }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        viewModelScope.launch {
            runCatching { JmApi.list(page + 1, category, order, query) }
                .onSuccess {
                    page += 1
                    val seen = items.map { it.id }.toHashSet()
                    items = items + it.content.filter { item -> seen.add(item.id) }
                    endReached = it.content.isEmpty()
                }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    fun chooseCategory(value: String) {
        if (value == category) return
        category = value
        reload()
    }

    fun chooseOrder(value: String) {
        if (value == order) return
        order = value
        reload()
    }

    fun search(text: String) {
        query = text.trim()
        reload()
    }
}
