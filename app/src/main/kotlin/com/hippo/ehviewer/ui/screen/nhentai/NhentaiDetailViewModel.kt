package com.hippo.ehviewer.ui.screen.nhentai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.nhentai.NhentaiApi
import com.hippo.ehviewer.nhentai.NhentaiCdn
import com.hippo.ehviewer.nhentai.NhentaiDetailCache
import com.hippo.ehviewer.nhentai.NhentaiHistory
import com.hippo.ehviewer.nhentai.NhentaiImages
import com.hippo.ehviewer.nhentai.NhentaiSession
import com.hippo.ehviewer.util.AppLog
import kotlinx.coroutines.launch
import okio.Path

private const val TAG = "NhentaiDetail"

/**
 * 详情页状态。
 *
 * 放在导航返回栈作用域的 ViewModel 里：进阅读器再返回时这个 ViewModel 还在，
 * 详情、封面、收藏态全都在内存里，回到详情页是直接渲染，不会再转圈重新请求。
 */
class NhentaiDetailViewModel(private val id: Long) : ViewModel() {

    var detail by mutableStateOf(NhentaiDetailCache.get(id))
        private set

    var cover by mutableStateOf<Path?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var favorited by mutableStateOf<Boolean?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        val cached = detail
        if (cached != null) {
            AppLog.i(TAG, "detail ${cached.id} restored, pages=${cached.pages.size}")
            prepare(cached)
        } else {
            fetch()
        }
    }

    fun reload() = fetch()

    fun toggleFavorite(onNeedSignIn: () -> Unit) {
        val current = detail ?: return
        if (!NhentaiSession.isSignedIn) {
            onNeedSignIn()
            return
        }
        viewModelScope.launch {
            runCatching { NhentaiApi.setFavorite(current.id, !(favorited ?: false)) }
                .onSuccess { favorited = it.favorited }
                .onFailure { AppLog.w(TAG, "toggle favorite ${current.id} failed", it) }
        }
    }

    /** 正文之外的附带数据：历史、封面、收藏态。缓存命中时只补这些，不碰详情接口。 */
    private fun prepare(detail: NhentaiApi.Detail) {
        NhentaiHistory.record(detail)
        viewModelScope.launch {
            if (cover == null) {
                val path = NhentaiImages.ensureCover(detail.id, detail.cover.path, detail.thumbnail.path)
                cover = path
                if (path == null) AppLog.w(TAG, "cover for ${detail.id} unavailable, falling back to thumbnail")
            }
            if (favorited == null && NhentaiSession.isSignedIn) {
                favorited = runCatching { NhentaiApi.favoriteState(detail.id).favorited }.getOrNull()
            }
        }
    }

    private fun fetch() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            runCatching {
                NhentaiCdn.refresh()
                NhentaiApi.detail(id)
            }.onSuccess {
                NhentaiDetailCache.put(it)
                detail = it
                AppLog.i(TAG, "detail ${it.id} loaded pages=${it.pages.size} tags=${it.tags.size}")
                prepare(it)
            }.onFailure {
                AppLog.e(TAG, "detail $id load failed", it)
                error = it.message ?: it.javaClass.simpleName
            }
            loading = false
        }
    }
}
