package com.hippo.ehviewer.jm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.hippo.ehviewer.util.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

private const val TAG = "JmImages"

/**
 * 禁漫的图片缓存。
 *
 * 图片本身是「上下几段打乱」的（段数由 [JmApi.scrambleNum] 算），所以要下载后重排、
 * 存成一张正常的 JPEG 再交给阅读器。解码、重排都在 IO 线程做。
 */
object JmImages {
    /**
     * v4：临时原图文件曾经全章节共用 `raw.bin`，而预热是 12 路并发 ——
     * 各页互相覆盖，A 页读到的其实是 B 页下的图，按自己的段数切完就是错位乱版。
     * 缓存目录跟着换，旧碎图整体失效。
     */
    private val root: Path = appCtx.cacheDir.toOkioPath() / "jm4"

    private fun dir(albumId: Long, chapterId: Long): Path = root / "$albumId" / "$chapterId"

    fun pageFile(albumId: Long, chapterId: Long, index: Int): Path = dir(albumId, chapterId) / "${index + 1}.jpg"

    /** 原图（打乱的）先落个临时文件，处理完删掉。**每页一个**，否则并发时会互相覆盖。 */
    private fun rawFile(albumId: Long, chapterId: Long, index: Int): Path =
        dir(albumId, chapterId) / "raw_$index.bin"

    fun coverFile(albumId: Long): Path = root / "$albumId" / "cover.jpg"

    /** 拿到一页正常方向的图片文件；已经处理过就直接返回。 */
    suspend fun ensure(
        albumId: Long,
        chapterId: Long,
        index: Int,
        fileName: String,
        scrambleNum: Int,
    ): Path = withContext(Dispatchers.IO) {
        val target = pageFile(albumId, chapterId, index)
        if (target.exists()) return@withContext target
        val raw = rawFile(albumId, chapterId, index)
        try {
            download(raw, JmApi.imageCandidates(chapterId, fileName))
            val bytes = raw.toFile().readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("图片解码失败")
            val fixed = if (scrambleNum > 1) descramble(bitmap, scrambleNum) else bitmap
            if (fixed !== bitmap) bitmap.recycle()
            AppLog.i(TAG, "page ${index + 1} descrambled num=$scrambleNum ${fixed.width}x${fixed.height}")
            val out = ByteArrayOutputStream()
            fixed.compress(Bitmap.CompressFormat.JPEG, 92, out)
            fixed.recycle()
            target.parent?.mkdirs()
            target.toFile().writeBytes(out.toByteArray())
            target
        } finally {
            raw.delete()
        }
    }

    /** 封面：/media/albums/<本子id>.jpg，不需要打乱。 */
    suspend fun ensureCover(albumId: Long): Path? = withContext(Dispatchers.IO) {
        runCatching {
            val target = coverFile(albumId)
            if (target.exists()) return@runCatching target
            download(target, JmApi.coverCandidates(albumId))
            target
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.w(TAG, "cover $albumId failed: ${it.message}", it)
        }.getOrNull()
    }

    private fun descramble(src: Bitmap, num: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || num <= 1) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val over = h % num
        for (i in 0 until num) {
            val move = h / num
            val srcY = h - move * (i + 1) - over
            var dstY = move * i
            val sliceHeight = if (i == 0) move + over else move
            if (i != 0) dstY += over
            if (srcY < 0 || srcY + sliceHeight > h || dstY + sliceHeight > h) continue
            canvas.drawBitmap(
                src,
                Rect(0, srcY, w, srcY + sliceHeight),
                Rect(0, dstY, w, dstY + sliceHeight),
                null,
            )
        }
        return out
    }

    internal suspend fun download(target: Path, urls: List<String>): Path {
        val dir = target.parent ?: root
        dir.mkdirs()
        var last: Throwable? = null
        for (url in urls) {
            val temp = dir / (target.name + ".part")
            try {
                val bytes = withContext(Dispatchers.IO) {
                    JmHttp.getBytes(
                        url,
                        mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36",
                            "Referer" to "https://18comic.vip/",
                        ),
                    )
                }
                temp.toFile().writeBytes(bytes)
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
