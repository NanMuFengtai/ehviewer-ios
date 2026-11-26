package com.hippo.ehviewer.jm

import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val jmJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

private const val SECRET = "185Hcomic3PAPP7R"
private const val APP_VERSION = "2.1.13"

/** 禁漫的移动端接口，走 CDN 域名 + token 头；返回值是 AES 加密的 JSON。 */
object JmApi {
    /** 接口域名（顺序即回退顺序）。 */
    val hosts = listOf(
        "www.cdnhjk.net",
        "www.cdngwc.cc",
        "www.cdngwc.net",
        "www.cdngwc.club",
    )

    /** 图床域名（顺序即回退顺序）。 */
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

    /** 分类：value 是接口参数，中文名给界面用。 */
    val categories = listOf(
        "全部" to "",
        "同人" to "doujin",
        "單本" to "single",
        "短篇" to "short",
        "韓漫" to "hanman",
        "其他" to "another",
        "3D" to "3D",
    )

    /** 排序：o 参数。 */
    val orders = listOf(
        "最新" to "mr",
        "熱門" to "mv",
        "週榜" to "mv_w",
        "月榜" to "mv_m",
    )

    const val CATEGORY_ALL = ""

    @Serializable
    data class Category(val id: String = "", val title: String = "")

    @Serializable
    data class AlbumItem(
        val id: String = "",
        val name: String = "",
        val author: String = "",
        val image: String = "",
        val category: Category = Category(),
        val category_sub: Category = Category(),
        val update_at: Long = 0,
        val adddate: String = "",
    ) {
        val albumId: Long get() = id.toLongOrNull() ?: 0L
    }

