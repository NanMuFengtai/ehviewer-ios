package com.hippo.ehviewer.ios.source

import com.hippo.ehviewer.ios.net.AppJson
import com.hippo.ehviewer.ios.net.NetException
import com.hippo.ehviewer.ios.net.getText
import com.hippo.ehviewer.ios.net.httpClient
import com.hippo.ehviewer.ios.net.requestText
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * nhentai 的 v2 接口。
 *
 * 和 E 站最大的不同：详情接口一次就把全部页表和图片路径给全了，
 * 不需要 pToken、不需要逐页解析 HTML，图片 URL 直接拼出来。
 */
object NhentaiApi {
    const val BASE = "https://nhentai.net"
    const val PER_PAGE = 25

    const val SORT_DATE = "date"
    const val SORT_POPULAR = "popular"

    suspend fun list(page: Int, perPage: Int = PER_PAGE): PagedGalleries =
        AppJson.decodeFromString(
            getText("$BASE/api/v2/galleries", page, mapOf("per_page" to "$perPage")),
        )

    suspend fun search(query: String, page: Int, sort: String = SORT_DATE): PagedGalleries =
        AppJson.decodeFromString(
            getText("$BASE/api/v2/search", page, mapOf("query" to query, "sort" to sort)),
        )

    suspend fun tagged(tagId: Long, page: Int, sort: String = SORT_DATE): PagedGalleries =
        AppJson.decodeFromString(
            getText(
                "$BASE/api/v2/galleries/tagged",
                page,
                mapOf("tag_id" to "$tagId", "sort" to sort),
            ),
        )

    /** 热门接口不给分页，一次返回一页。 */
    suspend fun popular(): List<GalleryItem> =
        AppJson.decodeFromString(getText("$BASE/api/v2/galleries/popular"))

    suspend fun detail(id: Long): Detail =
        AppJson.decodeFromString(getText("$BASE/api/v2/galleries/$id"))

    suspend fun cdn(): Cdn = AppJson.decodeFromString(getText("$BASE/api/v2/cdn"))

    suspend fun me(token: String): User {
        val (status, body) = requestText(
            "$BASE/api/v2/user",
            headers = mapOf("Authorization" to "User $token"),
        )
        if (status !in 200..299) throw NetException("HTTP $status: ${body.take(160)}")
        return AppJson.decodeFromString(body)
    }

    suspend fun favorites(page: Int, token: String, perPage: Int = PER_PAGE): PagedGalleries {
        val (status, body) = requestText(
            "$BASE/api/v2/favorites",
            page = page,
            params = mapOf("per_page" to "$perPage"),
            headers = mapOf("Authorization" to "User $token"),
        )
        if (status !in 200..299) throw NetException("HTTP $status: ${body.take(160)}")
        return AppJson.decodeFromString(body)
    }

    @Serializable
    data class User(
        val id: Long = 0,
        val username: String = "",
        val email: String? = null,
        val avatar_url: String? = null,
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
            get() = english_title.ifBlank { japanese_title.orEmpty() }.ifBlank { "#$id" }
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
            get() = title.pretty.ifBlank {
                title.english.ifBlank { title.japanese.orEmpty() }
            }.ifBlank { "#$id" }
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
    data class Cdn(
        val image_servers: List<String> = emptyList(),
        val thumb_servers: List<String> = emptyList(),
    )
}

/**
 * 图床域名。优先用 /api/v2/cdn 返回的列表，拿不到就用官方那几个备用域名。
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

    private var imageServers: List<String> = defaultImage
    private var thumbServers: List<String> = defaultThumb
    private var refreshed = false

    suspend fun refresh() {
        if (refreshed) return
        runCatching { NhentaiApi.cdn() }.onSuccess { cdn ->
            cdn.image_servers.takeIf { it.isNotEmpty() }?.let { imageServers = it }
            cdn.thumb_servers.takeIf { it.isNotEmpty() }?.let { thumbServers = it }
            refreshed = true
        }
    }

    fun image(path: String) = build(imageServers.first(), path)

    fun thumb(path: String) = build(thumbServers.first(), path)

    /** 按顺序尝试全部图床，前一个挂了就换下一个。 */
    fun imageCandidates(path: String): List<String> = imageServers.map { build(it, path) }

    fun thumbCandidates(path: String): List<String> = thumbServers.map { build(it, path) }

    private fun build(server: String, path: String): String =
        "${server.trimEnd('/')}/${path.trimStart('/')}"
}
