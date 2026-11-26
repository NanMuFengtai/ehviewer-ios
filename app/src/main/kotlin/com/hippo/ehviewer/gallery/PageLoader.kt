package com.hippo.ehviewer.gallery

import androidx.collection.SieveCache
import androidx.collection.mutableIntObjectMapOf
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.bracketCase
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.withNonCancellableContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.util.AppLog
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.OSUtils
import com.hippo.ehviewer.util.detectAds
import com.hippo.ehviewer.util.displayString
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.coroutines.NamedMutex
import moe.tarsin.coroutines.withLock
import okio.Path

private val progressScope = CoroutineScope(Dispatchers.IO)
private const val TAG = "PageLoader"
private const val PREVIEW_BATCH = 30
private const val MAX_ERROR_PREVIEWS = 24
internal const val CANCELLED_MESSAGE = "Cancelled"
private const val PREVIEW_WARMUP_LIMIT = 600
private const val MAX_CACHE_SIZE = 512 * 1024 * 1024
private const val MIN_CACHE_SIZE = 256 * 1024 * 1024
private val activeLoaders = WeakHashMap<PageLoader, Unit>()

internal fun trimReaderMemory() {
    val loaders = synchronized(activeLoaders) { activeLoaders.keys.toList() }
    loaders.forEach { it.trimIdleImages() }
}

abstract class PageLoader(val scope: CoroutineScope, val info: GalleryInfo?, startPage: Int, val size: Int, val hasAds: Boolean = false) : AutoCloseable {
    var startPage = startPage.coerceIn(0, size - 1)

    private val jobs = mutableIntObjectMapOf<Job>()
    private val previewJobs = mutableIntObjectMapOf<Job>()

    /** 预览图同时下载的数量：30 页一批，真正并发的控制在 12 个（预览图小，快一点更跟手）。 */
    private val previewSemaphore = Semaphore(12)

    @Volatile
    private var previewWarmupStarted = false
    private val mutex = NamedMutex<Int>()
    private val semaphore = Semaphore(4)

    /**
     * 失败页保留的预览图：原图挂了也能看到低清底图，错误提示叠在上面。
     * 成功、重试成功或阅读器关闭时释放。
     */
    private val errorPreviews = LinkedHashMap<Int, Image>()

    private val cache = SieveCache<Int, Image>(
        maxSize = (OSUtils.totalMemory / 8).toInt().coerceIn(MIN_CACHE_SIZE, MAX_CACHE_SIZE),
        sizeOf = { _, v -> v.allocationSize.toInt() },
        onEntryRemoved = { k, o, n, _ -> if (o.unpin()) n ?: notifyPageWait(k) },
    )

    private suspend fun atomicallyDecodeAndUpdate(index: Int) {
        bracketCase(
            { openSource(index) },
            { src ->
                withNonCancellableContext {
                    notifyPageSucceed(index, Image.decode(src, hasAds && detectAds(index, size)))
                }
            },
            { src, case -> if (case !is ExitCase.Completed) src.close() },
        )
    }

    private val lock = ReentrantReadWriteLock()

    val pages = (0 until size).map { Page(it) }

    private val prefetchPageCount = Settings.preloadImage.value

    fun restart() {
        lock.write { cache.evictAll() }
        pages.forEach(Page::reset)
    }

    private val prevIndex = AtomicInt(-1)

    init {
        synchronized(activeLoaders) { activeLoaders[this] = Unit }
    }

    internal fun trimIdleImages() = lock.write {
        val current = prevIndex.load()
        val candidates = mutableListOf<Int>()
        cache.forEach { index, image ->
            if (index != current && image.refcnt.load() == 1) candidates += index
        }
        candidates.forEach { cache.remove(it) }
    }

    fun retryPage(index: Int, orgImg: Boolean = false) {
        notifyPageWait(index)
        lock.write { cache.remove(index) }
        onRequest(index, true, orgImg)
    }

