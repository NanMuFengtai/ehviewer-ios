package com.hippo.ehviewer.ios.source

import com.hippo.ehviewer.ios.net.IOS_USER_AGENT
import com.hippo.ehviewer.ios.net.NetException
import com.hippo.ehviewer.ios.net.httpClient
import com.hippo.ehviewer.ios.util.Html
import com.hippo.ehviewer.ios.util.HtmlTag
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** 列表页的一个条目。字段和原版 BaseGalleryInfo 对齐，但只保留 iOS 会用到的。 */
data class EhGalleryItem(
    val gid: Long,
    val token: String,
    val title: String,
    val titleJpn: String?,
    val thumbUrl: String,
    val category: Int,
    val posted: String?,
    val uploader: String?,
    val pages: Int,
    val rating: Float,
    val rated: Boolean,
    val tags: List<String>,
    val thumbWidth: Int,
    val thumbHeight: Int,
)

data class EhListResult(
    val items: List<EhGalleryItem>,
    val prev: String?,
    val next: String?,
)

/** 详情页的一页预览。 */
data class EhPreview(val pageUrl: String, val thumbUrl: String)

data class EhTag(val namespace: String, val name: String)

data class EhDetail(
    val item: EhGalleryItem,
    val size: String?,
    val language: String?,
    val favoriteCount: Int,
    val tagGroups: List<Pair<String, List<EhTag>>>,
    val previews: List<EhPreview>,
)

/**
 * E-Hentai 数据源。
 *
 * 原版这部分是 Rust + JNI（native/rust/core/src/parser），iOS 上没法用，
 * 这里按同一套规则用 Kotlin 重写：列表照 list.rs，详情照 detail.rs 的主要字段。
 * 默认走表站 e-hentai.org，不需要登录；exhentai 需要 igneous cookie，后续再接。
 */
object EhSource {
    const val EH_BASE = "https://e-hentai.org"
    const val EX_BASE = "https://exhentai.org"
    const val THUMB_BASE = "https://ehgt.org/"
    private const val THUMB_V1 = "https://ehgt.org/t/"

    /** 登录 exhentai 后置 true。 */
    var useExHentai: Boolean = false

    val host: String get() = if (useExHentai) EX_BASE else EH_BASE

    private val tokenAndGid = Regex("""/(?:g|mpv)/(\d+)/([0-9a-f]{10})""")
    private val pxRegex = Regex("""(\d+)px""")

    // ---------- 网络 ----------

    private suspend fun fetchHtml(url: String): String {
        val response = httpClient.get(url) {
            header("User-Agent", IOS_USER_AGENT)
            header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw NetException("HTTP ${response.status.value}: ${text.take(200)}")
        }
        return text
    }

    // ---------- 列表 ----------

