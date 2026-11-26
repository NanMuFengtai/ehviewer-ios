package com.hippo.ehviewer.jm

import com.hippo.ehviewer.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

private const val TAG = "JmHistory"

/** 一本禁漫的阅读记录（同一本只留一条，记最后读的章节和页码）。 */
@Serializable
data class JmHistoryEntry(
    val albumId: Long,
    val title: String = "",
    val chapterId: Long = 0,
    val chapterName: String = "",
    val chapterSort: Int = 1,
    val chapters: Int = 1,
    val pages: Int = 0,
    val timestamp: Long = 0,
    /** 上次看到第几页（0 基）。 */
    val lastPage: Int = 0,
) {
    fun timeText(): String = if (timestamp <= 0) {
        ""
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    fun progressText(): String? = if (lastPage > 0 && pages > 0) "${lastPage + 1}/$pages" else null

    fun chapterText(): String = when {
        chapters <= 1 -> ""
        chapterName.isNotBlank() -> chapterName
        else -> "第 $chapterSort 話"
    }
}

object JmHistory {
    private const val MAX = 500

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(appCtx.filesDir, "jm/history.json")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableEntries = MutableStateFlow<List<JmHistoryEntry>>(emptyList())

    @Volatile
    private var loaded = false

    val entries: StateFlow<List<JmHistoryEntry>> = mutableEntries

    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    fun record(album: JmApi.Album) {
        scope.launch {
            mutex.withLock {
                loadLocked()
                val previous = mutableEntries.value.firstOrNull { it.albumId == album.albumId }
                val entry = JmHistoryEntry(
                    albumId = album.albumId,
                    title = album.name,
                    chapters = album.series.size.coerceAtLeast(1),
                    timestamp = System.currentTimeMillis(),
                    chapterId = previous?.chapterId ?: 0,
                    chapterName = previous?.chapterName ?: "",
                    chapterSort = previous?.chapterSort ?: 1,
                    pages = previous?.pages ?: 0,
                    lastPage = previous?.lastPage ?: 0,
                )
                val next = (listOf(entry) + mutableEntries.value.filter { it.albumId != entry.albumId }).take(MAX)
                mutableEntries.value = next
                persist(next)
            }
        }
    }

    /** 阅读器翻页 / 退出时写回「第几章第几页」；没有条目就补一条。 */
    fun saveProgress(
        albumId: Long,
        title: String,
        chapterId: Long,
        chapterName: String,
        chapterSort: Int,
        chapters: Int,
        pages: Int,
        page: Int,
    ) {
        val index = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        scope.launch {
            mutex.withLock {
                loadLocked()
                val current = mutableEntries.value
                val existing = current.firstOrNull { it.albumId == albumId }
                val entry = JmHistoryEntry(
                    albumId = albumId,
                    title = title.ifBlank { existing?.title ?: "" },
                    chapterId = chapterId,
                    chapterName = chapterName,
                    chapterSort = chapterSort,
                    chapters = chapters.coerceAtLeast(1),
                    pages = pages,
                    timestamp = existing?.timestamp ?: System.currentTimeMillis(),
                    lastPage = index,
                )
                val next = if (existing == null) {
                    (listOf(entry) + current).take(MAX)
                } else {
                    current.map { if (it.albumId == albumId) entry else it }
                }
                mutableEntries.value = next
                persist(next)
                AppLog.i(TAG, "progress $albumId ch$chapterId -> page ${index + 1}/$pages")
            }
        }
    }

    fun remove(albumId: Long) {
        scope.launch {
            mutex.withLock {
                loadLocked()
                val next = mutableEntries.value.filter { it.albumId != albumId }
                mutableEntries.value = next
                persist(next)
            }
        }
    }

    fun clear() {
        scope.launch {
            mutex.withLock {
                loaded = true
                mutableEntries.value = emptyList()
                runCatching { if (file.exists()) file.delete() }
            }
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        mutableEntries.value = runCatching {
            if (!file.exists()) emptyList() else json.decodeFromString<List<JmHistoryEntry>>(file.readText())
        }.onFailure { AppLog.w(TAG, "history read failed", it) }.getOrDefault(emptyList())
        loaded = true
    }

    private fun persist(list: List<JmHistoryEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(list))
        }.onFailure { AppLog.w(TAG, "history write failed", it) }
    }
}