    @Serializable
    data class Page(
        val search_query: String = "",
        val total: Int = 0,
        val content: List<AlbumItem> = emptyList(),
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

    // ---- 请求层 ----

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 解密接口返回：key = md5(ts + secret) 的字符串，AES-ECB，末尾是 padding 长度。 */
    private fun decrypt(data: String, ts: Long): String {
        val key = md5Hex("$ts$SECRET").toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val plain = cipher.doFinal(java.util.Base64.getDecoder().decode(data))
        val pad = plain.last().toInt() and 0xFF
        return String(plain, 0, plain.size - pad.coerceIn(0, 32), Charsets.UTF_8)
    }

    private suspend fun getJson(path: String, params: Map<String, String> = emptyMap()): String {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                val ts = System.currentTimeMillis() / 1000
                val token = md5Hex("$ts$SECRET")
                val query = if (params.isEmpty()) {
                    ""
                } else {
                    "?" + params.entries.joinToString("&") { (k, v) ->
                        "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                    }
                }
                val headers = mutableMapOf(
                    "User-Agent" to UA,
                    "token" to token,
                    "tokenparam" to "$ts,$APP_VERSION",
                    "Accept" to "application/json",
                )
                if (JmAuth.cookie.isNotBlank()) headers["Cookie"] = JmAuth.cookie
                val text = withContext(Dispatchers.IO) {
                    JmHttp.getText("https://$host$path$query", headers)
                }
                val data = envelopePayload(text) ?: return "{}"
                return decrypt(data, ts)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: IOException("禁漫接口不可用")
    }

    /** POST 表单，返回值同样是加密 JSON。 */
    private suspend fun postJson(path: String, form: Map<String, String>): String {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                val ts = System.currentTimeMillis() / 1000
                val token = md5Hex("$ts$SECRET")
                val headers = mutableMapOf(
                    "User-Agent" to UA,
                    "token" to token,
                    "tokenparam" to "$ts,$APP_VERSION",
                    "Accept" to "application/json",
                )
                if (JmAuth.cookie.isNotBlank()) headers["Cookie"] = JmAuth.cookie
                val result = withContext(Dispatchers.IO) {
                    JmHttp.request("https://$host$path", headers, form)
                }
                if (result.code !in 200..299) throw IOException("HTTP ${result.code}")
                val text = result.body.toString(Charsets.UTF_8)
                val data = envelopePayload(text) ?: throw IOException("禁漫接口返回空数据")
                return decrypt(data, ts)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: IOException("禁漫接口不可用")
    }

    @Serializable
    private data class Envelope(val code: Int = 0, val data: JsonElement? = null)

    /**
     * 从响应里取出加密串。
     *
     * 搜不到东西时禁漫会回 `{"code":200,"data":[]}` —— data 是个空数组而不是字符串，
     * 这时候直接当「没有数据」处理，别让它把整页炸成 JSON 解析错误。
     */
    private fun envelopePayload(text: String): String? {
        val envelope = jmJson.decodeFromString<Envelope>(text)
        val primitive = envelope.data as? JsonPrimitive
        if (primitive == null || !primitive.isString) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    /** 列表 / 分类 / 搜索。 */
    suspend fun list(page: Int, category: String = CATEGORY_ALL, order: String = "mr", query: String = ""): Page {
        val params = mutableMapOf("page" to "$page")
        if (query.isNotBlank()) {
            // 禁漫对单个字符的关键词直接回 total=0（J、S、雲 实测都是），必须加通配符才搜得到
            params["search_query"] = if (query.length == 1) "*$query*" else query
            params["o"] = order
            return jmJson.decodeFromString(getJson("/search", params))
        }
        if (category.isNotEmpty()) params["c"] = category
        params["order"] = ""
        params["o"] = order
        return jmJson.decodeFromString(getJson("/categories/filter", params))
    }

    suspend fun album(albumId: Long): Album =
        jmJson.decodeFromString(getJson("/album", mapOf("id" to "$albumId")))

    suspend fun chapter(chapterId: Long): ChapterPage =
        jmJson.decodeFromString(getJson("/chapter", mapOf("id" to "$chapterId")))

    /** 图片来源：图床 + /media/photos/<章节id>/<文件名>。 */
    fun imageCandidates(chapterId: Long, fileName: String): List<String> =
        imageHosts.map { "https://$it/media/photos/$chapterId/$fileName" }

    /** 封面：/media/albums/<本子id>.jpg */
    fun coverCandidates(albumId: Long): List<String> =
        imageHosts.map { "https://$it/media/albums/$albumId.jpg" }

    /**
     * 图片被打乱成几段（数值 = 段数），0 表示没打乱。
     *
     * 规则完全照官方的 get_num / get_num_by_detail：本子 id 小于 scramble_id 不打乱；小于
     * 268850 固定 10 段；之后 num = (md5(aid + 去后缀文件名) 末位的 **ASCII 码** % x) * 2 + 2，
     * x 在 421926 之前是 10、之后是 8。
     *
     * 两个坑（都踩过）：
     * 1. 末位取的是字符的 ASCII 值（'f' = 102），不是把它当十六进制数字（15）；
     * 2. 这里的 [chapterId] 是**章节 id**，对应图片 URL `/media/photos/<章节id>/`，
     *    不是专辑 id。官方 `JmImageDetail` 的 `aid` 就是 `photo_id`。单话本两者相同，
     *    多话本（比如《特色新視界》专辑 1225495、第 2 話 1225497）用错就会整页乱版。
     */
    fun scrambleNum(chapterId: Long, fileName: String, scrambleId: Long): Int = when {
        chapterId < scrambleId -> 0
        chapterId < 268850L -> 10
        else -> {
            val x = if (chapterId < 421926L) 10 else 8
            val hash = md5Hex("$chapterId${fileName.substringBeforeLast('.')}")
            ((hash.last().code % x) * 2 + 2)
        }
    }

    /** 从章节模板页里取 scramble_id（这是个 HTML 页面，不是 JSON）。 */
    suspend fun scrambleId(chapterId: Long): Long {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                val ts = System.currentTimeMillis() / 1000
                val token = md5Hex("$ts$SECRET")
                val html = withContext(Dispatchers.IO) {
                    JmHttp.getText(
                        "https://$host/chapter_view_template?id=$chapterId",
                        mapOf(
                            "User-Agent" to UA,
                            "token" to token,
                            "tokenparam" to "$ts,$APP_VERSION",
                        ),
                    )
                }
                Regex("var scramble_id = (\\d+);").find(html)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
                throw IOException("scramble_id not found")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: IOException("scramble_id unavailable")
    }

    // ---- 账号 ----

    /** 登录：成功返回（Cookie 头, 账号信息）。 */
    suspend fun login(username: String, password: String): Pair<String, JmUser> {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                val ts = System.currentTimeMillis() / 1000
                val token = md5Hex("$ts$SECRET")
                val result = withContext(Dispatchers.IO) {
                    JmHttp.request(
                        "https://$host/login",
                        mapOf(
                            "User-Agent" to UA,
                            "token" to token,
                            "tokenparam" to "$ts,$APP_VERSION",
                            "Accept" to "application/json",
                        ),
                        form = mapOf("username" to username, "password" to password),
                    )
                }
                val text = result.body.toString(Charsets.UTF_8)
                val payload = envelopePayload(text)
                if (payload.isNullOrBlank()) {
                    throw IOException("帳號或密碼錯誤")
                }
                val obj = jmJson.parseToJsonElement(decrypt(payload, ts)).jsonObject
                fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull ?: ""
                val avatar = str("s")
                if (avatar.isBlank()) throw IOException("帳號或密碼錯誤")
                val user = JmUser(
                    uid = str("uid"),
                    username = str("username"),
                    email = str("email"),
                    photo = str("photo"),
                    level = str("level"),
                    levelName = str("level_name"),
                    exp = str("exp"),
                    expPercent = str("expPercent"),
                    coin = str("coin"),
                    favorites = str("album_favorites").toIntOrNull() ?: 0,
                    favoritesMax = str("album_favorites_max").toIntOrNull() ?: 0,
                )
                val cookies = result.setCookies
                    .map { it.substringBefore(';').trim() }
                    .filter { it.contains('=') && !it.lowercase().startsWith("avs=") }
                val cookie = (cookies + "AVS=$avatar").joinToString("; ")
                return cookie to user
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: IOException("登入失敗")
    }

    /** 我的收藏（folder_id=0 就是全部）。 */
    @Serializable
    data class FavoritePage(val list: List<AlbumItem> = emptyList(), val total: Int = 0)

    suspend fun favorites(page: Int, order: String = "mr"): FavoritePage {
        val text = getJson("/favorite", mapOf("page" to "$page", "folder_id" to "0", "o" to order))
        val obj = jmJson.parseToJsonElement(text).jsonObject
        val list = obj["list"]?.let {
            runCatching { jmJson.decodeFromString<List<AlbumItem>>(it.toString()) }.getOrDefault(emptyList())
        } ?: emptyList()
        val total = obj["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return FavoritePage(list, total)
    }

    /** 切换收藏状态；返回 true = 现在是「已收藏」。 */
    suspend fun toggleFavorite(albumId: Long): Boolean {
        val text = postJson("/favorite", mapOf("aid" to "$albumId"))
        val obj = jmJson.parseToJsonElement(text).jsonObject
        return obj["type"]?.jsonPrimitive?.contentOrNull == "add"
    }

    /** 本子评论（移动端只能读，发评论要走网页端）。 */
    @Serializable
    data class JmComment(
        val CID: String = "",
        val UID: String = "",
        val content: String = "",
        val username: String = "",
        val nickname: String = "",
        val addtime: String = "",
        val likes: String = "",
        val replys: List<JmComment> = emptyList(),
    ) {
        val author: String get() = nickname.ifBlank { username }
        val plainText: String
            get() = content
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("&#(\\d+);")) { m ->
                    m.groupValues[1].toIntOrNull()
                        ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                        ?: ""
                }
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
    }

    @Serializable
    data class CommentPage(val list: List<JmComment> = emptyList(), val total: Int = 0)

    suspend fun comments(albumId: Long, page: Int = 1): CommentPage {
        val text = getJson("/forum", mapOf("mode" to "all", "page" to "$page", "aid" to "$albumId"))
        val obj = jmJson.parseToJsonElement(text).jsonObject
        val list = obj["list"]?.let {
            runCatching { jmJson.decodeFromString<List<JmComment>>(it.toString()) }.getOrDefault(emptyList())
        } ?: emptyList()
        val total = obj["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return CommentPage(list, total)
    }

}
