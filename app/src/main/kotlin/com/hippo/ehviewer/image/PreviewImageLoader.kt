package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.util.AppLog
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import splitties.init.appCtx

private const val TAG = "PreviewImage"

/**
 * E 站的页面预览图。
 *
 * 详情页预览网格用的就是这份数据：[GalleryPreview] 指向一张 sprite（一张图里横排着好几页的缩略图），
 * 每页的位置由 `offsetX` / `clipWidth` / `clipHeight` 给出；V1 是一页一张小图，不用裁。
 *
 * 阅读器拿它当「先看个大概」的占位图。图片自己下载、自己解码，不走 Coil：
 * Coil 链上的 [com.hippo.ehviewer.coil.HardwareBitmapInterceptor] 会把结果换成 hardware bitmap
 * 并回收原图，拿到的往往不是 BitmapImage，复用它的位图很容易踩「recycled bitmap」。
 *
 * 这里只缓存 sprite 的**字节**（几十 KB 到几 MB），每次按页解码再裁一刀：
 * 每页都拿到一张独立的位图，交出去的 [Image] 回收时也只回收自己那张，不会互相影响。
 */
object PreviewImageLoader {

    /** sprite 原始字节缓存，够放一两次阅读的量。 */
    private val sprites = object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun load(preview: GalleryPreview): Image? = try {
        val bytes = bytes(preview.url)
        if (bytes == null) {
            null
        } else {
            val sprite = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (sprite == null) {
                AppLog.w(TAG, "preview ${preview.position} decode failed: ${preview.url}")
                null
            } else {
                val cropped = crop(preview, sprite)
                if (cropped == null) {
                    sprite.recycle()
                    null
                } else {
                    if (cropped !== sprite) sprite.recycle()
                    Image.ofBitmap(cropped)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.w(TAG, "preview ${preview.position} failed: ${preview.url}", e)
        null
    }

    private suspend fun bytes(url: String): ByteArray? {
        sprites.get(url)?.let { return it }
        // 同一张 sprite 会被几十页共用，预热时又是并发拉，串行化避免重复下载
        val lock = locks.getOrPut(url) { Mutex() }
        return lock.withLock {
            sprites.get(url)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                runCatching {
                    val disk = diskFile(url)
                    val cached = if (disk.isFile) disk.readBytes() else null
                    if (cached != null && cached.isNotEmpty()) {
                        sprites.put(url, cached)
                        return@runCatching cached
                    }
                    val response = ktorClient.get(url)
                    if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                    response.body<ByteArray>().also { data ->
                        sprites.put(url, data)
                        runCatching {
                            disk.parentFile?.mkdirs()
                            disk.writeBytes(data)
                        }
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    AppLog.w(TAG, "sprite download failed: $url", it)
                }.getOrNull()
            }
        }
    }

    /** sprite 落盘一份，第二次进同一本、或者同一张 sprite 被多页共用时就不用再下。 */
    private fun diskFile(url: String): File {
        val name = url.hashCode().toUInt().toString(16) + "-" + url.length + ".bin"
        return File(appCtx.cacheDir, "eh_preview/$name")
    }

    private fun crop(preview: GalleryPreview, sprite: Bitmap): Bitmap? = when (preview) {
        is V2GalleryPreview -> runCatching {
            val x = preview.offsetX.coerceIn(0, (sprite.width - 1).coerceAtLeast(0))
            val width = preview.clipWidth.coerceIn(1, (sprite.width - x).coerceAtLeast(1))
            val height = preview.clipHeight.coerceIn(1, sprite.height)
            Bitmap.createBitmap(sprite, x, 0, width, height)
        }.getOrNull()
        else -> sprite
    }
}
