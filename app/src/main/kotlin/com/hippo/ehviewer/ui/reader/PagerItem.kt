package com.hippo.ehviewer.ui.reader

import android.graphics.drawable.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.unreachable
import com.google.accompanist.drawablepainter.DrawablePainter
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.progressObserved
import com.hippo.ehviewer.gallery.statusObserved
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.util.AdsPlaceholderFile
import com.hippo.ehviewer.util.AppLog
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.withFrameNanos

@Composable
fun PagerItem(
    page: Page,
    pageLoader: PageLoader,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    /** 翻页模式：错误/等待页铺满整屏；条漫模式按页面比例占一块，别把长列表撑坏。 */
    fillPageHeight: Boolean = false,
) {
    LaunchedEffect(Unit) {
        pageLoader.request(page.index)
        // In case page loader restart
        page.statusFlow.drop(1).collect {
            if (page.statusFlow.value == PageStatus.Queued) {
                pageLoader.request(page.index)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            pageLoader.cancelRequest(page.index)
        }
    }
    val defaultError = stringResource(id = R.string.decode_image_error)
    val state = page.statusObserved
    // 观察状态切换：预览 -> 原图应该是 Ready -> Ready，中间不该出现 Loading
    LaunchedEffect(state::class) {
        AppLog.i("PagerItem", "page ${page.index + 1} state=${state::class.simpleName}")
    }
    when (state) {
        is PageStatus.Queued, is PageStatus.Loading -> {
            Box(
                modifier = if (fillPageHeight) modifier.fillMaxSize() else modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state is PageStatus.Loading,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "progressState",
                ) { determinate ->
                    val animatedProgress by animateFloatAsState(
                        targetValue = state.progressObserved,
                        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                        label = "progress",
                    )
                    if (determinate) {
                        CircularWavyProgressIndicator(progress = { animatedProgress })
                    } else {
                        CircularWavyProgressIndicator()
                    }
                }
            }
        }
        is PageStatus.Ready -> {
            val image = state.image
            // 同时拿住「当前显示的图 + 它的 painter」：原图替换预览图时先把新图挂上去，
            // 旧图等新图至少画过一帧再放引用，否则中间会闪一下黑（或画到已回收的位图）。
            var displayed by remember { mutableStateOf<Pair<Image, Painter>?>(null) }
            LaunchedEffect(image) {
                if (!image.pin()) return@LaunchedEffect
                val previous = displayed
                displayed = image to image.toPainter()
                if (previous != null && previous.first !== image) {
                    AppLog.i(
                        "PagerItem",
                        "page ${page.index + 1} swap ${previous.first.intrinsicSize.width}x" +
                            "${previous.first.intrinsicSize.height} -> ${image.intrinsicSize.width}x" +
                            "${image.intrinsicSize.height} seamless",
                    )
                    withFrameNanos { }
                    if (previous.first.unpin()) {
                        pageLoader.notifyPageWait(page.index, previous.first)
                    }
                }
            }
            DisposableEffect(Unit) {
                onDispose { displayed?.first?.unpin() }
            }
            val painter = displayed?.second
            painter?.let { painter ->
                val drawable = (painter as? DrawablePainter)?.drawable
                val grayScale by Settings.grayScale.collectAsState()
                val invert by Settings.invertedColors.collectAsState()
                Image(
                    // DrawablePainter <: RememberObserver
                    painter = remember(painter) { painter },
                    contentDescription = null,
                    modifier = Modifier.thenIf(drawable is Animatable) {
                        onVisibilityChanged(minDurationMs = 33, minFractionVisible = 0.5f) {
                            drawable!!.setVisible(it, false)
                        }
                    }.then(contentModifier).fillMaxSize(),
                    // 预览图只有 200x285：按原尺寸 Inside 画会缩成中间一张小图，
                    // 看着像"又加载了一遍"。按宽度铺满，和原图占位一致，替换时才是无感的。
                    contentScale = if (image.isPreview) ContentScale.FillWidth else contentScale,
                    colorFilter = when {
                        grayScale && invert -> grayScaleAndInvertFilter
                        grayScale -> grayScaleFilter
                        invert -> invertFilter
                        else -> null
                    },
                )
            } ?: run {
                // 只有这一页第一次合成时才会走到这里；swap 过程中不该出现，出现就说明还会闪
                AppLog.w("PagerItem", "page ${page.index + 1} blank frame (no painter)")
                Spacer(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT))
            }
        }
        is PageStatus.Blocked -> {
            AdsPlaceholder(
                modifier = modifier.fillMaxSize(),
                contentScale = if (contentScale == ContentScale.Inside) ContentScale.Fit else contentScale,
            )
        }
        is PageStatus.Error -> {
            // 失败时不要把整页做成黑屏：有预览图就拿它当底图，错误提示半透明地叠在上面
            val preview = remember(state) { pageLoader.errorPreview(page.index) }
            Box(modifier = if (fillPageHeight) modifier.fillMaxSize() else modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT)) {
                PreviewBackdrop(preview, ContentScale.Crop)
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = defaultError,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = state.message.orEmpty(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(
                        onClick = { pageLoader.retryPage(page.index) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(text = stringResource(id = R.string.action_retry))
                    }
                }
            }
        }
    }
}

/** 错误页 / 等待页的预览底图：整页铺满，再压一层轻遮罩，让上面的文字看得清。 */
@Composable
private fun PreviewBackdrop(preview: Image?, contentScale: ContentScale) {
    var painter by remember(preview) { mutableStateOf<Painter?>(null) }
    LaunchedEffect(preview) {
        if (preview != null && preview.pin()) {
            painter = preview.toPainter()
            try {
                awaitCancellation()
            } finally {
                preview.unpin()
            }
        }
    }
    val current = painter ?: return
    Image(
        painter = remember(current) { current },
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
    )
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
}

private fun Image.toPainter() = when (val image = innerImage) {
    is BitmapImage -> BitmapPainter(image.bitmap, intrinsicSize.toSize())
    is DrawableImage -> DrawablePainter(image.drawable)
    else -> unreachable()
}

private const val DEFAULT_ASPECT = 1 / 1.4125f

private val invertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
private val grayScaleMatrix = ColorMatrix().apply { setToSaturation(0f) }
private val grayScaleAndInvertMatrix = ColorMatrix().also { mtx ->
    mtx.setToSaturation(0f)
    mtx *= invertMatrix
}

private val grayScaleFilter = ColorFilter.colorMatrix(grayScaleMatrix)
private val invertFilter = ColorFilter.colorMatrix(invertMatrix)
private val grayScaleAndInvertFilter = ColorFilter.colorMatrix(grayScaleAndInvertMatrix)

@Composable
fun AdsPlaceholder(
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
) = SubcomposeAsyncImage(
    model = AdsPlaceholderFile,
    contentDescription = null,
    modifier = modifier,
    contentScale = contentScale,
) {
    val placeholderState by painter.state.collectAsState()
    if (placeholderState is AsyncImagePainter.State.Success) {
        SubcomposeAsyncImageContent()
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT),
            contentAlignment = Alignment.Center,
        ) {
            if (placeholderState is AsyncImagePainter.State.Error) {
                Text(text = stringResource(id = R.string.blocked_image))
            }
        }
    }
}
