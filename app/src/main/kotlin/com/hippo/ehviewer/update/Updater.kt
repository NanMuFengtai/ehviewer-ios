package com.hippo.ehviewer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.util.AppLog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 自更新：读 GitHub Release，拿最新 APK，校验后交给系统安装器。
 *
 * 版本比较不看 versionCode：发布 tag 固定是 `build-<本包的 COMMIT_TIME>`，
 * 和 BuildConfig.COMMIT_TIME 比大小即可（相等就是当前版本）。
 * 所以发版脚本必须针对已提交的构建发布，tag 不能手改成别的格式。
 */
object Updater {
    private const val TAG = "Updater"
    const val DEFAULT_REPO = "NanMuFengtai/ehviewer-apk"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 一条可安装的发布。 */
    data class Release(
        val buildTime: Long,
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String?,
    ) {
        val displayTime: String
            get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(buildTime * 1000))
    }

    @Serializable
    private data class ApiRelease(
        val tag_name: String = "",
        val name: String = "",
        val body: String = "",
        @SerialName("published_at") val publishedAt: String = "",
        val assets: List<ApiAsset> = emptyList(),
    )

    @Serializable
    private data class ApiAsset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
        val size: Long = 0,
    )

    /** 当前安装包对应的构建时间（秒）。 */
    fun currentBuildTime(): Long = BuildConfig.COMMIT_TIME

    /** 这版 App 的展示信息。 */
    fun currentVersionLine(): String = "${BuildConfig.RAW_VERSION_NAME} · ${BuildConfig.COMMIT_SHA}"

    /**
     * 查最新发布。repo 形如 `owner/name`。
     * 返回 null 表示该仓库还没有任何 release。
     */
    suspend fun checkLatest(repo: String): Release? = withContext(Dispatchers.IO) {
        val clean = repo.trim().trimEnd('/').removePrefix("https://github.com/")
        require(clean.count { it == '/' } == 1) { "更新源格式应为 owner/repo" }
        val url = "https://api.github.com/repos/$clean/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "EhViewer-Update/${BuildConfig.COMMIT_SHA}")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub ${response.code}")
            val release = json.decodeFromString<ApiRelease>(text)
            val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: throw IOException("这个发布里没有 APK")
            Release(
                buildTime = parseBuildTime(release.tag_name, release.publishedAt),
                tag = release.tag_name,
                title = release.name.ifBlank { release.tag_name },
                notes = release.body,
                apkUrl = asset.downloadUrl,
                apkSize = asset.size,
                sha256 = parseSha256(release.body),
            )
        }
    }

    /** tag 形如 build-1795000000；解析不了就退回发布时间。 */
    private fun parseBuildTime(tag: String, publishedAt: String): Long {
        tag.removePrefix("build-").toLongOrNull()?.let { return it }
        return runCatching {
            java.time.Instant.parse(publishedAt).epochSecond
        }.getOrDefault(0L)
    }

    /** 发布说明里写一行 `SHA256: <hex>` 就能做完整性校验。 */
    private fun parseSha256(body: String): String? = Regex("(?im)^\\s*SHA256\\s*:\\s*([0-9a-f]{64})\\s*$")
        .find(body)?.groupValues?.get(1)?.lowercase()

    fun isNewer(release: Release): Boolean = release.buildTime > currentBuildTime()

    /** 下载到 cacheDir/update/update.apk，支持进度回调与取消（协程取消即可）。 */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "update.apk")
        if (target.exists()) target.delete()
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "EhViewer-Update/${BuildConfig.COMMIT_SHA}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下載失敗：HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        // 协程取消后要立刻停：否则 OkHttp 的阻塞读会继续下完整包
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReport > 512 * 1024 || downloaded == total) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
        }
        AppLog.i(TAG, "downloaded ${target.length()} bytes -> ${target.absolutePath}")
        target
    }

    /** 大小 + sha256（有的话）双校验。 */
    suspend fun verify(file: File, release: Release): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0) return@withContext false
        if (release.apkSize > 0 && file.length() != release.apkSize) {
            AppLog.w(TAG, "size mismatch ${file.length()} != ${release.apkSize}")
            return@withContext false
        }
        val expected = release.sha256 ?: return@withContext true
        val actual = sha256(file)
        if (actual != expected) {
            AppLog.w(TAG, "sha256 mismatch $actual != $expected")
            return@withContext false
        }
        true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 交给系统安装器；用户第一次需要允许「安装未知应用」。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
