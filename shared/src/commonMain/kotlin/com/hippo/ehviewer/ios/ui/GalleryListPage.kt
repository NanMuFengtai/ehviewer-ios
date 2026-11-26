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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.ios.source.EhSource
import com.hippo.ehviewer.ios.source.JmSource
import com.hippo.ehviewer.ios.source.ListEntry
import com.hippo.ehviewer.ios.source.NhentaiApi
import com.hippo.ehviewer.ios.source.NhentaiCdn
import com.hippo.ehviewer.ios.source.Site
import com.hippo.ehviewer.ios.store.Account
import com.hippo.ehviewer.ios.store.HistoryStore
import kotlinx.coroutines.launch

enum class ListMode { LATEST, POPULAR, FAVORITES }

/** 分类色，和 Android 的 EhUtils.getCategoryColor 一致。 */
private fun categoryColor(category: Int): Color = Color(
    when (category) {
        0x2 -> 0xFFF44336          // doujinshi
        0x4 -> 0xFFFF9800          // manga
        0x8 -> 0xFFFBC02D          // artist cg
        0x10 -> 0xFF4CAF50         // game cg
        0x20 -> 0xFF3F51B5         // image set
        0x40 -> 0xFF9C27B0         // cosplay
        0x80 -> 0xFF9575CD         // asian porn
        0x100 -> 0xFF2196F3        // non-h
        0x200 -> 0xFF8BC34A        // western
        0x1 -> 0xFFF06292          // misc
        else -> 0xFF000000         // unknown
    },
)

@Composable
fun GalleryListPage(
    site: Site,
    mode: ListMode,
    title: String,
    onMenu: () -> Unit,
    onOpenDetail: (Site, Long, String, String) -> Unit,
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
    var searchInput by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    suspend fun load(target: Int, reset: Boolean) {
        if (loading) return
        loading = true
        error = null
        runCatching {
            when (site) {
                Site.EHENTAI -> {
                    val r = when (mode) {
                        ListMode.FAVORITES -> EhSource.favorites(target)
                        else -> EhSource.list(target, query.takeIf { it.isNotBlank() })
                    }
                    r.items.map { g ->
                        ListEntry(
                            id = g.gid,
                            token = g.token,
                            title = g.title,
                            thumb = g.thumbUrl,
                            pages = g.pages,
                            meta = g.uploader ?: "",
                            category = g.category,
                            language = g.titleJpn,
                        )
                    } to (r.next == null)
                }

                Site.NHENTAI -> {
                    NhentaiCdn.refresh()
                    val r = when {
                        mode == ListMode.FAVORITES && Account.nhToken.isNotBlank() ->
                            NhentaiApi.favorites(target + 1, Account.nhToken)
                        mode == ListMode.POPULAR && query.isBlank() -> null
                        query.isNotBlank() -> NhentaiApi.search(query, target + 1)
                        else -> NhentaiApi.list(target + 1)
                    }
                    if (r == null) {
                        NhentaiApi.popular().map { g ->
                            ListEntry(
                                g.id, "", g.title, NhentaiCdn.thumb(g.thumbnail), g.num_pages,
                                "${g.num_favorites} 收藏",
                            )
                        } to true
                    } else {
                        r.result.map { g ->
                            ListEntry(
                                g.id, "", g.title, NhentaiCdn.thumb(g.thumbnail), g.num_pages,
                                "${g.num_favorites} 收藏",
                            )
                        } to (target + 1 >= r.num_pages || r.result.isEmpty())
                    }
                }

                Site.JMCOMIC -> {
                    val r = if (mode == ListMode.FAVORITES) {
                        JmSource.favorites(target + 1)
                    } else {
                        JmSource.list(target + 1, query = query)
                    }
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
            searchOpen = true
        }
    }

    LaunchedEffect(site, mode, query) {
        entries.clear()
        pageIndex = 0
        endReached = false
        error = null
        load(0, reset = true)
    }

    LaunchedEffect(gridState, site, mode, query) {
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

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏：圆角悬浮，左汉堡，右站点色圆标
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, contentDescription = "菜单")
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(30.dp)
                        .clip(RoundedCornerShape(50))
                        .background(site.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = site.label.first().toString(),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (searchOpen) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    placeholder = { Text("搜索 ${site.label}") },
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

        Box(modifier = Modifier.fillMaxSize()) {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { "${site.name}-${mode.name}-${it.id}" }) { item ->
                        GalleryGridCard(item) {
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

/** 宫格卡片：封面 + 右上角分类色角标（页数），和 Android 的 GalleryInfoGridItem 一致。 */
@Composable
private fun GalleryGridCard(item: ListEntry, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            AsyncImage(
                model = item.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.67f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            )
            if (item.pages > 0) {
                Surface(
                    color = categoryColor(item.category),
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        text = "${item.pages}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
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