    suspend fun list(page: Int, query: String? = null): EhListResult {
        val url = buildString {
            append(host).append('/')
            val params = mutableListOf<String>()
            if (!query.isNullOrBlank()) params.add("f_search=" + encode(query))
            if (page > 0) params.add("page=$page")
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        val html = fetchHtml(url)
        return parseList(html)
    }

    private fun parseList(html: String): EhListResult {
        // 搜索无结果时会出现 searchwarn
        Html.firstByClass(html, "searchwarn")?.let { warn ->
            val text = warn.text()
            if (text.isNotBlank()) throw NetException(text)
        }

        val itg = Html.firstTagByClass(html, "table", "itg")
            ?: return EhListResult(emptyList(), null, null)
        val items = Html.findAll(itg.inner, "tr").mapNotNull { parseRow(it) }

        val prev = Html.findById(html, "uprev")?.attr("href")
            ?.let { Regex("""prev=(\d+(-\d+)?)""").find(it)?.groupValues?.get(1) }
        val next = Html.findById(html, "unext")?.attr("href")
            ?.let { Regex("""next=(\d+(-\d+)?)""").find(it)?.groupValues?.get(1) }

        return EhListResult(items, prev, next)
    }

    private fun parseRow(row: HtmlTag): EhGalleryItem? {
        val glink = Html.firstByClass(row.inner, "glink") ?: return null
        val title = glink.text()
        if (title.isBlank()) return null

        val glname = Html.firstByClass(row.inner, "glname")
        val href = glname?.let { Html.firstHref(it.inner) }
            ?: Html.firstHref(row.inner)
            ?: return null
        val m = tokenAndGid.find(href) ?: return null
        val gid = m.groupValues[1].toLongOrNull() ?: return null
        val token = m.groupValues[2]

        // 缩略图：优先 data-src（懒加载），退到 src
        val imgs = Html.findVoid(row.inner, "img")
        val thumbAttrs = imgs.firstOrNull { it.contains("data-src") }
            ?: imgs.firstOrNull { it.contains("src=") }
        val rawThumb = thumbAttrs?.let { attrs ->
            Regex("""data-src\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)
                ?: Regex("""\bsrc\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)
        }
        val thumbUrl = rawThumb?.let { normalizeThumb(it) } ?: ""

        val thumbSize: Pair<Int, Int> = thumbAttrs?.let { attrs ->
            Regex("""height:(\d+)px;width:(\d+)px""").find(attrs)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val w = m.groupValues[2].toIntOrNull() ?: 0
                h to w
            } ?: (0 to 0)
        } ?: (0 to 0)
        val thumbHeight = thumbSize.first
        val thumbWidth = thumbSize.second

        val categoryText = Html.firstByClass(row.inner, "cn")?.text()
            ?: Html.firstByClass(row.inner, "cs")?.text()
            ?: ""
        val category = toCategory(categoryText.trim().lowercase())

        val posted = Html.findById(row.inner, "posted_$gid")?.text()?.takeIf { it.isNotBlank() }

        // 评分：.ir 的 style 里有两个 NNpx，第一个是背景偏移，第二个 21 表示半星
        val ir = Html.firstByClass(row.inner, "ir")
        val irStyle = ir?.attr("style") ?: ""
        val irClass = ir?.attr("class") ?: ""
        val rating = parseRating(irStyle)
        val rated = irClass.contains("irr") || irClass.contains("irg") || irClass.contains("irb")

        // 上传者与页数在一个隐藏块里
        val infoBlock = Html.firstByClass(row.inner, "glhide")
            ?: Html.firstByClass(row.inner, "gl3e")
            ?: Html.firstByClass(row.inner, "gl5t")
        val infoHtml = infoBlock?.inner ?: ""
        val uploader = Regex(
            """<a href="https://e[x-]hentai\.org/uploader/.*?">(.*?)</a>|(\(Disowned\))""",
        ).find(infoHtml)?.let { mm ->
            Html.unescape(mm.groupValues[1].ifEmpty { mm.groupValues[2] })
                .replace(Regex("<[^>]+>"), "").trim()
        }?.takeIf { it.isNotBlank() }
        val pages = Regex("""<div>(\d+) pages</div>""").find(infoHtml)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val tags = (Html.findAllByClass(row.inner, "gt") + Html.findAllByClass(row.inner, "gtl"))
            .mapNotNull { it.attr("title") }
            .map { Html.unescape(it) }
            .distinct()

        return EhGalleryItem(
            gid = gid,
            token = token,
            title = Html.unescape(title),
            titleJpn = null,
            thumbUrl = thumbUrl,
            category = category,
            posted = posted,
            uploader = uploader,
            pages = pages,
            rating = rating,
            rated = rated,
            tags = tags,
            thumbWidth = thumbWidth,
            thumbHeight = thumbHeight,
        )
    }

    private fun parseRating(style: String): Float {
        val nums = pxRegex.findAll(style).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        if (nums.size < 2) return -1f
        val (n1, n2) = nums
        val rate = 5 - n1 / 16
        return if (n2 == 21) (rate - 1) + 0.5f else rate.toFloat()
    }

    private fun normalizeThumb(raw: String): String {
        if (raw.startsWith("http")) return raw
        return if (raw.startsWith("t/")) THUMB_BASE + raw else THUMB_BASE + "t/" + raw
    }

    private fun toCategory(s: String): Int = when (s) {
        "misc" -> 0x1
        "doujinshi" -> 0x2
        "manga" -> 0x4
        "artistcg", "artist cg sets", "artist cg" -> 0x8
        "gamecg", "game cg sets", "game cg" -> 0x10
        "imageset", "image sets", "image set" -> 0x20
        "cosplay" -> 0x40
        "asianporn", "asian porn" -> 0x80
        "non-h" -> 0x100
        "western" -> 0x200
        "private" -> 0x400
        else -> 0x800
    }

    // ---------- 详情 ----------

    suspend fun detail(gid: Long, token: String): EhDetail {
        val html = fetchHtml("$host/g/$gid/$token/")
        return parseDetail(html, gid, token)
    }

