package com.hippo.ehviewer.jm

import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.sendTo
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.util.displayString
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okio.Path

/** 详情 / 章节页的短命缓存，进阅读器不用重新请求一遍。 */
object JmCache {
    private const val TTL = 30 * 60 * 1000L

    private data class Timed<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        fun alive() = System.currentTimeMillis() - at < TTL
    }

    private val albums = ConcurrentHashMap<Long, Timed<JmApi.Album>>()
    private val chapters = ConcurrentHashMap<Long, Timed<JmApi.ChapterPage>>()

    fun album(id: Long): JmApi.Album? = albums[id]?.takeIf { it.alive() }?.value
    fun putAlbum(album: JmApi.Album) {
        albums[album.albumId] = Timed(album)
    }

    fun chapter(id: Long): JmApi.ChapterPage? = chapters[id]?.takeIf { it.alive() }?.value
    fun putChapter(page: JmApi.ChapterPage) {
        chapters[page.chapterId] = Timed(page)
    }

    suspend fun albumOrLoad(id: Long, force: Boolean = false): JmApi.Album =
        (if (force) null else album(id)) ?: JmApi.album(id).also(::putAlbum)

    suspend fun chapterOrLoad(id: Long, force: Boolean = false): JmApi.ChapterPage =
        (if (force) null else chapter(id)) ?: JmApi.chapter(id).also(::putChapter)
}

/**
 * 禁漫的阅读器页加载器。
 *
 * 复用 E 站的 [PageLoader]（缓存、预取、手势），只是取图不一样：
 * 禁漫的图是上下分段的，需要下载后重排成正常方向（[JmImages.ensure]），再走原来的解码流程。
 */
class JmPageLoader(
    scope: CoroutineScope,
    val album: JmApi.Album,
    val chapter: JmApi.ChapterPage,
    val chapterSort: Int,
    private val scrambleId: Long,
    startPage: Int,
    private val onSwitchChapter: (Long) -> Unit = {},
) : PageLoader(scope, null, startPage, chapter.images.size) {

    // 预热是并发的，同一页可能被「预取」和「当前页」同时请求，这里用并发安全的表去重
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    override val title: String
        get() = if (album.isSeries) {
            "${album.name} - 第 $chapterSort 話"
        } else {
            album.name
        }

    override val autoSaveProgress: Boolean get() = true

    /**
     * 禁漫的韩漫是「720x3008」这种竖长条，一话几十屏。
     *
     * 左右翻页会把整条压进一屏（内容小得看不清、上下两条大黑边），所以韩漫默认走条漫模式：
     * 按屏幕宽度铺满、上下连续滚动。标签里没有「韓漫」的（日漫单话本）仍旧左右翻页。
     */
    override val defaultWebtoon: Boolean
        get() = album.tags.any { it.contains("韓漫") || it.contains("韩漫") } || album.series.size > 20

    /** 多话本的章节表：阅读器底部「章節」按钮、结尾的「下一話」提示都靠它。 */
    override val chapters: List<PageLoader.ChapterEntry>
        get() = album.series
            .sortedBy { it.sort }
            .map { PageLoader.ChapterEntry(it.chapterId, it.name.ifBlank { "第 ${it.sort} 話" }, it.sort) }

    override val currentChapterIndex: Int
        get() = chapters.indexOfFirst { it.id == chapter.chapterId }

    override fun switchChapter(chapterId: Long) = onSwitchChapter(chapterId)

    private fun fileName(index: Int) = chapter.images[index]

    private fun scrambleNum(index: Int) = JmApi.scrambleNum(chapter.chapterId, fileName(index), scrambleId)

    override fun getImageExtension(index: Int): String = "jpg"

    override fun save(index: Int, file: Path): Boolean = runCatching {
        val source = JmImages.pageFile(album.albumId, chapter.chapterId, index)
        if (!source.exists()) return false
        file.delete()
        source sendTo file
        true
    }.getOrDefault(false)

    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
        if (index !in chapter.images.indices) {
            notifyPageFailed(index, "Invalid page")
            return
        }
        if (jobs[index]?.isActive == true) return
        jobs[index] = scope.launch {
            runCatching {
                JmImages.ensure(album.albumId, chapter.chapterId, index, fileName(index), scrambleNum(index))
            }.onSuccess {
                notifySourceReady(index)
            }.onFailure {
                if (it is CancellationException) return@onFailure
                notifyPageFailed(index, it.displayString())
            }
        }
    }

    override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
        pages.forEach { onRequest(it) }
    }

    override fun openSource(index: Int): ImageSource = object : PathSource {
        override val source = JmImages.pageFile(album.albumId, chapter.chapterId, index)
        override val type = "jpg"
        override fun close() = Unit
    }

    override fun saveReadProgress(index: Int) {
        JmHistory.saveProgress(
            albumId = album.albumId,
            title = album.name,
            chapterId = chapter.chapterId,
            chapterName = chapter.name,
            chapterSort = chapterSort,
            chapters = album.series.size.coerceAtLeast(1),
            pages = chapter.images.size,
            page = index,
        )
    }
}

/** 打开阅读器前把本子、章节、打乱段数几样东西凑齐。 */
suspend inline fun <T> useJmPageLoader(
    albumId: Long,
    chapterId: Long,
    startPage: Int,
    noinline onSwitchChapter: (Long) -> Unit = {},
    crossinline block: suspend (PageLoader) -> T,
): T = coroutineScope {
    val album = JmCache.albumOrLoad(albumId)
    val chapter = JmCache.chapterOrLoad(chapterId)
    require(chapter.images.isNotEmpty()) { "Chapter has no pages" }
    val scrambleId = runCatching { JmApi.scrambleId(chapterId) }.getOrDefault(220980L)
    val sort = album.series.firstOrNull { it.chapterId == chapterId }?.sort
        ?: album.series.indexOfFirst { it.chapterId == chapterId }.plus(1).coerceAtLeast(1)
    JmHistory.record(album)
    block(JmPageLoader(this, album, chapter, sort, scrambleId, startPage, onSwitchChapter))
}
