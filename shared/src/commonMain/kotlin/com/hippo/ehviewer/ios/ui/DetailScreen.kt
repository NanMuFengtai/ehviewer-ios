package com.hippo.ehviewer.ios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.ios.source.EhDetail
import com.hippo.ehviewer.ios.source.EhSource
import com.hippo.ehviewer.ios.source.JmSource
import com.hippo.ehviewer.ios.source.NhentaiApi
import com.hippo.ehviewer.ios.source.NhentaiCdn
import com.hippo.ehviewer.ios.source.Site
import kotlinx.coroutines.launch

/**
 * 阅读器的一页。
 * - url: N 站是图片地址；E 站是阅读页地址（还需要再请求一次才能拿到原图）
 * - isPageUrl: true 表示要先解析页面
 */
data class PageRef(
    val url: String,
    val width: Int,
    val height: Int,
    val isPageUrl: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    site: Site,
    galleryId: Long,
    token: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onRead: (Site, Long, String, List<PageRef>) -> Unit,
    onTagClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var eh by remember { mutableStateOf<EhDetail?>(null) }
    var nh by remember { mutableStateOf<NhentaiApi.Detail?>(null) }
    var jm by remember { mutableStateOf<JmSource.Album?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(site, galleryId) {
        loading = true
        error = null
        runCatching {
            when (site) {
                Site.EHENTAI -> eh = EhSource.detail(galleryId, token)
                Site.NHENTAI -> {
                    NhentaiCdn.refresh()
                    nh = NhentaiApi.detail(galleryId)
                }
                Site.JMCOMIC -> jm = JmSource.album(galleryId)
            }
        }.onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    val title = eh?.item?.title ?: nh?.displayTitle ?: jm?.name?.ifBlank { fallbackTitle } ?: fallbackTitle
    val cover = eh?.item?.thumbUrl
        ?: nh?.let { NhentaiCdn.image(it.cover.path) }
        ?: jm?.let { JmSource.coverUrl(it.albumId) }
        ?: ""
    val pageCount = eh?.item?.pages ?: nh?.num_pages ?: jm?.pageCount ?: 0
    val tagGroups: List<Pair<String, List<Pair<String, String>>>> = when {
        eh != null -> eh!!.tagGroups.map { (ns, tags) -> ns to tags.map { it.namespace to it.name } }
        nh != null -> listOf("tag" to nh!!.tags.map { it.type to it.name })
        jm != null -> listOf(
            "author" to jm!!.author.map { "author" to it },
            "tag" to jm!!.tags.map { "tag" to it },
        ).filter { it.second.isNotEmpty() }
        else -> emptyList()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("加载失败：$error", color = MaterialTheme.colorScheme.error)
                }
                else -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = cover,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    )

                    Text(title, style = MaterialTheme.typography.titleLarge)

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MetaChip("$pageCount 页")
                        eh?.let { d ->
                            d.size?.let { MetaChip(it) }
                            if (d.favoriteCount > 0) MetaChip("${d.favoriteCount} 收藏")
                            if (d.item.rating > 0) MetaChip("${d.item.rating.oneDecimal()} 星")
                            d.item.uploader?.let { MetaChip(it) }
                        }
                        nh?.let { d ->
                            MetaChip("${d.num_favorites} 收藏")
                            d.scanlator?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                        }
                        jm?.let { d ->
                            if (d.authorText.isNotBlank()) MetaChip(d.authorText)
                            if (d.likes.isNotBlank()) MetaChip("${d.likes} 喜欢")
                        }
                    }

                    if (tagGroups.isNotEmpty()) {
                        TagFlow(tagGroups, onTagClick)
                    }

                    eh?.let { d ->
                        if (d.comments.isNotEmpty()) {
                            Text(
                                "评论（${d.comments.size}）",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            d.comments.forEach { c ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                            RoundedCornerShape(10.dp),
                                        )
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            c.user.ifBlank { "匿名" },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            if (c.score != 0) "评分 ${c.score}" else c.time,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(c.content, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = {
                                when {
                                    eh != null -> {
                                        val pages = eh!!.previews.map {
                                            PageRef(it.pageUrl, 0, 0, isPageUrl = true)
                                        }
                                        if (pages.isNotEmpty()) {
                                            onRead(site, galleryId, title, pages)
                                        }
                                    }

                                    nh != null -> {
                                        val pages = nh!!.pages.map {
                                            PageRef(NhentaiCdn.image(it.path), it.width, it.height)
                                        }
                                        if (pages.isNotEmpty()) {
                                            onRead(site, galleryId, title, pages)
                                        }
                                    }

                                    jm != null -> {
                                        val album = jm!!
                                        scope.launch {
                                            // 多话本取第一话，单话本直接用专辑 id 当章节 id
                                            val chapterId =
                                                album.series.firstOrNull()?.chapterId
                                                    ?: album.albumId
                                            runCatching { JmSource.chapter(chapterId) }
                                                .onSuccess { cp ->
                                                    val refs = cp.images.map { file ->
                                                        PageRef(
                                                            JmSource.imageUrl(chapterId, file),
                                                            0,
                                                            0,
                                                        )
                                                    }
                                                    if (refs.isNotEmpty()) {
                                                        onRead(site, chapterId, album.name, refs)
                                                    } else {
                                                        error = "这一话没有图片"
                                                    }
                                                }
                                                .onFailure {
                                                    error = it.message ?: it.toString()
                                                }
                                        }
                                    }
                                }
                            },
                        ) { Text("开始阅读（$pageCount 页）") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFlow(
    groups: List<Pair<String, List<Pair<String, String>>>>,
    onTagClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (ns, tags) ->
            Text(
                text = ns,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.take(40).forEach { (t, name) -> TagPill(t, name) { onTagClick(name) } }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TagPill(type: String, name: String, onClick: () -> Unit = {}) {
    val bg = when (type.lowercase()) {
        "language" -> Color(0xFF5B8DEF)
        "artist" -> Color(0xFFE0678A)
        "group" -> Color(0xFF9C6BD6)
        "parody" -> Color(0xFFE39A3B)
        "character" -> Color(0xFF3FB27F)
        "female", "male" -> Color(0xFF3FB27F)
        else -> Color(0xFF7A7A7A)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = name, style = MaterialTheme.typography.labelSmall, color = bg)
    }
}

internal fun Float.oneDecimal(): String {
    val i = (this * 10).toInt()
    return "${i / 10}.${i % 10}"
}
