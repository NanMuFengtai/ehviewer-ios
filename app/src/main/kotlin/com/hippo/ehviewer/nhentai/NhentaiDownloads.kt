package com.hippo.ehviewer.nhentai

import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.util.AppLog
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

private const val TAG = "NhentaiDownloads"

/**
 * 一条 nhentai 下载记录。
 *
 * 整个详情一起存下来：页表、标题、缩略图路径都在里面，
 * 所以离线时连详情接口都不用请求，直接能进阅读器。
 */
@Serializable
data class NhentaiDownload(
    val id: Long,
    val detail: NhentaiApi.Detail,
    val done: Int = 0,
    val total: Int = 0,
    val completed: Boolean = false,
    val addedAt: Long = 0,
)

/**
 * nhentai 的离线下载。
 *
 * 文件放 `filesDir/nhentai/downloads/<id>/<页码>.<ext>`，索引进 `nhentai/downloads.json`。
 * 下载一条是一个协程，逐页拉、断点续：已存在的页直接跳过。
 */
object NhentaiDownloads {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root: Path = appCtx.filesDir.toOkioPath() / "nhentai" / "downloads"
    private val indexFile = appCtx.filesDir.toOkioPath().resolve("nhentai/downloads.json").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val jobs = HashMap<Long, Job>()
    private val mutableItems = MutableStateFlow<List<NhentaiDownload>>(emptyList())

    @Volatile
    private var loaded = false

    val items: StateFlow<List<NhentaiDownload>> = mutableItems

    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    fun pageFile(id: Long, index: Int, path: String): Path =
        root / "$id" / "${index + 1}.${NhentaiImages.extensionOf(path)}"

    /** 已下载到本地的这一页；没有就是 null。 */
    fun localPage(id: Long, index: Int, path: String): Path? =
        pageFile(id, index, path).takeIf { it.exists() }

    /** 下载完整的一本（离线阅读时用它，不再请求接口）。 */
    fun completedDetail(id: Long): NhentaiApi.Detail? =
        mutableItems.value.firstOrNull { it.id == id && it.completed }?.detail

    fun isDownloading(id: Long): Boolean = synchronized(jobs) { jobs[id]?.isActive == true }

    fun start(detail: NhentaiApi.Detail) {
        if (detail.pages.isEmpty()) return
        synchronized(jobs) {
            if (jobs[detail.id]?.isActive == true) return
        }
        val job = scope.launch {
            mutex.withLock {
                loadLocked()
                if (mutableItems.value.any { it.id == detail.id && it.completed }) return@launch
                upsert(NhentaiDownload(detail.id, detail, 0, detail.pages.size, false, System.currentTimeMillis()))
                persist()
            }
            AppLog.i(TAG, "start ${detail.id} pages=${detail.pages.size}")
            val done = AtomicInteger(0)
            val concurrency = Settings.multiThreadDownload.value.coerceIn(1, 6)
            val semaphore = Semaphore(concurrency)
            coroutineScope {
                detail.pages.forEachIndexed { index, page ->
                    launch {
                        semaphore.withPermit {
                            ensureActive()
                            val target = pageFile(detail.id, index, page.path)
                            if (!target.exists()) {
                                runCatching { NhentaiImages.download(target, NhentaiCdn.imageCandidates(page.path)) }
                                    .onFailure { AppLog.w(TAG, "page ${index + 1} of ${detail.id} failed: ${it.message}", it) }
                            }
                            if (target.exists()) {
                                val current = done.incrementAndGet()
                                update(detail.id) { it.copy(done = current) }
                                if (current % 4 == 0) mutex.withLock { persist() }
                            }
                        }
                    }
                }
            }
            val finished = done.get()
            mutex.withLock {
                update(detail.id) { it.copy(done = finished, completed = finished >= detail.pages.size) }
                persist()
            }
            AppLog.i(TAG, "finish ${detail.id} $finished/${detail.pages.size}")
        }
        synchronized(jobs) { jobs[detail.id] = job }
    }

    fun cancel(id: Long) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        scope.launch {
            mutex.withLock {
                loadLocked()
                mutableItems.value = mutableItems.value.filterNot { it.id == id }
                persist()
            }
        }
    }

    fun delete(id: Long) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        scope.launch {
            mutex.withLock {
                loadLocked()
                mutableItems.value = mutableItems.value.filterNot { it.id == id }
                persist()
            }
            runCatching { (root / "$id").delete() }
                .onFailure { AppLog.w(TAG, "delete $id failed", it) }
        }
    }

    fun clearCompleted() {
        scope.launch {
            val ids = mutex.withLock {
                loadLocked()
                mutableItems.value.filter { it.completed }.map { it.id }
            }
            ids.forEach { delete(it) }
        }
    }

    private fun upsert(record: NhentaiDownload) {
        mutableItems.value = listOf(record) + mutableItems.value.filter { it.id != record.id }
    }

    private fun update(id: Long, block: (NhentaiDownload) -> NhentaiDownload) {
        mutableItems.value = mutableItems.value.map { if (it.id == id) block(it) else it }
    }

    private fun loadLocked() {
        if (loaded) return
        runCatching {
            root.toFile().mkdirs()
            mutableItems.value = if (indexFile.exists()) {
                json.decodeFromString<List<NhentaiDownload>>(indexFile.readText())
            } else {
                emptyList()
            }
        }.onFailure { AppLog.w(TAG, "index read failed", it) }
        loaded = true
    }

    private fun persist() {
        runCatching {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(json.encodeToString(mutableItems.value))
        }.onFailure { AppLog.w(TAG, "index write failed", it) }
    }
}
