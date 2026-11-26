package com.hippo.ehviewer.ui.screen.nhentai

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ehviewer.core.ui.util.SETNodeGenerator
import com.ehviewer.core.ui.util.SharedElementBox
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.hippo.ehviewer.nhentai.NhentaiApi
import com.hippo.ehviewer.nhentai.NhentaiCdn
import com.hippo.ehviewer.nhentai.NhentaiHistory
import com.hippo.ehviewer.nhentai.NhentaiSession
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.NhentaiAccountScreenDestination
import com.hippo.ehviewer.ui.destinations.NhentaiDetailScreenDestination
import com.hippo.ehviewer.ui.destinations.NhentaiListScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.SearchBarScreen
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.serialization.Serializable

@Serializable
data class NhentaiListArgs(
    val mode: String = NhentaiApi.MODE_LATEST,
    val tagId: Long = 0,
    val tagName: String = "",
)

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NhentaiListScreen(
    args: NhentaiListArgs,
    navigator: DestinationsNavigator,
    vm: NhentaiListViewModel = viewModel { NhentaiListViewModel(args) },
) = Screen(navigator) {
    val gridState = rememberLazyGridState()
    val searchFieldState = rememberTextFieldState()
    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    val accessToken by Settings.nhentaiAccessToken.collectAsState()
    val signedIn = accessToken.isNotBlank()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val history by NhentaiHistory.entries.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.lastPage } }

    LaunchedEffect(Unit) { NhentaiHistory.load() }

    DrawerHandle(!searchBarExpanded)

    // 只有第一次进入才请求；从详情页返回时 ViewModel 还在，列表原样保留
    LaunchedEffect(Unit) { vm.ensureLoaded() }

    LaunchedEffect(gridState, vm) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= vm.items.size - 5) vm.loadMore()
            }
    }

    val title = when {
        vm.activeQuery.isNotBlank() -> vm.activeQuery
        args.mode == NhentaiApi.MODE_POPULAR -> stringResource(R.string.nhentai_popular)
        args.mode == NhentaiApi.MODE_TAGGED && args.tagName.isNotBlank() -> args.tagName
        args.mode == NhentaiApi.MODE_FAVORITES -> stringResource(R.string.nhentai_favorites)
        args.mode == NhentaiApi.MODE_SEARCH -> stringResource(R.string.nhentai_search)
        else -> stringResource(R.string.nhentai_home)
    }

    SearchBarScreen(
        onApplySearch = { query -> vm.search(query) },
        expanded = searchBarExpanded,
        onExpandedChange = { searchBarExpanded = it },
        title = title,
        searchFieldHint = stringResource(R.string.nhentai_search_hint),
        searchFieldState = searchFieldState,
        trailingIcon = {
            IconButton(
                onClick = { vm.reload() },
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
            }
            // 和 E 站搜索栏右上角一样：账号/登录入口
            IconButton(
                onClick = { navigator.navigate(NhentaiAccountScreenDestination) },
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = if (signedIn) Icons.Default.Person else Icons.Default.NoAccounts,
                    contentDescription = stringResource(R.string.settings_nhentai_account),
                )
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            val error = vm.error
            if (args.mode == NhentaiApi.MODE_FAVORITES && !NhentaiSession.isSignedIn) {
                Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.nhentai_login_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        NhentaiSignInButton(navigator, Modifier.padding(horizontal = 24.dp))
                    }
                }
            } else when {
                vm.items.isEmpty() && vm.loading -> Centered { CircularProgressIndicator() }
                vm.items.isEmpty() && error != null -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.nhentai_load_failed, error),
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
                vm.items.isEmpty() -> Centered { Text(text = stringResource(R.string.nhentai_empty)) }
                else -> with(nhentaiListThumbs) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(vm.items, key = { it.id }) { item ->
                            NhentaiCard(
                                item = item,
                                progress = if (showProgress) progressById[item.id] ?: 0 else 0,
                            ) {
                                navigator.navigate(NhentaiDetailScreenDestination(NhentaiDetailArgs(item.id)))
                            }
                        }
                        if (vm.loading) {
                            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        } else if (error != null) {
                            item(key = "load-more-error", span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.nhentai_load_failed, error),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { vm.loadMore() }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope, _: SETNodeGenerator)
private fun NhentaiCard(item: NhentaiApi.GalleryItem, progress: Int = 0, onClick: () -> Unit) {
    Column(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        SharedElementBox(key = nhentaiCoverKey(item.id), shape = ShapeDefaults.Medium) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = NhentaiCdn.thumb(item.thumbnail),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (progress > 0) "${progress + 1}/${item.num_pages}P" else "${item.num_pages}P",
            style = MaterialTheme.typography.labelSmall,
            color = if (progress > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NhentaiTagsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    DrawerHandle(true)

    var tags by remember { mutableStateOf<List<NhentaiApi.Tag>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        runCatching { NhentaiApi.tags("tag", 1, 100) }
            .onSuccess { tags = it.result }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nhentai_tags)) },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            val failure = error
            when {
                loading -> Centered { CircularProgressIndicator() }
                failure != null -> Centered {
                    Text(
                        text = stringResource(R.string.nhentai_load_failed, failure),
                        modifier = Modifier.clickable { reloadKey++ }.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(tags, key = { it.id }) { tag ->
                        ListItem(
                            headlineContent = { Text(text = tag.name) },
                            supportingContent = { Text(text = "${tag.count}") },
                            modifier = Modifier.clickable {
                                navigator.navigate(
                                    NhentaiListScreenDestination(
                                        NhentaiListArgs(
                                            mode = NhentaiApi.MODE_TAGGED,
                                            tagId = tag.id,
                                            tagName = tag.name,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