    private fun parseDetail(html: String, gid: Long, token: String): EhDetail {
        val title = Html.findById(html, "gn")?.text()?.takeIf { it.isNotBlank() }
            ?: Html.firstByClass(html, "gm")?.attr("alt")
            ?: ""
        val titleJpn = Html.findById(html, "gj")?.text()?.takeIf { it.isNotBlank() }

        val categoryText = Html.findById(html, "gdc")
            ?.let { Html.firstByClass(it.inner, "cs")?.text() ?: it.text() }
            ?: ""

        val uploader = Html.findById(html, "gdn")?.let { d ->
            Html.firstByClass(d.inner, "gn")?.text()?.takeIf { it.isNotBlank() }
                ?: d.text().removePrefix("Uploader:").trim().takeIf { it.isNotBlank() }
        }

        val posted = Html.findById(html, "gdd")?.text()

        val lengthText = Html.findById(html, "gdf")?.text() ?: ""
        val pages = Regex("""(\d+)\s*pages""", RegexOption.IGNORE_CASE)
            .find(lengthText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val favoriteCount = Html.findById(html, "favcount")?.text()
            ?.replace(Regex("""[^\d]"""), "")?.toIntOrNull() ?: 0

        val size = Regex("""File Size:\s*([\d.]+\s*[KMGT]i?B)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        val language = Regex("""Language:\s*<[^>]*>\s*([^<]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()

        // 标签：#taglist 里每行一个 namespace
        val tagGroups = mutableListOf<Pair<String, List<EhTag>>>()
        Html.findById(html, "taglist")?.let { list ->
            Html.findAll(list.inner, "tr").forEach { tr ->
                val tds = Html.findAll(tr.inner, "td")
                if (tds.size < 2) return@forEach
                val ns = tds[0].text().removeSuffix(":").trim()
                val tags = (Html.findAll(tds[1].inner, "a")).mapNotNull { a ->
                    val name = a.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    EhTag(ns, Html.unescape(name))
                }
                if (tags.isNotEmpty()) tagGroups.add(ns to tags)
            }
        }

        // 预览页：#gdt 里每个 a 指向 /s/xxx/gid-1
        val previews = mutableListOf<EhPreview>()
        Html.findById(html, "gdt")?.let { gdt ->
            Html.findAll(gdt.inner, "a").forEach { a ->
                val href = a.attr("href") ?: return@forEach
                val img = Html.findVoid(a.inner, "img").firstOrNull() ?: return@forEach
                val src = Regex("""\bsrc\s*=\s*["']([^"']+)["']""")
                    .find(img)?.groupValues?.get(1) ?: return@forEach
                previews.add(EhPreview(href, src))
            }
        }

        val item = EhGalleryItem(
            gid = gid,
            token = token,
            title = Html.unescape(title).ifBlank { "#$gid" },
            titleJpn = titleJpn,
            thumbUrl = previews.firstOrNull()?.thumbUrl ?: "",
            category = toCategory(categoryText.trim().lowercase()),
            posted = posted,
            uploader = uploader,
            pages = pages,
            rating = Regex("""Average:\s*([\d.]+)""").find(html)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f,
            rated = false,
            tags = tagGroups.flatMap { it.second }.map { it.name },
            thumbWidth = 0,
            thumbHeight = 0,
        )

        return EhDetail(
            item = item,
            size = size,
            language = language,
            favoriteCount = favoriteCount,
            tagGroups = tagGroups,
            previews = previews,
        )
    }

    // ---------- 阅读器 ----------

    /**
     * 取一页的原图地址和下一页地址。
     * E 站的阅读页有两种形态：直接带 `<img id="img">`，或者先给一个「继续」链接。
     */
    suspend fun readerPage(pageUrl: String): Pair<String?, String?> {
        val absolute = if (pageUrl.startsWith("http")) pageUrl else host + pageUrl
        var html = fetchHtml(absolute)
        var guard = 0
        while (guard++ < 3) {
            val img = Regex("""<img[^>]*\bid=["']img["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(html)?.value
            if (img != null) {
                val src = Regex("""\bsrc\s*=\s*["']([^"']+)["']""")
                    .find(img)?.groupValues?.get(1)
                val next = Html.findById(html, "next")?.attr("href")
                val absNext = next?.let { if (it.startsWith("http")) it else host + it }
                return src to absNext
            }
            // 没图就先点继续
            val cont = Html.findById(html, "continue")?.let { Html.firstHref(it.inner) }
                ?: Regex("""<a[^>]*id=["']continue["'][^>]*href=["']([^"']+)["']""")
                    .find(html)?.groupValues?.get(1)
                ?: break
            html = fetchHtml(if (cont.startsWith("http")) cont else host + cont)
        }
        return null to null
    }

    private fun encode(s: String): String = buildString {
        for (ch in s) {
            when {
                ch.isLetterOrDigit() || ch in "-_.~" -> append(ch)
                ch == ' ' -> append('+')
                else -> {
                    val bytes = ch.toString().encodeToByteArray()
                    for (b in bytes) {
                        append('%')
                        append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                        append("0123456789ABCDEF"[b.toInt() and 0xF])
                    }
                }
            }
        }
    }
}
