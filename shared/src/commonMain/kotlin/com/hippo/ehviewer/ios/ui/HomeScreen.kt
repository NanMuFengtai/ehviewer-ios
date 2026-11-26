package com.hippo.ehviewer.ios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.ios.source.EhSource
import com.hippo.ehviewer.ios.source.ListEntry
import com.hippo.ehviewer.ios.source.NhentaiApi
import com.hippo.ehviewer.ios.source.NhentaiCdn
import com.hippo.ehviewer.ios.source.Site
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    site: Site,
    onSwitchSite: (Site) -> Unit,
    onOpenDetail: (Site, Long, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember { mutableStateListOf<ListEntry>() }
    var pageIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    suspend fun load(target: Int, reset: Boolean) {
        if (loading) return
        loading = true
        error = null
        runCatching {
            when (site) {
                Site.EHENTAI -> {
                    val r = EhSource.list(target)
                    r.items.map { g ->
                        ListEntry(
                            id = g.gid,
                            token = g.token,
                            title = g.title,
                            thumb = g.thumbUrl,
                            pages = g.pages,
                            meta = buildString {
                                g.uploader?.let { append(it).append(" · ") }
                                append("${g.pages}页")
                                if (g.rating > 0) append(" · ${g.rating.oneDecimal()}星")
                            },
                        )
                    } to (r.next == null)
                }

                Site.NHENTAI -> {
                    NhentaiCdn.refresh()
                    val r = NhentaiApi.list(target + 1)
                    r.result.map { g ->
                        ListEntry(
                            id = g.id,
                            token = "",
                            title = g.title,
                            thumb = NhentaiCdn.thumb(g.thumbnail),
                            pages = g.num_pages,
                            meta = "${g.num_pages}页 · ${g.num_favorites}收藏",
                        )
                    } to (target + 1 >= r.num_pages || r.result.isEmpty())
                }

                Site.JMCOMIC -> emptyList<ListEntry>() to true
            }
        }.onSuccess { (list, end) ->
            if (reset) entries.clear()
            entries.addAll(list)
            pageIndex = target
            endReached = end
        }.onFailure {
            error = it.message ?: it.toString()
        }
        loading = false
    }

    LaunchedEffect(site) {
        entries.clear()
        pageIndex = 0
        endReached = false
        error = null
        load(0, reset = true)
    }

    LaunchedEffect(gridState, site) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 6 && info.totalItemsCount > 0
        }.collect { nearEnd ->
            if (nearEnd && !loading && !endReached && entries.isNotEmpty()) {
                load(pageIndex + 1, reset = false)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text("很太之家", style = MaterialTheme.typography.titleLarge)
                    },
                    actions = {
                        TextButton(onClick = { scope.launch { load(0, true) } }, enabled = !loading) {
                            Text("刷新")
                        }
                    },
                )
                SiteTabs(current = site, onSelect = onSwitchSite)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                entries.isEmpty() && loading -> CenterProgress()
                entries.isEmpty() && error != null -> ErrorPane(error!!) {
                    scope.launch { load(0, true) }
                }
                entries.isEmpty() && !endReached -> CenterProgress()
                entries.isEmpty() -> EmptyPane()
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { "${site.name}-${it.id}" }) { item ->
                        GalleryCard(site, item) {
                            onOpenDetail(site, item.id, item.token, item.title)
                        }
                    }
                    if (loading) {
                        item(span = { GridItemSpan(2) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteTabs(current: Site, onSelect: (Site) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Site.entries.forEach { s ->
            val selected = s == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) s.accent.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    )
                    .clickable { onSelect(s) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) s.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GalleryCard(site: Site, item: ListEntry, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = item.thumb,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = item.meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CenterProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("没有内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
