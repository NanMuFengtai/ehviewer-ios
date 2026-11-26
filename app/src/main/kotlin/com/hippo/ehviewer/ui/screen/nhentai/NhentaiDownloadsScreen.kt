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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hippo.ehviewer.nhentai.NhentaiApi
import com.hippo.ehviewer.nhentai.NhentaiCdn
import com.hippo.ehviewer.nhentai.NhentaiDownload
import com.hippo.ehviewer.nhentai.NhentaiDownloads
import com.hippo.ehviewer.nhentai.NhentaiHistory
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.NhentaiDetailScreenDestination
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * nhentai 的下载页。
 *
 * 和 E 站的下载管理完全分开：这里只是「这本书整本下到本地了，离线也能看」。
 * 点条目直接进阅读器（有进度就续读），右边删本地文件。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NhentaiDownloadsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    DrawerHandle(true)

    val items by NhentaiDownloads.items.collectAsState()
    val history by NhentaiHistory.entries.collectAsState()
    var pendingDelete by remember { mutableStateOf<NhentaiDownload?>(null) }

    LaunchedEffect(Unit) {
        NhentaiDownloads.load()
        NhentaiHistory.load()
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.delete)) },
            text = { Text(text = stringResource(R.string.nhentai_download_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        NhentaiDownloads.delete(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.downloads)) },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { paddingValues ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.nhentai_downloads_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    val progress = history.firstOrNull { it.id == item.id }?.lastPage ?: 0
                    val active = NhentaiDownloads.isDownloading(item.id)
                    NhentaiDownloadRow(
                        item = item,
                        active = active,
                        onClick = {
                            when {
                                item.completed -> navigator.navigate(
                                    ReaderScreenDestination(ReaderScreenArgs.Nhentai(item.id, progress)),
                                )
                                active -> Unit
                                else -> NhentaiDownloads.start(item.detail)
                            }
                        },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }
}

@Composable
private fun NhentaiDownloadRow(item: NhentaiDownload, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                model = NhentaiCdn.thumb(item.detail.thumbnail.path),
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
                text = item.detail.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                item.completed -> Text(
                    text = stringResource(R.string.nhentai_downloaded),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                active -> {
                    Text(
                        text = stringResource(R.string.nhentai_downloading, item.done, item.total),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (item.total > 0) item.done.toFloat() / item.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                    )
                }
                else -> Text(
                    text = stringResource(R.string.nhentai_download_incomplete, item.done, item.total) +
                        " · " + stringResource(R.string.nhentai_download_resume),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
        }
    }
}
