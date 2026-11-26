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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.jm.JmApi
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.util.AppLog
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable

@Serializable
data class JmCommentsArgs(val albumId: Long, val title: String = "")

/** 禁漫接口每页 10 条，一次拉 2 页 = 20 条。 */
private const val TAG = "JmComments"
private const val API_PAGE_SIZE = 10
private const val BATCH_SIZE = 20

/**
 * 禁漫全部评论页，照 E 站的 Gallery Comments 排：
 * 灰色圆角卡片，第一行 用户名 + 时间，第二行正文。
 * 一次请求 20 条，滑到底自动再拉 20 条，直到没有更多（不设总条数上限）。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.JmCommentsScreen(
    args: JmCommentsArgs,
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    val listState = rememberLazyListState()
    var comments by remember { mutableStateOf<List<JmApi.JmComment>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var apiPage by remember { mutableStateOf(1) }

    suspend fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        try {
            val batch = mutableListOf<JmApi.JmComment>()
            while (batch.size < BATCH_SIZE && hasMore) {
                val one = JmApi.comments(args.albumId, apiPage)
                total = one.total
                apiPage += 1
                if (one.list.isEmpty()) {
                    hasMore = false
                    break
                }
                batch += one.list
                if (one.list.size < API_PAGE_SIZE) {
                    // 最后一页不满，说明没有下一页了
                    hasMore = false
                }
            }
            val merged = LinkedHashMap<String, JmApi.JmComment>()
            (comments + batch).forEach { comment ->
                val key = comment.CID.ifBlank { "${comment.UID}_${comment.addtime}_${comment.content.hashCode()}" }
                merged[key] = comment
            }
            comments = merged.values.toList()
            AppLog.i(TAG, "batch +${batch.size} loaded=${comments.size} total=$total apiPage=$apiPage hasMore=$hasMore")
            if (total > 0 && comments.size >= total) hasMore = false
            if (batch.isEmpty()) hasMore = false
            error = null
        } catch (e: Exception) {
            error = e.message ?: "載入失敗"
        } finally {
            loading = false
            loadedOnce = true
        }
    }

    LaunchedEffect(args.albumId) {
        if (comments.isEmpty()) loadMore()
    }

    // 滑到底部附近（倒数第 3 条）自动拉下一批
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= comments.size - 3) loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (total > 0) "評論（$total）" else "評論",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                !loadedOnce && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && comments.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = error!!, color = MaterialTheme.colorScheme.error)
                }
                comments.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "還沒有評論", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (args.title.isNotBlank()) {
                        item(key = "title") {
                            Text(
                                text = args.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    items(comments) { comment ->
                        JmCommentCard(comment)
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator()
                                error != null -> Text(
                                    text = error!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                hasMore -> Text(
                                    text = "下拉載入更多",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "沒有更多評論了",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "禁漫移動端接口只支持讀評論，發評論要在網頁端。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** E 站同款评论卡片：灰底圆角，用户名 + 时间一行，正文在下面。 */
@Composable
fun JmCommentCard(
    comment: JmApi.JmComment,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = comment.author.ifBlank { "匿名" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (comment.addtime.isNotBlank()) {
                Text(
                    text = comment.addtime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = comment.plainText.ifBlank { "（圖片評論）" },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
