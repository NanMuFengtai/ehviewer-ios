package com.hippo.ehviewer.ui.screen.jm

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.jm.JmApi
import com.hippo.ehviewer.jm.JmAuth
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.JmDetailScreenDestination
import com.hippo.ehviewer.ui.destinations.JmLoginScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/** 我的收藏：登录后从 /favorite 拉，三列网格 + 滚动加载。 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.JmFavoriteScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val gridState = rememberLazyGridState()
    var items by remember { mutableStateOf<List<JmApi.AlbumItem>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }

    val loggedIn = JmAuth.loggedIn

    suspend fun loadMore() {
        if (loading) return
        loading = true
        runCatching { JmApi.favorites(page + 1) }
            .onSuccess { result ->
                page += 1
                total = result.total
                items = items + result.list.filter { new -> items.none { it.id == new.id } }
                error = null
            }
            .onFailure { error = it.message ?: "載入失敗" }
        loadedOnce = true
        loading = false
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn && items.isEmpty()) loadMore()
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= items.size - 6 && items.size < total) loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (total > 0) "我的收藏（$total）" else "我的收藏") },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !loggedIn -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("登入後才能看收藏", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { navigator.navigate(JmLoginScreenDestination) }) {
                        Text("去登入")
                    }
                }
                items.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                items.isEmpty() && loadedOnce -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error ?: "還沒有收藏")
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        FavoriteCard(item) {
                            navigator.navigate(JmDetailScreenDestination(JmDetailArgs(item.albumId)))
                        }
                    }
                    if (loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(item: JmApi.AlbumItem, onClick: () -> Unit) {
    Column(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = JmApi.coverCandidates(item.albumId).first(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.author.ifBlank { item.category.title },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
