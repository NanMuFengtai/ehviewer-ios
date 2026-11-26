package com.hippo.ehviewer.nhentai

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

private const val TAG = "NhentaiHistory"

@Serializable
data class NhentaiHistoryEntry(
    val id: Long,
    val title: String = "",
    /** 服务端给的缩略图路径，例如 `galleries/123/thumb.webp`。 */
    val thumbnail: String = "",
    val pages: Int = 0,
    val timestamp: Long = 0,
    /** 上次看到第几页（0 基），和 E 站 `progress` 表一个语义。 */
    val lastPage: Int = 0,
) {
    fun timeText(): String = if (timestamp <= 0) {
        ""
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    /** 有进度时显示 `读到 13/164`。 */
    fun progressText(): String? =
        if (lastPage > 0 && pages > 0) "${lastPage + 1}/$pages" else null
}

/**
 * nhentai 的浏览历史。
 *
 * 没有进 Room：nhentai 的 id 和 E 站的 gid 都是纯数字，混在一张表里必须改主键和迁移，
 * 这里单独存一个 JSON（最多 500 条），和 E 站历史互不干扰。
 */
object NhentaiHistory {
    private const val MAX = 500

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(appCtx.filesDir, "nhentai/history.json")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableEntries = MutableStateFlow<List<NhentaiHistoryEntry>>(emptyList())

    @Volatile
    private var loaded = false

    val entries: StateFlow<List<NhentaiHistoryEntry>> = mutableEntries

    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    fun record(entry: NhentaiHistoryEntry) {        scope.launch {
            mutex.withLock {
                loadLocked()
                // 重新打开同一个本子时保留原来的阅读进度
                val previous = mutableEntries.value.firstOrNull { it.id == entry.id }
                val merged = entry.copy(lastPage = previous?.lastPage ?: entry.lastPage)
                val next = (listOf(merged) + mutableEntries.value.filter { it.id != entry.id }).take(MAX)
                mutableEntries.value = next
                persist(next)
            }
        }
    }

    /** 打开详情就记一条（同一本会移到最前，进度保留）。 */
    fun record(detail: NhentaiApi.Detail) = record(entryOf(detail))

    private fun entryOf(detail: NhentaiApi.Detail) = NhentaiHistoryEntry(
        id = detail.id,
        title = detail.displayTitle,
        thumbnail = detail.thumbnail.path,
        pages = detail.num_pages,
        timestamp = System.currentTimeMillis(),
    )

    fun remove(id: Long) {
        scope.launch {
            mutex.withLock {
                loadLocked()
                val next = mutableEntries.value.filter { it.id != id }
                mutableEntries.value = next
                persist(next)
            }
        }
    }

    /**
     * 阅读器翻页 / 退出时回写看到第几页。
     *
     * 没有历史条目就顺手补一条：从下载页或离线直接进阅读器时，详情页可能还没记过历史，
     * 以前这里会直接丢掉进度，回详情页就成了「没记录」。
     */
    fun saveProgress(detail: NhentaiApi.Detail, page: Int) {
        val index = page.coerceIn(0, (detail.pages.size - 1).coerceAtLeast(0))
        scope.launch {
            mutex.withLock {
                loadLocked()
                val current = mutableEntries.value
                val existing = current.firstOrNull { it.id == detail.id }
                if (existing != null) {
                    // 顺序不动，只改页码
                    if (existing.lastPage == index && existing.pages == detail.num_pages) return@withLock
                    val next = current.map {
                        if (it.id == detail.id) it.copy(lastPage = index, pages = detail.num_pages) else it
                    }
                    mutableEntries.value = next
                    persist(next)
                } else {
                    val next = (listOf(entryOf(detail).copy(lastPage = index)) + current).take(MAX)
                    mutableEntries.value = next
                    persist(next)
                }
                AppLog.i(TAG, "progress ${detail.id} -> page ${index + 1}/${detail.num_pages}")
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
            if (!file.exists()) emptyList() else json.decodeFromString<List<NhentaiHistoryEntry>>(file.readText())
        }.onFailure { AppLog.w(TAG, "history read failed", it) }.getOrDefault(emptyList())
        loaded = true
    }

    private fun persist(list: List<NhentaiHistoryEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(list))
        }.onFailure { AppLog.w(TAG, "history write failed", it) }
    }
}
