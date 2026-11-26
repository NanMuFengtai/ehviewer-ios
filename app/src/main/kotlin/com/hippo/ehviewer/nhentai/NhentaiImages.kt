package com.hippo.ehviewer.nhentai

import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.util.AppLog
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

private const val TAG = "NhentaiImages"

/**
 * nhentai 的图片缓存。
 *
 * 不参与下载管理，只是阅读用的磁盘缓存：`cacheDir/nhentai/<galleryId>/<页码>.<ext>`。
 * 服务端支持 Range，但这里一次拉完整张图、写完再原子改名，避免半截文件被解码。
 */
object NhentaiImages {
    private val root: Path = appCtx.cacheDir.toOkioPath() / "nhentai"

    fun file(id: Long, index: Int, ext: String): Path = root / "$id" / "${index + 1}.$ext"

    fun extensionOf(path: String): String {
        val ext = NhentaiCdn.normalize(path).substringAfterLast('.', "jpg").lowercase()
        return if (ext.length in 2..5) ext else "jpg"
    }

    suspend fun ensure(id: Long, index: Int, path: String): Path = withContext(Dispatchers.IO) {
        // 整本下载过的直接读本地
        NhentaiDownloads.localPage(id, index, path)?.let { return@withContext it }
        val ext = extensionOf(path)
        val target = file(id, index, ext)
        if (target.exists()) return@withContext target
        download(target, NhentaiCdn.imageCandidates(path))
    }

    /**
     * 详情页封面。
     *
     * 不走 Coil 的 Ktor fetcher：Cronet 在代理下对这条请求会抛
     * `ERR_HTTP2_PROTOCOL_ERROR`，同一个地址用自建下载器就没问题。
     * 单台图床偶发超时，所以按 i1→i4 依次重试，再退到缩略图服务器，
     * 全挂才返回 null（界面会再回退一次缩略图地址）。
     */
    suspend fun ensureCover(id: Long, path: String, thumbnail: String = ""): Path? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = extensionOf(path)
            val target = root / "$id" / "cover.$ext"
            if (target.exists()) return@runCatching target
            val candidates = buildList {
                addAll(NhentaiCdn.imageCandidates(path))
                if (thumbnail.isNotBlank()) addAll(NhentaiCdn.thumbCandidates(thumbnail))
            }
            download(target, candidates)
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.w(TAG, "cover of $id failed: ${it.displayMessage()}", it)
        }.getOrNull()
    }

    /** 某一页的缩略图，阅读器「先看预览图」用；拿不到返回 null。 */
    suspend fun ensureThumb(id: Long, index: Int, path: String): Path? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = extensionOf(path)
            val target = root / "$id" / "preview-${index + 1}.$ext"
            if (target.exists()) return@runCatching target
            download(target, NhentaiCdn.thumbCandidates(path))
        }.getOrNull()
    }

    internal suspend fun download(target: Path, urls: List<String>): Path {
        val dir = target.parent ?: root
        dir.mkdirs()
        var last: Throwable? = null
        for (url in urls) {
            val temp = dir / (target.name + ".part")
            try {
                val response = ktorClient.get(url)
                if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}")
                response.bodyAsChannel().copyTo(temp)
                target.delete()
                temp moveTo target
                return target
            } catch (throwable: Throwable) {
                temp.delete()
                if (throwable is CancellationException) throw throwable
                last = throwable
            }
        }
        throw last ?: IOException("No image server available")
    }
}

private fun Throwable.displayMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
