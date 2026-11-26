package com.hippo.ehviewer.ui.screen.jm

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.jm.JmApi
import com.hippo.ehviewer.jm.JmAuth
import com.hippo.ehviewer.jm.JmDownloader
import com.hippo.ehviewer.jm.JmFavorites
import com.hippo.ehviewer.jm.JmHistory
import com.hippo.ehviewer.jm.JmHistoryEntry
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.JmCommentsScreenDestination
import com.hippo.ehviewer.ui.destinations.JmListScreenDestination
import com.hippo.ehviewer.ui.destinations.JmLoginScreenDestination
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class JmDetailArgs(val albumId: Long)

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.JmDetailScreen(
    args: JmDetailArgs,
    navigator: DestinationsNavigator,
    vm: JmDetailViewModel = viewModel { JmDetailViewModel(args.albumId) },
) = Screen(navigator) {
    DrawerHandle(true)

    val album = vm.album
    val error = vm.error
    val history by JmHistory.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<JmApi.JmComment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(true) }
    var commentsTotal by remember { mutableStateOf(0) }
    var favoriteBusy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadDone by remember { mutableStateOf(0) }
    var downloadTotal by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        JmAuth.load()
        JmHistory.load()
        vm.ensureLoaded()
        if (JmAuth.loggedIn) JmFavorites.refresh()
    }
    LaunchedEffect(args.albumId) {
        commentsLoading = true
        runCatching { JmApi.comments(args.albumId) }
            .onSuccess {
                comments = it.list
                commentsTotal = it.total
            }
        commentsLoading = false
    }
    LaunchedEffect(history) {
        vm.refreshProgress(history.firstOrNull { it.albumId == args.albumId })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = album?.name ?: stringResource(R.string.jm_site),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            val current = album
            val failure = error
            when {
                current != null -> with(navigator) {
                    JmDetailContent(
                        album = current,
                        progress = vm.progress,
                        favorite = JmFavorites.contains(current.albumId) || current.is_favorite,
                        comments = comments,
                        commentsLoading = commentsLoading,
                        commentsTotal = commentsTotal,
                        downloading = downloading,
                        downloadDone = downloadDone,
                        downloadTotal = downloadTotal,
                        onSearch = { keyword ->
                            navigator.navigate(JmListScreenDestination(query = keyword))
                        },
                        onToggleFavorite = {
                            if (!favoriteBusy) {
                                if (!JmAuth.loggedIn) {
                                    navigator.navigate(JmLoginScreenDestination)
                                } else {
                                    favoriteBusy = true
                                    scope.launch {
                                        runCatching { JmApi.toggleFavorite(current.albumId) }
                                            .onSuccess { JmFavorites.mark(current.albumId, it) }
                                        favoriteBusy = false
                                    }
                                }
                            }
                        },
                        onDownload = {
                            if (!downloading) {
                                val chapterId = current.series.firstOrNull { it.chapterId == (vm.progress?.chapterId ?: 0) }?.chapterId
                                    ?: current.series.firstOrNull()?.chapterId
                                    ?: current.albumId
                                downloading = true
                                downloadDone = 0
                                downloadTotal = current.pageCount
                                scope.launch {
                                    JmDownloader.downloadChapter(current.albumId, chapterId) { done, total ->
                                        downloadDone = done
                                        downloadTotal = total
                                    }
                                    downloading = false
                                }
                            }
                        },
                        onShowComments = {
                            navigator.navigate(
                                JmCommentsScreenDestination(
                                    JmCommentsArgs(albumId = current.albumId, title = current.name),
                                ),
                            )
                        },
                    ) { chapterId, page ->
                        navigator.navigate(
                            ReaderScreenDestination(
                                ReaderScreenArgs.Jm(
                                    albumId = current.albumId,
                                    chapterId = chapterId,
                                    page = page,
                                ),
                            ),
                        )
                    }
                }
                failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.jm_load_failed, failure),
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
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JmDetailContent(
    album: JmApi.Album,
    progress: JmHistoryEntry?,
    favorite: Boolean,
    comments: List<JmApi.JmComment>,
    commentsLoading: Boolean,
    commentsTotal: Int,
    downloading: Boolean,
    downloadDone: Int,
    downloadTotal: Int,
    onSearch: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onShowComments: () -> Unit,
    onRead: (chapterId: Long, page: Int) -> Unit,
) {
    val chapters = album.series.ifEmpty {
        listOf(JmApi.Chapter(id = "${album.albumId}", name = "", sort = 1))
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .width(116.dp)
                        .aspectRatio(0.72f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = JmApi.coverCandidates(album.albumId).first(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 作者靠右，每个名字各自可点：点斜杠前搜前一个，点斜杠后搜后一个
                    if (album.author.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                text = "作者：",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            album.author.forEachIndexed { index, name ->
                                if (index > 0) {
                                    Text(
                                        text = " / ",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { onSearch(name) }
                                        .padding(horizontal = 2.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 喜欢 / 观看 / JM 码 / 页数，四行靠右
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "♥ ${formatCount(album.likes)} 喜歡",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "👁 ${formatCount(album.total_views)} 觀看",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val context = LocalContext.current
                        val clipboard = LocalClipboardManager.current
                        Text(
                            text = "JM${album.albumId}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val code = "JM${album.albumId}"
                                    clipboard.setText(AnnotatedString(code))
                                    Toast.makeText(context, "已複製 $code", Toast.LENGTH_SHORT).show()
                                },
                            ),
                        )
                        Text(
                            text = "${album.pageCount} 頁" + if (album.isSeries) " · ${chapters.size} 話" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 下载 / 收藏 / 阅读，阅读是最右边的主按钮
        item(key = "actions") {
            val target = progress?.takeIf { it.chapterId > 0 }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onDownload,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = when {
                            downloading -> "$downloadDone/$downloadTotal"
                            downloadTotal > 0 && downloadDone >= downloadTotal -> "已下載"
                            else -> "下載"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = if (favorite) "已收藏" else "收藏",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = {
                        if (target != null) onRead(target.chapterId, target.lastPage) else onRead(chapters.first().chapterId, 0)
                    },
                    modifier = Modifier.weight(1.2f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = if (target != null) {
                            val chapter = chapters.firstOrNull { it.chapterId == target.chapterId }
                            val sort = chapter?.sort ?: target.chapterSort
                            if (album.isSeries) {
                                stringResource(R.string.jm_continue_chapter, sort, target.lastPage + 1)
                            } else {
                                stringResource(R.string.read_from, target.lastPage + 1)
                            }
                        } else {
                            stringResource(R.string.jm_read)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (album.description.isNotBlank()) {
            item(key = "description") {
                Column {
                    Text(text = "描述", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = album.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (album.tags.isNotEmpty()) {
            item(key = "tags") {
                Column {
                    Text(
                        text = "標籤",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        album.tags.forEach { tag ->
                            AssistChip(onClick = { onSearch(tag) }, label = { Text(text = tag) })
                        }
                    }
                }
            }
        }

        // 评论区（照 E 站：详情页只放最新一条灰卡片，点进去看全部）
        item(key = "comments-title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "評論", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "共 ${album.comment_total.ifBlank { commentsTotal.toString() }} 條",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (commentsLoading) {
            item(key = "comments-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (comments.isEmpty()) {
            item(key = "comments-empty") {
                Text(
                    text = "還沒有評論",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item(key = "comments-preview") {
                JmCommentCard(
                    comment = comments.first(),
                    onClick = onShowComments,
                )
            }
            item(key = "comments-more") {
                TextButton(onClick = onShowComments) {
                    Text(
                        text = if (comments.size > 1 || commentsTotal > 1) {
                            "查看全部 ${album.comment_total.ifBlank { commentsTotal.toString() }} 條評論"
                        } else {
                            "查看評論"
                        },
                    )
                }
            }
        }

        if (album.isSeries) {
            item(key = "chapter-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.jm_chapters),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "共 ${chapters.size} 話",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onRead(chapter.chapterId, 0) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "第 ${chapter.sort.takeIf { it > 0 } ?: (index + 1)} 話",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (progress?.chapterId == chapter.chapterId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (chapter.name.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = chapter.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (progress?.chapterId == chapter.chapterId && progress.lastPage > 0) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${progress.lastPage + 1}/${progress.pages}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** 18115 -> 18.1K，151118 -> 151.1K，1510000 -> 1.5M。 */
private fun formatCount(raw: String): String {
    val value = raw.toLongOrNull() ?: return raw.ifBlank { "0" }
    return when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

