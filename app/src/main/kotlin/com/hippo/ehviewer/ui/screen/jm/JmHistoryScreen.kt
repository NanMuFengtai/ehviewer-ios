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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.hippo.ehviewer.jm.JmHistory
import com.hippo.ehviewer.jm.JmHistoryEntry
import com.hippo.ehviewer.jm.JmApi
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.JmDetailScreenDestination
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/** 禁漫自己的阅读历史（和 E 站 / nhentai 的历史互不干扰）。 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.JmHistoryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    DrawerHandle(true)
    val entries by JmHistory.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { JmHistory.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.history)) },
                navigationIcon = { NavigationIcon() },
                actions = {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.jm_clear_history))
                    }
                },
            )
        },
    ) { paddingValues ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.jm_history_empty))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
            ) {
                items(entries, key = { it.albumId }) { entry ->
                    JmHistoryRow(entry) {
                        navigator.navigate(JmDetailScreenDestination(JmDetailArgs(entry.albumId)))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(text = stringResource(R.string.jm_clear_history_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    JmHistory.clear()
                    confirmClear = false
                }) { Text(text = stringResource(R.string.jm_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(text = stringResource(R.string.jm_cancel)) }
            },
        )
    }
}

@Composable
private fun JmHistoryRow(entry: JmHistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(88.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = JmApi.coverCandidates(entry.albumId).first(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detailText = buildString {
                val chapter = entry.chapterText()
                if (chapter.isNotBlank()) append(chapter).append(" · ")
                append(entry.progressText() ?: stringResource(R.string.jm_not_started))
                if (entry.timeText().isNotBlank()) append(" · ").append(entry.timeText())
            }
            Text(
                text = detailText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { JmHistory.remove(entry.albumId) }) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.delete),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
