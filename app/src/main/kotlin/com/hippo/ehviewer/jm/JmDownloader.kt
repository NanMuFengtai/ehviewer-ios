package com.hippo.ehviewer.jm

import com.hippo.ehviewer.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "JmDownloader"

/**
 * 把一话的页图全部落到缓存里（下载 + 去乱序 + 存正常 JPEG）。
 *
 * 只做「缓存预热」级别：目标目录就是阅读器用的 `cache/jm4/<本子>/<章节>/`，
 * 下载完直接离线可读。不是正式下载管理（没有断点记录、没有导出）。
 */
object JmDownloader {
    /** 返回成功下载的页数。 */
    suspend fun downloadChapter(
        albumId: Long,
        chapterId: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val chapter = JmCache.chapterOrLoad(chapterId)
        val images = chapter.images
        if (images.isEmpty()) return@withContext 0
        val scrambleId = runCatching { JmApi.scrambleId(chapterId) }.getOrDefault(220980L)
        var done = 0
        onProgress(0, images.size)
        images.forEachIndexed { index, fileName ->
            runCatching {
                JmImages.ensure(
                    albumId = albumId,
                    chapterId = chapterId,
                    index = index,
                    fileName = fileName,
                    scrambleNum = JmApi.scrambleNum(chapterId, fileName, scrambleId),
                )
            }.onSuccess {
                done += 1
                onProgress(done, images.size)
            }.onFailure {
                AppLog.w(TAG, "download page ${index + 1} failed: ${it.message}")
            }
        }
        AppLog.i(TAG, "chapter $chapterId downloaded $done/${images.size}")
        done
    }
}
