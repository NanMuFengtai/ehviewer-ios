package com.hippo.ehviewer.ui.screen.nhentai

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.nhentai.NhentaiCdn
import com.hippo.ehviewer.nhentai.NhentaiHistory
import com.hippo.ehviewer.nhentai.NhentaiHistoryEntry
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.NhentaiDetailScreenDestination
import com.hippo.ehviewer.ui.screen.SearchBarScreen
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch

/**
 * nhentai 的浏览历史。
 *
 * 数据在 [NhentaiHistory]（JSON），和 E 站历史完全分开。
 * 点条目进详情，右侧叉号删单条，右上角清空全部。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NhentaiHistoryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    DrawerHandle(true)

    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var keyword by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val all by NhentaiHistory.entries.collectAsState()
    val entries = remember(all, keyword) {
        if (keyword.isBlank()) all else all.filter { it.title.contains(keyword, ignoreCase = true) }
    }

    LaunchedEffect(Unit) { NhentaiHistory.load() }

    SearchBarScreen(
        onApplySearch = { keyword = it },
        expanded = searchBarExpanded,
        onExpandedChange = { searchBarExpanded = it },
        title = stringResource(R.string.history),
        searchFieldHint = stringResource(R.string.nhentai_history_search_hint),
        trailingIcon = {
            if (all.isNotEmpty()) {
                IconButton(
                    onClick = {
                        scope.launch {
                            awaitConfirmationOrCancel(
                                confirmText = R.string.nhentai_clear_history,
                                text = { Text(text = stringResource(R.string.nhentai_clear_history_confirm)) },
                            )
                            NhentaiHistory.clear()
                        }
                    },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.nhentai_clear_history))
                }
            }
        },
    ) { contentPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (keyword.isBlank()) R.string.nhentai_history_empty else R.string.nhentai_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    NhentaiHistoryRow(
                        entry = entry,
                        onClick = { navigator.navigate(NhentaiDetailScreenDestination(NhentaiDetailArgs(entry.id))) },
                        onRemove = { NhentaiHistory.remove(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NhentaiHistoryRow(entry: NhentaiHistoryEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .aspectRatio(0.72f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = NhentaiCdn.thumb(entry.thumbnail),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    val progress = entry.progressText()
                    if (progress != null) {
                        append(progress)
                    } else {
                        append(entry.pages)
                        append("P")
                    }
                    val time = entry.timeText()
                    if (time.isNotEmpty()) {
                        append(" · ")
                        append(time)
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
            Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.delete))
        }
    }
}
