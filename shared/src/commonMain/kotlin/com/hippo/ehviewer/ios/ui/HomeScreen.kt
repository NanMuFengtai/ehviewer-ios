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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.ios.source.EhSource
import com.hippo.ehviewer.ios.source.JmSource
import com.hippo.ehviewer.ios.source.ListEntry
import com.hippo.ehviewer.ios.source.NhentaiApi
import com.hippo.ehviewer.ios.source.NhentaiCdn
import com.hippo.ehviewer.ios.source.Site
import com.hippo.ehviewer.ios.store.HistoryStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    site: Site,
    onSwitchSite: (Site) -> Unit,
    onOpenDetail: (Site, Long, String, String) -> Unit,
    onOpenSettings: () -> Unit,
    searchSeq: Int = 0,
    searchText: String = "",
    modifier: Modifier = Modifier,
) {
    val entries = remember { mutableStateListOf<ListEntry>() }
    var pageIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showFavorites by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    suspend fun load(target: Int, reset: Boolean) {
        if (loading) return
        loading = true
        error = null
        runCatching {
            if (showFavorites) {
                return@runCatching when (site) {
                    Site.EHENTAI -> {
                        val r = EhSource.favorites(target)
                        r.items.map { g ->
                            ListEntry(
                                g.gid, g.token, g.title, g.thumbUrl, g.pages,
                                "${g.pages}页" + if (g.rating > 0) " · ${g.rating.oneDecimal()}星" else "",
                            )
                        } to (r.next == null)
                    }
                    Site.NHENTAI -> {
                        NhentaiCdn.refresh()
                        val r = NhentaiApi.favorites(target + 1, com.hippo.ehviewer.ios.store.Account.nhToken)
                        r.result.map { g ->
                            ListEntry(
                                g.id, "", g.title, NhentaiCdn.thumb(g.thumbnail), g.num_pages,
                                "${g.num_pages}页 · ${g.num_favorites}收藏",
                            )
                        } to (target + 1 >= r.num_pages || r.result.isEmpty())
                    }
                    Site.JMCOMIC -> {
                        val r = JmSource.favorites(target + 1)
                        r.content.map { g ->
                            ListEntry(
                                g.albumId, "", g.name.ifBlank { "#${g.albumId}" },
                                JmSource.coverUrl(g.albumId), 0,
                                g.author.ifBlank { g.category.title },
                            )
                        } to r.content.isEmpty()
                    }
                }
            }
            when (site) {
                Site.EHENTAI -> {
                    val r = EhSource.list(target, query.takeIf { it.isNotBlank() })
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
                    if (query.isBlank()) {
                        val r = NhentaiApi.list(target + 1)
                        r.result.map { g ->
                            ListEntry(
                                g.id, "", g.title,
                                NhentaiCdn.thumb(g.thumbnail),
                                g.num_pages,
                                "${g.num_pages}页 · ${g.num_favorites}收藏",
                            )
                        } to (target + 1 >= r.num_pages || r.result.isEmpty())
                    } else {
                        val r = NhentaiApi.search(query, target + 1)
                        r.result.map { g ->
                            ListEntry(
                                g.id, "", g.title,
                                NhentaiCdn.thumb(g.thumbnail),
                                g.num_pages,
                                "${g.num_pages}页 · ${g.num_favorites}收藏",
                            )
                        } to (target + 1 >= r.num_pages || r.result.isEmpty())
                    }
                }

                Site.JMCOMIC -> {
                    val r = JmSource.list(target + 1, query = query)
                    r.content.map { g ->
                        ListEntry(
                            id = g.albumId,
                            token = "",
                            title = g.name.ifBlank { "#${g.albumId}" },
                            thumb = JmSource.coverUrl(g.albumId),
                            pages = 0,
                            meta = g.author.ifBlank { g.category.title },
                        )
                    } to r.content.isEmpty()
                }
            }
        }.onSuccess { (list, end) ->
            if (reset) entries.clear()
            entries.addAll(list)
            pageIndex = target
            endReached = end
        }.onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    LaunchedEffect(searchSeq) {
        if (searchSeq > 0 && searchText.isNotBlank()) {
            searchInput = searchText
            query = searchText
            showFavorites = false
        }
    }

    LaunchedEffect(site, query, showFavorites) {
        entries.clear()
        pageIndex = 0
        endReached = false
        error = null
        load(0, reset = true)
    }

    LaunchedEffect(gridState, site, query, showFavorites) {
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
                    title = { Text("很太之家", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        TextButton(
                            onClick = { showFavorites = !showFavorites },
                            enabled = !loading,
                        ) { Text(if (showFavorites) "列表" else "收藏") }
                        TextButton(onClick = { scope.launch { load(0, true) } }, enabled = !loading) {
                            Text("刷新")
                        }
                        TextButton(onClick = onOpenSettings) { Text("设置") }
                    },
                )
                SiteTabs(current = site, onSelect = onSwitchSite)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it },
                        placeholder = { Text("搜索 ${site.label}", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { query = searchInput.trim() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { query = searchInput.trim() }) { Text("搜索") }
                    if (query.isNotBlank()) {
                        TextButton(onClick = { searchInput = ""; query = "" }) { Text("清空") }
                    }
                }
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
                            HistoryStore.add(site, item.id, item.token, item.title, item.thumb)
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