    protected abstract fun prefetchPages(pages: List<Int>, bounds: IntRange)

    protected abstract fun onRequest(index: Int, force: Boolean = false, orgImg: Boolean = false)

    fun notifyPageWait(index: Int, expected: Image? = null) {
        pages[index].statusFlow.update { status ->
            // 只在「当前显示的仍是这张图」时才退回等待，避免把已经替换好的原图打回加载圈
            if (expected != null && status is PageStatus.Ready && status.image !== expected) {
                status
            } else {
                PageStatus.Queued
            }
        }
    }

    fun notifyPagePercent(index: Int, percent: Float) {
        pages[index].statusFlow.update { current ->
            when (current) {
                is PageStatus.Loading -> current.apply { progress.update { percent } }
                // 已经有图在显示（预览图或原图）：原图进度只属于后台，
                // 不能把画面打回加载圈，否则预览会被"重新加载一遍"
                is PageStatus.Ready -> current
                else -> PageStatus.Loading(MutableStateFlow(percent))
            }
        }
    }

    fun notifyPageSucceed(index: Int, image: Image, replaceCache: Boolean = true) {
        forgetErrorPreview(index)
        if (replaceCache) lock.write { cache[index] = image }
        pages[index].statusFlow.update { current ->
            if (current is PageStatus.Ready && current.image !== image) {
                AppLog.i(
                    TAG,
                    "page ${index + 1} swapped ${current.image.intrinsicSize.width}x${current.image.intrinsicSize.height} -> " +
                        "${image.intrinsicSize.width}x${image.intrinsicSize.height}",
                )
            }
            if (image.hasQrCode) PageStatus.Blocked(image) else PageStatus.Ready(image)
        }
    }

    /** 请求被取消（翻走、换页、退出）不是失败：能显示预览就继续显示，回来再拉原图。 */
    private fun notifyPageCancelled(index: Int) {
        val preview = lock.read { cache[index] }?.takeIf { it.isPreview }
        pages[index].statusFlow.update { current ->
            when {
                current is PageStatus.Ready && !current.image.isPreview -> current
                preview != null -> PageStatus.Ready(preview)
                else -> PageStatus.Queued
            }
        }
        AppLog.i(TAG, "page ${index + 1} request cancelled, preview=${preview != null}")
    }

    fun notifyPageFailed(index: Int, error: String?) {
        // SpiderQueen 在下载任务被取消时上报的就是这个字符串
        if (error == CANCELLED_MESSAGE) {
            notifyPageCancelled(index)
            return
        }
        AppLog.w(TAG, "page ${index + 1} failed: ${error ?: "unknown"}")
        // 失败时把预览图留下来当底图（钉住引用，缓存移除后仍可用），错误提示叠在上面
        val preview = lock.read { cache[index] }?.takeIf { it.isPreview }
        if (preview != null && preview.pin()) {
            rememberErrorPreview(index, preview)
        }
        // 缓存里的预览图要清掉：否则下次 request() 会把它当成"已加载"贴回去，状态从 Error 变回 Ready
        lock.write { cache.remove(index) }
        pages[index].statusFlow.update { PageStatus.Error(error) }
    }

    /** 失败页可以拿来当底图的预览图；没有就是 null。 */
    fun errorPreview(index: Int): Image? = lock.read { errorPreviews[index] }

    private fun rememberErrorPreview(index: Int, preview: Image) = lock.write {
        errorPreviews.remove(index)?.unpin()
        errorPreviews[index] = preview
        while (errorPreviews.size > MAX_ERROR_PREVIEWS) {
            val eldest = errorPreviews.keys.first()
            errorPreviews.remove(eldest)?.unpin()
        }
    }

    private fun forgetErrorPreview(index: Int) {
        lock.write { errorPreviews.remove(index)?.unpin() }
    }

    override fun close() {
        synchronized(activeLoaders) { activeLoaders.remove(this) }
        lock.write {
            errorPreviews.values.forEach { it.unpin() }
            errorPreviews.clear()
            cache.evictAll()
        }
        saveProgress()
    }

