package com.hippo.ehviewer.ios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.ios.source.EhSource
import com.hippo.ehviewer.ios.source.Site
import com.hippo.ehviewer.ios.store.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * E 站的阅读页要逐页请求才能拿到原图，而且站点对并发很敏感（一次太多会 509）。
 * 这里全局限两个并发，并且每次请求后停一下。
 */
private val ehGate = Semaphore(2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    site: Site,
    galleryId: Long,
    title: String,
    pages: List<PageRef>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 进度记忆：下次打开直接跳到上次位置
    val progressKey = "progress.${site.name}.$galleryId"
    val startPage = remember(galleryId) {
        Prefs.getString(progressKey)?.toIntOrNull()?.coerceIn(0, (pages.size - 1).coerceAtLeast(0)) ?: 0
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    LaunchedEffect(listState, progressKey) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            Prefs.putString(progressKey, "$index")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF111111),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "$title  (${pages.size})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(pages) { index, page ->
                if (page.isPageUrl) {
                    EhPageView(index, page.url)
                } else {
                    DirectPageView(index, page)
                }
            }
        }
    }
}

/** N 站这类直接给图片地址的，按原始宽高占位。 */
@Composable
private fun DirectPageView(index: Int, page: PageRef) {
    val ratio = if (page.width > 0 && page.height > 0) {
        page.width.toFloat() / page.height
    } else {
        0.7f
    }
    Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A))) {
        AsyncImage(
            model = page.url,
            contentDescription = "第 ${index + 1} 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
        )
    }
}

/** E 站的页面地址，先解析出原图再显示。 */
@Composable
private fun EhPageView(index: Int, pageUrl: String) {
    var imageUrl by remember(pageUrl) { mutableStateOf<String?>(null) }
    var error by remember(pageUrl) { mutableStateOf<String?>(null) }
    var landing by remember(pageUrl) { mutableStateOf(true) }

    LaunchedEffect(pageUrl) {
        landing = true
        error = null
        ehGate.withPermit {
            runCatching { EhSource.readerPage(pageUrl) }
                .onSuccess { (src, _) ->
                    imageUrl = src
                    if (src == null) error = "这一页拿不到原图"
                }
                .onFailure { error = it.message ?: it.toString() }
        }
        // 给站点留点空隙，避免连续请求被限流
        delay(260)
        landing = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            imageUrl != null -> AsyncImage(
                model = imageUrl,
                contentDescription = "第 ${index + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            error != null -> Text(
                text = "第 ${index + 1} 页: $error",
                color = Color(0xFF8A8A8A),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )

            landing -> CircularProgressIndicator(
                color = Color(0xFF888888),
                modifier = Modifier.fillMaxWidth(0.12f),
            )
        }
    }
}
