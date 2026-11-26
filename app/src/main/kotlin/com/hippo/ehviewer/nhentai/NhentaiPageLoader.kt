package com.hippo.ehviewer.nhentai

import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.sendTo
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.util.displayString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okio.Path

/**
 * nhentai 的阅读器页加载器。
 *
 * 复用 E 站的 [PageLoader]：内存缓存、前后预取、解码、翻页手势全都一样，
 * 只有"从哪弄到这一页的字节"不同 —— 已下载的读本地文件，没下载的按详情给的路径下载。
 */
class NhentaiPageLoader(
    scope: CoroutineScope,
    val detail: NhentaiApi.Detail,
    startPage: Int,
) : PageLoader(scope, null, startPage, detail.pages.size) {

    private val jobs = mutableMapOf<Int, Job>()

    override val title: String = detail.displayTitle.ifBlank { "nhentai #${detail.id}" }

    private fun pathOf(index: Int) = detail.pages[index].path

    private fun extension(index: Int) = NhentaiImages.extensionOf(pathOf(index))

    /** 已下载优先，其次阅读缓存。 */
    private fun sourceOf(index: Int): Path =
        NhentaiDownloads.localPage(detail.id, index, pathOf(index))
            ?: NhentaiImages.file(detail.id, index, extension(index))

    override fun getImageExtension(index: Int): String? =
        detail.pages.getOrNull(index)?.let { extension(index) }

    override fun save(index: Int, file: Path): Boolean = runCatching {
        val source = sourceOf(index)
        if (!source.exists()) return false
        file.delete()
        source sendTo file
        true
    }.getOrDefault(false)

    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
        if (index !in detail.pages.indices) {
            notifyPageFailed(index, "Invalid page")
            return
        }
        if (force) {
            jobs.remove(index)?.cancel()
            NhentaiImages.file(detail.id, index, extension(index)).delete()
        }
        if (jobs[index]?.isActive == true) return
        jobs[index] = scope.launch {
            runCatching {
                NhentaiImages.ensure(detail.id, index, pathOf(index))
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
        override val source = sourceOf(index)
        override val type = extension(index)
        override fun close() = Unit
    }

    override val autoSaveProgress: Boolean get() = true

    override fun saveReadProgress(index: Int) {
        NhentaiHistory.saveProgress(detail, index)
    }

    /**
     * 先垫一张缩略图。
     *
     * nhentai 的详情里每页都带 `thumbnail`，比原图小一个数量级，走图床通常几百毫秒就能拿到。
     * 已经下载到本地的页（离线可读）直接返回 null —— 原图本来就在磁盘上，不需要垫图。
     */
    override suspend fun loadPreview(index: Int): Image? {
        val page = detail.pages.getOrNull(index) ?: return null
        if (page.thumbnail.isBlank()) return null
        if (NhentaiDownloads.localPage(detail.id, index, page.path) != null) return null
        val cached = NhentaiImages.file(detail.id, index, extension(index))
        if (cached.exists()) return null
        val path = NhentaiImages.ensureThumb(detail.id, index, page.thumbnail) ?: return null
        return runCatching {
            Image.decode(
                object : PathSource {
                    override val source = path
                    override val type = NhentaiImages.extensionOf(page.thumbnail)
                    override fun close() = Unit
                },
            )
        }.getOrNull()
    }
}

/**
 * 拿详情交给 [NhentaiPageLoader]。
 *
 * 顺序：已下载（离线也能读）→ 内存缓存 → 打接口。阅读器打开时不再重复请求详情。
 */
suspend inline fun <T> useNhentaiPageLoader(
    id: Long,
    startPage: Int,
    crossinline block: suspend (PageLoader) -> T,
): T = coroutineScope {
    val detail = NhentaiDownloads.completedDetail(id)?.takeIf { it.pages.isNotEmpty() }
        ?: NhentaiDetailCache.get(id)
        ?: NhentaiApi.detail(id).also { NhentaiDetailCache.put(it) }
    require(detail.pages.isNotEmpty()) { "Gallery has no pages" }
    // 直接从下载页/历史进阅读器时，详情页可能还没记过历史，这里先补一条，进度才有地方落
    NhentaiHistory.record(detail)
    block(NhentaiPageLoader(this, detail, startPage))
}
