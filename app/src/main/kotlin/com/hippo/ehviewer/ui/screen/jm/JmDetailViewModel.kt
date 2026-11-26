package com.hippo.ehviewer.ui.screen.jm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.jm.JmApi
import com.hippo.ehviewer.jm.JmCache
import com.hippo.ehviewer.jm.JmHistory
import com.hippo.ehviewer.jm.JmHistoryEntry
import com.hippo.ehviewer.util.AppLog
import kotlinx.coroutines.launch

private const val TAG = "JmDetail"

/** 禁漫详情页状态：本子信息、章节列表、读到的位置。 */
class JmDetailViewModel(private val albumId: Long) : ViewModel() {

    var album by mutableStateOf(JmCache.album(albumId))
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    /** 历史里这一本的记录（最后读的章节和页码）。 */
    var progress by mutableStateOf<JmHistoryEntry?>(null)
        private set

    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        fetch()
    }

    fun reload() = fetch()

    fun refreshProgress(entry: JmHistoryEntry?) {
        progress = entry
    }

    private fun fetch() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            runCatching { JmCache.albumOrLoad(albumId) }
                .onSuccess {
                    album = it
                    AppLog.i(TAG, "album $albumId loaded chapters=${it.series.size} pages=${it.pageCount}")
                    JmHistory.record(it)
                }
                .onFailure {
                    error = it.message ?: it.javaClass.simpleName
                    AppLog.e(TAG, "album $albumId load failed", it)
                }
            loading = false
        }
    }
}
