package com.hippo.ehviewer.ios.util

/**
 * 轻量 HTML 提取器，给 E 站的解析用。
 *
 * 原版是 Rust 里用 tl 库做 DOM 查询，iOS 上没有等价物，这里用正则实现
 * 「按标签名找元素」「按 class/id 找元素」「取属性」「取文本」这几件事，
 * 够覆盖 E 站列表页和详情页的结构。E 站的 HTML 是脚本拼出来的固定模板，
 * 不是任意网页，所以不做完整解析器。
 */
data class HtmlTag(
    val name: String,
    val attrs: String,
    val inner: String,
) {
    /** 取属性值，支持双引号和单引号。 */
    fun attr(key: String): String? {
        val re = Regex("""\b$key\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        val m = re.find(attrs) ?: return null
        return (m.groupValues[1].ifEmpty { null } ?: m.groupValues[2]).takeIf { it.isNotEmpty() }
    }

    /** 是否带某个 class。 */
    fun hasClass(cls: String): Boolean =
        attr("class")?.split(Regex("""\s+"""))?.any { it == cls } == true

    fun classes(): List<String> =
        attr("class")?.split(Regex("""\s+"""))?.filter { it.isNotEmpty() } ?: emptyList()

    /** 去掉内部标签后的纯文本，实体已解码。 */
    fun text(): String = Html.plainText(inner)

    override fun toString(): String = "<$name ${attrs.take(80)}>${inner.take(60)}"
}

object Html {

    /** 找所有 `<tag ...>...</tag>` 块。tag 不自嵌套时可安全使用。 */
    fun findAll(html: String, tag: String): List<HtmlTag> {
        val re = Regex(
            "<$tag\\b([^>]*)>(.*?)</$tag\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return re.findAll(html).map { HtmlTag(tag, it.groupValues[1], it.groupValues[2]) }.toList()
    }

    /** 找所有自闭合或空元素，比如 `<img ...>`。返回属性串。 */
    fun findVoid(html: String, tag: String): List<String> {
        val re = Regex("<$tag\\b([^>]*?)/?>", setOf(RegexOption.IGNORE_CASE))
        return re.findAll(html).map { it.groupValues[1] }.toList()
    }

    /** 找所有带指定 class 的元素，任意标签名。 */
    fun findAllByClass(html: String, cls: String): List<HtmlTag> {
        val re = Regex(
            "<([a-zA-Z][a-zA-Z0-9]*)\\b([^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\b$cls\\b[^\"']*[\"'][^>]*)>(.*?)</\\1\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return re.findAll(html).map {
            HtmlTag(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }.toList()
    }

    /** 找第一个带指定 class 的元素。 */
    fun firstByClass(html: String, cls: String): HtmlTag? = findAllByClass(html, cls).firstOrNull()

    /** 找指定 id 的元素。 */
    fun findById(html: String, id: String): HtmlTag? {
        val re = Regex(
            "<([a-zA-Z][a-zA-Z0-9]*)\\b([^>]*\\bid\\s*=\\s*[\"']$id[\"'][^>]*)>(.*?)</\\1\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val m = re.find(html) ?: return null
        return HtmlTag(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    /** 找完整标签块，用来取某个 class 元素的外层 HTML。 */
    fun firstTagByClass(html: String, tag: String, cls: String): HtmlTag? =
        findAll(html, tag).firstOrNull { it.hasClass(cls) }

    /** 去标签取文本，压缩空白，解码实体。 */
    fun plainText(html: String): String {
        val noTags = Regex("<[^>]+>").replace(html, "")
        return unescape(noTags).replace(Regex("""\s+"""), " ").trim()
    }

    /** HTML 实体解码，覆盖 E 站会出现的那些。 */
    fun unescape(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                sb.append(c)
                i++
                continue
            }
            val body = s.substring(i + 1, semi)
            val decoded = when {
                body == "amp" -> "&"
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "quot" -> "\""
                body == "apos" -> "'"
                body == "nbsp" -> " "
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { it.toChar().toString() }
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { it.toChar().toString() }
                else -> null
            }
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 从一段 HTML 里取第一个 href。 */
    fun firstHref(html: String): String? =
        Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
}
