package com.hippo.ehviewer.ios.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.ios.source.NhentaiApi
import com.hippo.ehviewer.ios.source.NhentaiCdn

/** 阅读器的一页：图片地址加上原始宽高，用来算占位比例避免跳动。 */
data class PageRef(val url: String, val width: Int, val height: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    galleryId: Long,
    fallbackTitle: String,
    onBack: () -> Unit,
    onRead: (Long, String, List<PageRef>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<NhentaiApi.Detail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(galleryId) {
        loading = true
        error = null
        runCatching {
            NhentaiCdn.refresh()
            NhentaiApi.detail(galleryId)
        }.onSuccess { detail = it }
            .onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.displayTitle ?: fallbackTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("加载失败：$error", color = MaterialTheme.colorScheme.error)
                }
                detail != null -> DetailContent(
                    detail = detail!!,
                    onRead = { d ->
                        val pages = d.pages.map { page ->
                            PageRef(
                                url = NhentaiCdn.image(page.path),
                                width = page.width,
                                height = page.height,
                            )
                        }
                        onRead(d.id, d.displayTitle, pages)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(detail: NhentaiApi.Detail, onRead: (NhentaiApi.Detail) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = NhentaiCdn.image(detail.cover.path),
            contentDescription = detail.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    if (detail.cover.width > 0 && detail.cover.height > 0) {
                        detail.cover.width.toFloat() / detail.cover.height
                    } else {
                        0.72f
                    },
                )
                .clip(RoundedCornerShape(12.dp)),
        )

        Text(detail.displayTitle, style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetaChip("${detail.num_pages} 页")
            MetaChip("${detail.num_favorites} 收藏")
            detail.scanlator?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
        }

        if (detail.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detail.tags.take(24).forEach { tag ->
                    TagPill(tag.type, tag.name)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = { onRead(detail) }) {
                Text("开始阅读（${detail.num_pages} 页）")
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
private fun TagPill(type: String, name: String) {
    val bg = when (type) {
        "language" -> Color(0xFF5B8DEF)
        "artist" -> Color(0xFFE0678A)
        "group" -> Color(0xFF9C6BD6)
        "parody" -> Color(0xFFE39A3B)
        "character" -> Color(0xFF3FB27F)
        else -> Color(0xFF7A7A7A)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "$name",
            style = MaterialTheme.typography.labelSmall,
            color = bg,
        )
    }
}