    /**
     * 记录阅读进度。
     *
     * E 站的书写进 Room 的 progress 表；nhentai 没有 Room 记录，靠 [saveReadProgress] 回写自己的历史。
     */
    fun saveProgress() {
        val page = startPage.coerceIn(0, size - 1)
        info?.run {
            progressScope.launch {
                EhDB.putReadProgress(gid, page)
            }
        }
        saveReadProgress(page)
    }

    /** 子类覆盖：把「看到第几页」存到自己的地方。 */
    protected open fun saveReadProgress(index: Int) = Unit

    /**
     * 是否每翻一页就落一次进度。
     *
     * E 站写 Room，退出时存一次就够；nhentai 用 JSON，翻页即存，
     * 这样即使阅读器被系统杀掉、或者返回时没走到 onDispose，详情页也拿得到页码。
     */
    open val autoSaveProgress: Boolean get() = false

    /**
     * 这个源默认就是条漫（竖长图、按宽度铺满、上下连续滚动）。
     *
     * 阅读器的阅读模式是「跟随设置」时，用这个值决定默认排版；
     * 禁漫的韩漫页图是 720x3008 这种超长条，塞进左右翻页会被压成一屏，所以它返回 true。
     */
    open val defaultWebtoon: Boolean get() = false

    /** 多话本的一话（目前只有禁漫的多话本会填）。 */
    data class ChapterEntry(val id: Long, val title: String, val sort: Int)

    /** 这个本子的全部章节；空列表表示不支持章节切换（E 站、nhentai、单话本）。 */
    open val chapters: List<ChapterEntry> get() = emptyList()

    /** 当前正在读的是第几话（[chapters] 的下标），-1 表示不知道。 */
    open val currentChapterIndex: Int get() = -1

    /** 切到指定章节（由实现方负责跳转）。 */
    open fun switchChapter(chapterId: Long) = Unit

    val hasPreviousChapter: Boolean get() = currentChapterIndex > 0
    val hasNextChapter: Boolean get() = currentChapterIndex >= 0 && currentChapterIndex < chapters.lastIndex

    fun previousChapterId(): Long? = chapters.getOrNull(currentChapterIndex - 1)?.id
    fun nextChapterId(): Long? = chapters.getOrNull(currentChapterIndex + 1)?.id

    abstract val title: String

    protected abstract fun getImageExtension(index: Int): String?

    fun getImageFilename(index: Int): String? = getImageExtension(index)?.let {
        FileUtils.sanitizeFilename("$title - ${index + 1}.${it.lowercase()}")
    }

    fun request(index: Int) {
        val prefetchRange = if (index >= prevIndex.load()) {
            index + 1..(index + prefetchPageCount).coerceAtMost(size - 1)
        } else {
            index - 1 downTo (index - prefetchPageCount).coerceAtLeast(0)
        }
        prevIndex.store(index)
        val image = lock.read { cache[index] }
        if (image != null) {
            notifyPageSucceed(index, image, false)
            // 缓存里只有预览图：原图还没拉过（或者还没回来），继续把原图请求发出去
            if (image.isPreview) {
                AppLog.i(TAG, "page ${index + 1} only preview cached, request original")
                onRequest(index)
            }
        } else {
            notifyPageWait(index)
            // 先贴一张低清预览，原图在后台继续拉
            requestPreview(index)
            onRequest(index)
        }

        // Prefetch to disk
        val pagesAbsent = prefetchRange.filter { pages[it].needsOriginal }
        val start = if (prefetchRange.step > 0) prefetchRange.first else prefetchRange.last
        val end = if (prefetchRange.step > 0) prefetchRange.last else prefetchRange.first
        prefetchPages(pagesAbsent, start - 5..end + 5)
        // 预取页也顺手把预览图拉下来，翻页时就立刻有东西看
        pagesAbsent.take(2).forEach(::requestPreview)
    }

