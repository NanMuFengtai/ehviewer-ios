package com.hippo.ehviewer.nhentai

import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val nJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * nhentai 的 v2 接口。
 *
 * 旧接口 /api/gallery/{id} 已废弃（返回 403 + "Use new API .../api/v2/docs"），
 * 新接口是 /api/v2/ 路径下的一套 REST，OpenAPI 在 https://nhentai.net/api/v2/openapi.json。
 *
 * 和 E 站最大的不同：详情接口一次就把全部页表和图片路径给全了，
 * 不需要 pToken、不需要逐页解析 HTML，图片 URL 直接拼出来。
 */
object NhentaiApi {
    const val BASE = "https://nhentai.net"
    const val PER_PAGE = 25

    const val MODE_LATEST = "latest"
    const val MODE_POPULAR = "popular"
    const val MODE_TAGGED = "tagged"
    const val MODE_SEARCH = "search"
    const val MODE_FAVORITES = "favorites"

    const val SORT_DATE = "date"
    const val SORT_POPULAR = "popular"

    private suspend fun getText(
        url: String,
        page: Int? = null,
        params: Map<String, String> = emptyMap(),
    ): String {
        val response = ktorClient.get(url) {
            page?.let { parameter("page", it) }
            params.forEach { (name, value) -> parameter(name, value) }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status.value}: ${text.take(160)}")
        }
        return text
    }

    suspend fun list(page: Int, perPage: Int = PER_PAGE): PagedGalleries =
        nJson.decodeFromString(getText("$BASE/api/v2/galleries", page, mapOf("per_page" to "$perPage")))

    suspend fun search(query: String, page: Int, sort: String = SORT_DATE): PagedGalleries =
        nJson.decodeFromString(
            getText("$BASE/api/v2/search", page, mapOf("query" to query, "sort" to sort)),
        )

    suspend fun tagged(tagId: Long, page: Int, sort: String = SORT_DATE): PagedGalleries =
        nJson.decodeFromString(
            getText(
                "$BASE/api/v2/galleries/tagged",
                page,
                mapOf("tag_id" to "$tagId", "sort" to sort),
            ),
        )

    /** 热门接口不给分页，一次返回一页。 */
    suspend fun popular(): List<GalleryItem> =
        nJson.decodeFromString(getText("$BASE/api/v2/galleries/popular"))

    suspend fun detail(id: Long): Detail =
        nJson.decodeFromString(getText("$BASE/api/v2/galleries/$id"))

    suspend fun tags(type: String = "tag", page: Int = 1, perPage: Int = 100): PagedTags =
        nJson.decodeFromString(
            getText("$BASE/api/v2/tags/$type", page, mapOf("per_page" to "$perPage")),
        )

    suspend fun cdn(): Cdn = nJson.decodeFromString(getText("$BASE/api/v2/cdn"))

    // ---- 登录相关 ----

    private suspend fun requestText(
        url: String,
        method: HttpMethod = HttpMethod.Get,
        page: Int? = null,
        params: Map<String, String> = emptyMap(),
        token: String? = null,
        body: String? = null,
    ): Pair<Int, String> {
        val response = ktorClient.request(url) {
            this.method = method
            page?.let { parameter("page", it) }
            params.forEach { (name, value) -> parameter(name, value) }
            token?.let { header("Authorization", "User $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return response.status.value to response.bodyAsText()
    }

    /**
     * 带登录态的请求。nhentai 用 `Authorization: User <access_token>` 这套 scheme
     * （不是 Bearer，也不是 Key）。
     * access_token 过期会返回 401，这里自动用 refresh_token 换一次再重试。
     */
    private suspend fun authedText(
        url: String,
        method: HttpMethod = HttpMethod.Get,
        page: Int? = null,
        params: Map<String, String> = emptyMap(),
    ): String {
        var token = Settings.nhentaiAccessToken.value
        if (token.isBlank()) throw IOException("Not signed in")
        var result = requestText(url, method, page, params, token)
        if (result.first == 401) {
            token = NhentaiSession.refresh() ?: throw IOException("Session expired, sign in again")
            result = requestText(url, method, page, params, token)
        }
        if (result.first !in 200..299) throw IOException("HTTP ${result.first}: ${result.second.take(160)}")
        return result.second
    }

    suspend fun refresh(refreshToken: String): TokenResponse {
        val body = nJson.encodeToString(RefreshBody(refreshToken))
        val (status, text) = requestText("$BASE/api/v2/auth/refresh", HttpMethod.Post, body = body)
        if (status !in 200..299) throw IOException("HTTP $status: ${text.take(160)}")
        return nJson.decodeFromString(text)
    }

    suspend fun me(token: String? = null): User {
        val text = if (token != null) {
            val (status, body) = requestText("$BASE/api/v2/user", token = token)
            if (status !in 200..299) throw IOException("HTTP $status: ${body.take(120)}")
            body
        } else {
            authedText("$BASE/api/v2/user")
        }
        return nJson.decodeFromString(text)
    }

    suspend fun favorites(page: Int, perPage: Int = PER_PAGE): PagedGalleries =
        nJson.decodeFromString(
            authedText("$BASE/api/v2/favorites", page = page, params = mapOf("per_page" to "$perPage")),
        )

    suspend fun favoriteState(galleryId: Long): FavoriteState =
        nJson.decodeFromString(authedText("$BASE/api/v2/galleries/$galleryId/favorite"))

    suspend fun setFavorite(galleryId: Long, favorited: Boolean): FavoriteState =
        nJson.decodeFromString(
            authedText(
                "$BASE/api/v2/galleries/$galleryId/favorite",
                method = if (favorited) HttpMethod.Post else HttpMethod.Delete,
            ),
        )

    @Serializable
    private data class RefreshBody(val refresh_token: String)

    @Serializable
    data class TokenResponse(
        val access_token: String = "",
        val refresh_token: String = "",
        val user: User? = null,
    )

    @Serializable
    data class User(
        val id: Long = 0,
        val username: String = "",
        val email: String? = null,
        val avatar_url: String? = null,
    )

    @Serializable
    data class FavoriteState(
        val favorited: Boolean = false,
        val num_favorites: Int? = null,
    )

    @Serializable
    data class PagedGalleries(
        val result: List<GalleryItem> = emptyList(),
        val num_pages: Int = 0,
        val per_page: Int = PER_PAGE,
        val total: Long = 0,
    )

    @Serializable
    data class GalleryItem(
        val id: Long,
        val media_id: String = "",
        val english_title: String = "",
        val japanese_title: String? = null,
        val thumbnail: String = "",
        val thumbnail_width: Int = 0,
        val thumbnail_height: Int = 0,
        val num_pages: Int = 0,
        val num_favorites: Int = 0,
    ) {
        val title: String
            get() = english_title.ifBlank { japanese_title.orEmpty() }
    }

    @Serializable
    data class Detail(
        val id: Long,
        val media_id: String = "",
        val title: Title = Title(),
        val cover: Cover = Cover(),
        val thumbnail: Cover = Cover(),
        val scanlator: String? = null,
        val upload_date: Long = 0,
        val tags: List<Tag> = emptyList(),
        val num_pages: Int = 0,
        val num_favorites: Int = 0,
        val pages: List<Page> = emptyList(),
    ) {
        val displayTitle: String
            get() = title.pretty.ifBlank { title.english.ifBlank { title.japanese.orEmpty() } }
    }

    @Serializable
    data class Title(
        val english: String = "",
        val japanese: String? = null,
        val pretty: String = "",
    )

    @Serializable
    data class Cover(
        val path: String = "",
        val width: Int = 0,
        val height: Int = 0,
    )

    @Serializable
    data class Page(
        val number: Int = 0,
        val path: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val thumbnail: String = "",
        val thumbnail_width: Int = 0,
        val thumbnail_height: Int = 0,
    )

    @Serializable
    data class Tag(
        val id: Long = 0,
        val type: String = "",
        val name: String = "",
        val slug: String = "",
        val url: String = "",
        val count: Long = 0,
    )

    @Serializable
    data class PagedTags(
        val result: List<Tag> = emptyList(),
        val num_pages: Int = 0,
        val per_page: Int = 100,
        val total: Long = 0,
    )

    @Serializable
    data class Cdn(
        val image_servers: List<String> = emptyList(),
        val thumb_servers: List<String> = emptyList(),
    )
}

/**
 * 图床域名。
 *
 * 优先用 /api/v2/cdn 返回的列表，拿不到就用官方那四个备用域名。
 * 服务端偶发给出 `1t.webp.webp` 这种双扩展名路径，这里统一规范成单扩展名。
 */
object NhentaiCdn {
    private val defaultImage = listOf(
        "https://i1.nhentai.net",
        "https://i2.nhentai.net",
        "https://i3.nhentai.net",
        "https://i4.nhentai.net",
    )
    private val defaultThumb = listOf(
        "https://t1.nhentai.net",
        "https://t2.nhentai.net",
        "https://t3.nhentai.net",
        "https://t4.nhentai.net",
    )

    @Volatile
    private var imageServers: List<String> = defaultImage

    @Volatile
    private var thumbServers: List<String> = defaultThumb

    @Volatile
    private var refreshed = false

    suspend fun refresh() {
        if (refreshed) return
        runCatching { NhentaiApi.cdn() }.onSuccess { cdn ->
            cdn.image_servers.takeIf { it.isNotEmpty() }?.let { imageServers = it }
            cdn.thumb_servers.takeIf { it.isNotEmpty() }?.let { thumbServers = it }
            refreshed = true
        }
    }

    fun image(path: String) = build(imageServers, path)

    fun thumb(path: String) = build(thumbServers, path)

    /** 按顺序尝试全部图床，前一个挂了就换下一个。 */
    fun imageCandidates(path: String): List<String> = imageServers.map { build(listOf(it), path) }

    fun thumbCandidates(path: String): List<String> = thumbServers.map { build(listOf(it), path) }

    private fun build(servers: List<String>, path: String): String {
        val server = servers.first().trimEnd('/')
        // 路径直接用服务端给的，不要自作聪明去改扩展名：
        // 封面真实路径就是 cover.webp.webp，去掉一层反而 404。
        return "$server/${path.trimStart('/')}"
    }

    /** `galleries/1/2t.webp.webp` -> `galleries/1/2t.webp` */
    fun normalize(path: String): String {
        val parts = path.split('.')
        return if (parts.size >= 3 && parts[parts.size - 1] == parts[parts.size - 2]) {
            parts.dropLast(1).joinToString(".")
        } else {
            path
        }
    }
}
