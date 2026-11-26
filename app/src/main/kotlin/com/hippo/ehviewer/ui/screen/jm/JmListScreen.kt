package com.hippo.ehviewer.ui.screen.jm

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.twotone.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.jm.JmApi
import com.hippo.ehviewer.jm.JmAuth
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.JmDetailScreenDestination
import com.hippo.ehviewer.ui.destinations.JmFavoriteScreenDestination
import com.hippo.ehviewer.ui.destinations.JmLoginScreenDestination
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.screen.SearchBarScreen
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.JmListScreen(
    navigator: DestinationsNavigator,
    query: String = "",
    vm: JmListViewModel = viewModel { JmListViewModel() },
) = Screen(navigator) {
    val gridState = rememberLazyGridState()
    val searchFieldState = rememberTextFieldState()
    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var orderMenuOpen by remember { mutableStateOf(false) }

    DrawerHandle(!searchBarExpanded)
    // 从详情页点作者 / 标签进来时带着关键词，直接出搜索结果
    LaunchedEffect(query) {
        JmAuth.load()
        if (query.isNotBlank()) {
            searchFieldState.edit { replace(0, length, query) }
            vm.search(query)
        } else {
            vm.ensureLoaded()
        }
    }

    LaunchedEffect(gridState, vm) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= vm.items.size - 6) vm.loadMore()
            }
    }

    SearchBarScreen(
        onApplySearch = { text ->
            // 「1233797」「JM1233797」这种直接当 JM 码跳到本子详情，其余按关键词搜
            val jmId = parseJmCode(text)
            if (jmId != null) {
                navigator.navigate(JmDetailScreenDestination(JmDetailArgs(jmId)))
            } else {
                vm.search(text)
            }
        },
        expanded = searchBarExpanded,
        onExpandedChange = { searchBarExpanded = it },
        title = stringResource(R.string.jm_site),
        searchFieldHint = stringResource(R.string.jm_search_hint),
        searchFieldState = searchFieldState,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { vm.reload() },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                }
                IconButton(
                    onClick = {
                        if (JmAuth.loggedIn) {
                            navigator.navigate(JmFavoriteScreenDestination)
                        } else {
                            navigator.navigate(JmLoginScreenDestination)
                        }
                    },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = if (JmAuth.loggedIn) Icons.Default.AccountCircle else Icons.Outlined.Login,
                        contentDescription = if (JmAuth.loggedIn) "我的收藏" else "登入",
                    )
                }
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(JmApi.categories, key = { it.second }) { (name, value) ->
                    FilterChip(
                        selected = vm.category == value,
                        onClick = { vm.chooseCategory(value) },
                        label = { Text(text = name) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Text(
                        text = JmApi.orders.firstOrNull { it.second == vm.order }?.first ?: "最新",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { orderMenuOpen = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Icon(
                        imageVector = Icons.TwoTone.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(start = 40.dp),
                    )
                    DropdownMenu(expanded = orderMenuOpen, onDismissRequest = { orderMenuOpen = false }) {
                        JmApi.orders.forEach { (name, value) ->
                            DropdownMenuItem(
                                text = { Text(text = name) },
                                onClick = {
                                    orderMenuOpen = false
                                    vm.chooseOrder(value)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (vm.query.isNotBlank()) {
                    Text(
                        text = "「${vm.query}」",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val failure = vm.error
            when {
                vm.items.isEmpty() && vm.loading -> Centered { CircularProgressIndicator() }
                vm.items.isEmpty() && failure != null -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.jm_load_failed, failure),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.action_retry),
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable { vm.reload() }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                vm.items.isEmpty() -> Centered { Text(text = stringResource(R.string.jm_empty)) }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.items, key = { it.id }) { item ->
                        JmCard(item) {
                            navigator.navigate(JmDetailScreenDestination(JmDetailArgs(item.albumId)))
                        }
                    }
                    if (vm.loading) {
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

/** 「1233797」「JM1233797」「jm 1233797」都算 JM 码；返回 null 表示不是码，走普通搜索。 */
private val JM_CODE_REGEX = Regex("(?i)^(?:jm)?\\s*0*(\\d{1,9})$")

internal fun parseJmCode(text: String): Long? =
    JM_CODE_REGEX.matchEntire(text.trim())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun JmCard(item: JmApi.AlbumItem, onClick: () -> Unit) {
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
