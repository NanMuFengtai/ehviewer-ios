package com.hippo.ehviewer.ui.screen.nhentai

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.util.SETNodeGenerator
import com.ehviewer.core.ui.util.SharedElementBox
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.hippo.ehviewer.nhentai.NhentaiApi
import com.hippo.ehviewer.nhentai.NhentaiCdn
import com.hippo.ehviewer.nhentai.NhentaiDetailCache
import com.hippo.ehviewer.nhentai.NhentaiDownload
import com.hippo.ehviewer.nhentai.NhentaiDownloads
import com.hippo.ehviewer.nhentai.NhentaiHistory
import com.hippo.ehviewer.nhentai.NhentaiHistoryEntry
import com.hippo.ehviewer.nhentai.NhentaiImages
import com.hippo.ehviewer.nhentai.NhentaiSession
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.NhentaiAccountScreenDestination
import com.hippo.ehviewer.ui.destinations.NhentaiListScreenDestination
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.util.AppLog
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okio.Path

@Serializable
data class NhentaiDetailArgs(val id: Long)

private const val TAG = "NhentaiDetail"

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NhentaiDetailScreen(
    args: NhentaiDetailArgs,
    navigator: DestinationsNavigator,
    vm: NhentaiDetailViewModel = viewModel { NhentaiDetailViewModel(args.id) },
) = Screen(navigator) {
    DrawerHandle(true)

    val detail = vm.detail
    val coverPath = vm.cover
    val error = vm.error
    val favorited = vm.favorited

    val downloads by NhentaiDownloads.items.collectAsState()
    val historyEntries by NhentaiHistory.entries.collectAsState()
    LaunchedEffect(Unit) {
        NhentaiDownloads.load()
        NhentaiHistory.load()
        vm.ensureLoaded()
    }
    val downloadRecord = downloads.firstOrNull { it.id == args.id }
    val lastPage = historyEntries.firstOrNull { it.id == args.id }?.lastPage ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detail?.displayTitle ?: stringResource(R.string.source_nhentai),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            val current = detail
            val failure = error
            when {
                failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.nhentai_load_failed, failure),
                        modifier = Modifier.clickable { vm.reload() }.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> with(nhentaiDetailThumbs) {
                    NhentaiDetailGrid(
                        detail = current,
                        cover = coverPath,
                        favorited = favorited,
                        download = downloadRecord,
                        lastPage = lastPage,
                        onToggleFavorite = {
                            vm.toggleFavorite { navigator.navigate(NhentaiAccountScreenDestination) }
                        },
                        onDownload = { NhentaiDownloads.start(current) },
                        onRead = { index ->
                            navigator.navigate(ReaderScreenDestination(ReaderScreenArgs.Nhentai(current.id, index)))
                        },
                        onTagClick = { tag ->
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

private val TagOrder = listOf("parody", "character", "group", "artist", "language", "category", "tag")

private fun labelOf(type: String): Int = when (type) {
    "parody" -> R.string.nhentai_info_parody
    "character" -> R.string.nhentai_info_character
    "group" -> R.string.nhentai_info_group
    "artist" -> R.string.nhentai_info_artist
    "language" -> R.string.nhentai_info_language
    "category" -> R.string.nhentai_info_category
    else -> R.string.nhentai_info_tags
}

private fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochSeconds * 1000))
}

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope, _: SETNodeGenerator)
private fun NhentaiDetailGrid(
    detail: NhentaiApi.Detail,
    cover: Path?,
    favorited: Boolean?,
    download: NhentaiDownload?,
    lastPage: Int,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onRead: (Int) -> Unit,
    onTagClick: (NhentaiApi.Tag) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            DetailHeader(detail, cover, onTagClick)
        }
        item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                // 下載 / 收藏 / 阅读 三个按钮并排：阅读放最右手边（拇指位），和用户习惯一致
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        DownloadButtonContent(detail, download)
                    }
                    FilledTonalButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            imageVector = if (favorited == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (favorited == true) {
                                stringResource(R.string.nhentai_favorited)
                            } else {
                                stringResource(R.string.nhentai_favorite)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = {
                            val target = if (detail.pages.isEmpty()) 0 else lastPage.coerceIn(0, detail.pages.size - 1)
                            onRead(target)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (lastPage > 0) {
                                stringResource(R.string.read_from, lastPage + 1)
                            } else {
                                stringResource(R.string.nhentai_read)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (detail.tags.isNotEmpty()) {
            item(key = "tags", span = { GridItemSpan(maxLineSpan) }) {
                TagGroups(detail.tags, onTagClick)
            }
        }
        if (detail.pages.isNotEmpty()) {
            item(key = "preview-title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.nhentai_preview),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            itemsIndexed(detail.pages) { index, page ->
                PreviewItem(
                    url = NhentaiCdn.thumb(page.thumbnail),
                    number = index + 1,
                    read = lastPage > 0 && index <= lastPage,
                    onClick = { onRead(index) },
                )
            }
        }
    }
}

@Composable
private fun DownloadButtonContent(detail: NhentaiApi.Detail, download: NhentaiDownload?) {
    val active = download != null && NhentaiDownloads.isDownloading(detail.id)
    when {
        download?.completed == true -> {
            Icon(
                imageVector = Icons.Default.DownloadDone,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.nhentai_downloaded_short),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        download != null && active -> Text(
            text = "${download.done}/${download.total}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        download != null -> Text(
            text = "${download.done}/${download.total}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        else -> {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.nhentai_download),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope, _: SETNodeGenerator)
private fun DetailHeader(
    detail: NhentaiApi.Detail,
    cover: Path?,
    onTagClick: (NhentaiApi.Tag) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SharedElementBox(key = nhentaiCoverKey(detail.id), shape = ShapeDefaults.Medium) {
            Box(
                modifier = Modifier
                    .width(116.dp)
                    .aspectRatio(0.72f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = cover?.toUri() ?: NhentaiCdn.thumb(detail.thumbnail.path),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = detail.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
            val japanese = detail.title.japanese
            if (!japanese.isNullOrBlank() && japanese != detail.title.english) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = japanese,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 作者靠右，多个作者斜杠分隔、各自可点：点谁就搜谁的作品
            val artists = detail.tags.filter { it.type == "artist" }.ifEmpty {
                detail.tags.filter { it.type == "group" }
            }
            if (artists.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(R.string.nhentai_info_artist) + "：",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    artists.forEachIndexed { index, tag ->
                        if (index > 0) {
                            Text(
                                text = " / ",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onTagClick(tag) }
                                .padding(horizontal = 2.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 收藏 / 页数 / 编号 / 上传日期，四行靠右，和禁漫详情页一致
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "♥ ${formatCount(detail.num_favorites)} 收藏",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${detail.num_pages} 頁",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "#${detail.id}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val uploaded = formatDate(detail.upload_date)
                if (uploaded.isNotEmpty()) {
                    Text(
                        text = "上傳於 $uploaded",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 18115 -> 18.1K，151118 -> 151.1K，1510000 -> 1.5M。 */
private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagGroups(tags: List<NhentaiApi.Tag>, onTagClick: (NhentaiApi.Tag) -> Unit) {
    val groups = tags.groupBy { it.type }.toList()
        .sortedBy { (type, _) -> TagOrder.indexOf(type).let { if (it < 0) TagOrder.size else it } }
    Column {
        groups.forEach { (type, list) ->
            // E 站那种一行一组：左边类型胶囊（蓝底），右边这一组的标签值（粉底）自动换行
            Row(modifier = Modifier.fillMaxWidth()) {
                TagPill(text = stringResource(labelOf(type)), isGroup = true)
                FlowRow {
                    list.forEach { tag ->
                        TagPill(
                            text = tag.name,
                            modifier = Modifier.clickable { onTagClick(tag) },
                        )
                    }
                }
            }
        }
    }
}

/** E 站 TagChip 同款：64dp 圆角胶囊，类型用 primaryContainer，值用 tertiaryContainer。 */
@Composable
private fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
) {
    Surface(
        modifier = modifier.padding(4.dp),
        color = if (isGroup) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        shape = RoundedCornerShape(64.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PreviewItem(url: String, number: Int, read: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(0.72f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = "$number",
            style = MaterialTheme.typography.labelSmall,
            color = if (read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
        )
    }
}
