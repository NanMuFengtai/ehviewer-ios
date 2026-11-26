package com.hippo.ehviewer.ios.source

import com.hippo.ehviewer.ios.crypto.Aes
import com.hippo.ehviewer.ios.crypto.Md5
import com.hippo.ehviewer.ios.net.AppJson
import com.hippo.ehviewer.ios.net.NetException
import com.hippo.ehviewer.ios.net.httpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

/**
 * 禁漫的移动端接口。
 *
 * 协议要点：
 * - `ts` 取秒级时间戳，`token = md5("$ts$SECRET")`，两个都放请求头
 * - 响应外层是 `{code, data}`，data 是 base64 的 AES-256-ECB 密文
 * - 解密密钥就是 token 的 32 个 ASCII 字符；明文末尾带 padding 长度
 *
 * Kotlin/Native 上没有 javax.crypto，加解密走自写的 Aes / Md5。
 */
@OptIn(ExperimentalEncodingApi::class)
object JmSource {
    private const val SECRET = "185Hcomic3PAPP7R"
    private const val APP_VERSION = "2.1.13"

    val hosts = listOf(
        "www.cdnhjk.net",
        "www.cdngwc.cc",
        "www.cdngwc.net",
        "www.cdngwc.club",
    )

    val imageHosts = listOf(
        "cdn-msp3.18comic.vip",
        "cdn-msp2.18comic.vip",
        "cdn-msp.18comic.vip",
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapinodeudzn.net",
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"

    val categories = listOf(
        "全部" to "",
        "同人" to "doujin",
        "單本" to "single",
        "短篇" to "short",
        "韓漫" to "hanman",
        "其他" to "another",
        "3D" to "3D",
    )

    val orders = listOf(
        "最新" to "mr",
        "熱門" to "mv",
        "週榜" to "mv_w",
        "月榜" to "mv_m",
    )

    /** 登录后的 cookie，和 Android 侧一致。 */
    var cookie: String = ""

    // ---------- 加解密 ----------

    private fun nowSeconds(): Long = Clock.System.now().epochSeconds

    private fun tokenFor(ts: Long): String = Md5.hex("$ts$SECRET")

    private fun decrypt(payload: String, ts: Long): String {
        val key = tokenFor(ts).encodeToByteArray()
        val cipher = Base64.decode(payload)
        val plain = Aes.decryptEcb(key, cipher)
        val pad = plain.last().toInt() and 0xFF
        val end = plain.size - pad.coerceIn(0, 32)
        return plain.decodeToString(0, end)
    }

    // ---------- 请求 ----------

    private suspend fun getJson(path: String, params: Map<String, String> = emptyMap()): String {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                val ts = nowSeconds()
                val token = tokenFor(ts)
                val query = if (params.isEmpty()) {
                    ""
                } else {
                    "?" + params.entries.joinToString("&") { (k, v) ->
                        "${encode(k)}=${encode(v)}"
                    }
                }
                val response = httpClient.get("https://$host$path$query") {
                    header("User-Agent", UA)
                    header("token", token)
                    header("tokenparam", "$ts,$APP_VERSION")
                    header("Accept", "application/json")
                    if (cookie.isNotBlank()) header("Cookie", cookie)
                }
                val text = response.bodyAsText()
                if (!response.status.isSuccess()) throw NetException("HTTP ${response.status.value}")
                val data = envelopePayload(text) ?: return "{}"
                return decrypt(data, ts)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                last = t
            }
        }
        throw last ?: NetException("禁漫接口不可用")
    }

    @Serializable
    private data class Envelope(val code: Int = 0, val data: kotlinx.serialization.json.JsonElement? = null)

    private fun envelopePayload(text: String): String? {
        val env = runCatching { AppJson.decodeFromString<Envelope>(text) }.getOrNull() ?: return null
        val prim = env.data as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (!prim.isString) return null
        return prim.content.takeIf { it.isNotBlank() }
    }

    // ---------- 数据 ----------

    @Serializable
    data class Item(
        val id: String = "",
        val name: String = "",
        val author: String = "",
        val image: String = "",
        val category: Category = Category(),
        val update_at: Long = 0,
        val adddate: String = "",
    ) {
        val albumId: Long get() = id.toLongOrNull() ?: 0L
    }

    @Serializable
    data class Category(val id: String = "", val title: String = "")

    @Serializable
    data class PageModel(
        val total: Int = 0,
        val content: List<Item> = emptyList(),
    )

    @Serializable
    data class Chapter(val id: String = "", val name: String = "", val sort: Int = 0) {
        val chapterId: Long get() = id.toLongOrNull() ?: 0L
    }

    @Serializable
    data class Album(
        val id: String = "",
        val name: String = "",
        val images: List<String> = emptyList(),
        val total_photos: Int = 0,
        val total_views: String = "",
        val likes: String = "",
        val comment_total: String = "",
        val is_favorite: Boolean = false,
        val liked: Boolean = false,
        val description: String = "",
        val author: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val series: List<Chapter> = emptyList(),
        val series_id: String = "0",
    ) {
        val albumId: Long get() = id.toLongOrNull() ?: 0L
        val authorText: String get() = author.joinToString(" / ")
        val pageCount: Int get() = if (total_photos > 0) total_photos else images.size
        val isSeries: Boolean get() = series.size > 1
    }

    @Serializable
    data class ChapterPage(
        val id: String = "",
        val name: String = "",
        val images: List<String> = emptyList(),
        val series: List<Chapter> = emptyList(),
        val tags: String = "",
    ) {
        val chapterId: Long get() = id.toLongOrNull() ?: 0L
    }

    // ---------- 接口 ----------

    suspend fun list(
        page: Int,
        category: String = "",
        order: String = "mr",
        query: String = "",
    ): PageModel {
        val params = mutableMapOf("page" to "$page")
        if (query.isNotBlank()) {
            // 单个字符直接搜会回 total=0，得加通配符
            params["search_query"] = if (query.length == 1) "*$query*" else query
            params["o"] = order
            return AppJson.decodeFromString(getJson("/search", params))
        }
        if (category.isNotEmpty()) params["c"] = category
        params["order"] = ""
        params["o"] = order
        return AppJson.decodeFromString(getJson("/categories/filter", params))
    }

    suspend fun album(albumId: Long): Album =
        AppJson.decodeFromString(getJson("/album", mapOf("id" to "$albumId")))

    suspend fun chapter(chapterId: Long): ChapterPage =
        AppJson.decodeFromString(getJson("/chapter", mapOf("id" to "$chapterId")))

    /** 封面：/media/albums/<本子id>.jpg */
    fun coverUrl(albumId: Long): String = "https://${imageHosts.first()}/media/albums/$albumId.jpg"

    fun coverCandidates(albumId: Long): List<String> =
        imageHosts.map { "https://$it/media/albums/$albumId.jpg" }

    /** 图片：/media/photos/<章节id>/<文件名> */
    fun imageUrl(chapterId: Long, fileName: String): String =
        "https://${imageHosts.first()}/media/photos/$chapterId/$fileName"

    fun imageCandidates(chapterId: Long, fileName: String): List<String> =
        imageHosts.map { "https://$it/media/photos/$chapterId/$fileName" }

    /**
     * 图片被打乱成几段（数值 = 段数），0 表示没打乱。
     *
     * 规则照官方：本子 id 小于 scramble_id 不打乱；小于 268850 固定 10 段；
     * 之后 num = (md5(aid + 去后缀文件名) 末位的 ASCII 码 % x) * 2 + 2，
     * x 在 421926 之前 10、之后 8。
     */
    fun scrambleNum(chapterId: Long, fileName: String, scrambleId: Long): Int = when {
        chapterId < scrambleId -> 0
        chapterId < 268850L -> 10
        else -> {
            val x = if (chapterId < 421926L) 10 else 8
            val hash = Md5.hex("$chapterId${fileName.substringBeforeLast('.')}")
            ((hash.last().code % x) * 2 + 2)
        }
    }

    /** 从模板页里取 scramble_id（这是个 HTML 页面）。 */
    suspend fun scrambleId(chapterId: Long): Long {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                val ts = nowSeconds()
                val token = tokenFor(ts)
                val response = httpClient.get("https://$host/chapter_view_template?id=$chapterId") {
                    header("User-Agent", UA)
                    header("token", token)
                    header("tokenparam", "$ts,$APP_VERSION")
                }
                val html = response.bodyAsText()
                Regex("var scramble_id = (\\d+);").find(html)
                    ?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
                last = NetException("scramble_id not found")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                last = t
            }
        }
        throw last ?: NetException("拿不到 scramble_id")
    }

    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            val ch = v.toChar()
            if (ch.isLetterOrDigit() || ch in "-_.~") {
                append(ch)
            } else {
                append('%')
                append("0123456789ABCDEF"[(v ushr 4) and 0xF])
                append("0123456789ABCDEF"[v and 0xF])
            }
        }
    }
}