    /** 还差原图吗：没有图、报错、或者现在只有一张预览图。 */
    private val Page.needsOriginal: Boolean
        get() = when (val current = status) {
            PageStatus.Queued, is PageStatus.Error -> true
            is PageStatus.Ready -> current.image.isPreview
            else -> false
        }

    /** 低清预览图：先给一张能看的，原图到了会自动替换。返回 null 表示这页没有预览。 */
    protected open suspend fun loadPreview(index: Int): Image? = null

    /**
     * 一进阅读器就把预览图铺开：先同时拉 30 页，拉完接着下一批 30 页，
     * 直到全书（或上限）或者退出阅读器。
     *
     * 预览图只有几百 KB 甚至几十 KB，比原图快一个量级；铺开之后随便翻到哪一页都有东西看，
     * 不用再盯着转圈等原图。真正要看的页仍然优先进 [request]，原图下载不受影响。
     */
    fun startPreviewWarmup(startPage: Int = this.startPage) {
        if (!Settings.readerPreviewFirst.value) return
        if (previewWarmupStarted) return
        previewWarmupStarted = true
        scope.launch {
            // 当前页优先：先把正在看的这页预览铺上，再批量预热后面的页
            val first = startPage.coerceIn(0, (size - 1).coerceAtLeast(0))
            requestPreview(first)
            previewJobs[first]?.join()
            var from = first
            val limit = (from + PREVIEW_WARMUP_LIMIT).coerceAtMost(size)
            while (from < limit) {
                val end = (from + PREVIEW_BATCH).coerceAtMost(limit)
                val jobs = (from until end).map { index ->
                    requestPreview(index)
                    previewJobs[index]
                }
                jobs.forEach { it?.join() }
                AppLog.i(TAG, "preview warmed ${from + 1}-$end/$size")
                from = end
            }
        }
    }

    private fun requestPreview(index: Int) {
        if (!Settings.readerPreviewFirst.value) return
        if (lock.read { cache[index] } != null) return
        // 原图已经报错了就别再贴预览图盖住重试按钮
        if (pages[index].status is PageStatus.Error) return
        if (previewJobs[index]?.isActive == true) return
        val fetch: suspend () -> Unit = {
            val preview = runCatching { loadPreview(index) }
                .onFailure { if (it !is CancellationException) AppLog.w(TAG, "page ${index + 1} preview failed", it) }
                .getOrNull()
            if (preview != null) {
                val current = pages[index].status
                // 原图已经就绪/报错（或者正在替换）时不要用预览覆盖它
                if (lock.read { cache[index] } == null &&
                    current !is PageStatus.Ready &&
                    current !is PageStatus.Blocked &&
                    current !is PageStatus.Error
                ) {
                    AppLog.i(TAG, "page ${index + 1} preview shown (${preview.intrinsicSize.width}x${preview.intrinsicSize.height})")
                    notifyPageSucceed(index, preview)
                } else {
                    preview.unpin()
                }
            }
        }
        previewJobs[index] = scope.launch {
            // 当前正在看的那页不排队：否则首屏要等预热队列里其他页的预览
            if (index == prevIndex.load()) {
                fetch()
            } else {
                previewSemaphore.withPermit { fetch() }
            }
        }
    }

    fun cancelRequest(index: Int) {
        jobs[index]?.cancel()
    }

    abstract fun save(index: Int, file: Path): Boolean

    fun notifySourceReady(index: Int) = synchronized(jobs) {
        if (jobs[index]?.isActive != true) {
            jobs[index] = scope.launch {
                try {
                    mutex.withLock(index) {
                        semaphore.withPermit {
                            atomicallyDecodeAndUpdate(index)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        notifyPageFailed(index, CANCELLED_MESSAGE)
                        throw e
                    } else {
                        notifyPageFailed(index, e.displayString())
                    }
                }
            }
        }
    }

    abstract fun openSource(index: Int): ImageSource
}
