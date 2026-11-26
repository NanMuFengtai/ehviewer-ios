package com.hippo.ehviewer.ui.reader

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.raise.ensure
import arrow.core.right
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.ui.util.Await
import com.ehviewer.core.ui.util.asyncInVM
import com.ehviewer.core.ui.util.rememberSystemUiController
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.unreachable
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.status
import com.hippo.ehviewer.gallery.unblock
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.gallery.useEhPageLoader
import com.hippo.ehviewer.jm.useJmPageLoader
import com.hippo.ehviewer.nhentai.useNhentaiPageLoader
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.theme.EhTheme
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.hasAds
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import eu.kanade.tachiyomi.ui.reader.PageIndicatorText
import eu.kanade.tachiyomi.ui.reader.ReaderAppBars
import eu.kanade.tachiyomi.ui.reader.ReaderContentOverlay
import eu.kanade.tachiyomi.ui.reader.ReaderPageSheetMeta
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import moe.tarsin.string
import okio.Path.Companion.toPath

@Serializable
sealed interface ReaderScreenArgs {
    @Serializable
    data class Gallery(val info: BaseGalleryInfo, val page: Int) : ReaderScreenArgs

    @Serializable
    data class Archive(val path: String) : ReaderScreenArgs

    /**
     * nhentai 的书：只需要 gallery id，页表由 [com.hippo.ehviewer.nhentai.NhentaiPageLoader] 自己拉。
     */
    @Serializable
    data class Nhentai(val id: Long, val page: Int = 0) : ReaderScreenArgs

    /**
     * 禁漫的一话：本子 id + 章节 id；页表和打乱参数由 [com.hippo.ehviewer.jm.JmPageLoader] 自己拉。
     */
    @Serializable
    data class Jm(val albumId: Long, val chapterId: Long, val page: Int = 0) : ReaderScreenArgs
}

@Composable
private fun Background(
    color: Color,
    content: @Composable () -> Unit,
) = Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
    EhTheme(useDarkTheme = color != Color.White, content = content)
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.ReaderScreen(args: ReaderScreenArgs, navigator: DestinationsNavigator) = Screen(navigator) {
    val bgColor by collectBackgroundColorAsState()
    val uiController = rememberSystemUiController()
    DisposableEffect(uiController) {
        val lightStatusBar = uiController.statusBarDarkContentEnabled
        uiController.statusBarDarkContentEnabled = bgColor == Color.White
        onDispose {
            uiController.statusBarDarkContentEnabled = lightStatusBar
        }
    }

    Await(
        block = asyncInVM(args) { alive ->
            suspendCancellableCoroutine { cont ->
                with(alive) {
                    launchIO {
                        catch {
                            usePageLoader(args) { loader ->
                                cont.resume(loader.right())
                                awaitCancellation()
                            }
                        }.let { left -> cont.resume(left) }
                    }
                }
            }
        }.value.run {
            { await() }
        },
        placeholder = {
            Background(bgColor) {
                CircularWavyProgressIndicator()
            }
        },
    ) { result ->
        when (result) {
            is Either.Left -> Background(bgColor) {
                Text(
                    text = result.value.displayString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            is Either.Right -> {
                val loader = result.value
                val info = (args as? ReaderScreenArgs.Gallery)?.info
                key(loader) {
                    ReaderScreen(loader, info)
                }
            }
        }
    }
}

@Composable
context(activity: MainActivity, _: SnackbarHostState, _: DialogState, _: CoroutineScope, _: DestinationsNavigator)
fun ReaderScreen(pageLoader: PageLoader, info: BaseGalleryInfo?) {
    LaunchedEffect(Unit) {
        val orientation = activity.requestedOrientation
        Settings.orientationMode.valueFlow()
            .onCompletion { activity.requestedOrientation = orientation }
            .collect { activity.setOrientation(it) }
    }
    LaunchedEffect(pageLoader) {
        with(Settings) {
            merge(cropBorder.changesFlow(), stripExtraneousAds.changesFlow()).collect {
                pageLoader.restart()
            }
        }
    }
    // 退出阅读器时保存进度（E 站写 Room，nhentai 写自己的历史）
    DisposableEffect(pageLoader) {
        onDispose { pageLoader.saveProgress() }
    }
    // 一进来就按 30 页一批铺预览图，翻到哪儿都有东西看
    LaunchedEffect(pageLoader) {
        pageLoader.startPreviewWarmup()
    }
    val webtoon = remember(info, pageLoader) {
        // Tags in database may or may not have the prefix "other:"
        pageLoader.defaultWebtoon || info?.simpleTags?.any { it.endsWith("webtoon") } == true
    }
    val showSeekbar by Settings.showReaderSeekbar.collectAsState()
    val readingMode by Settings.readingMode.collectAsState {
        when (val mode = ReadingModeType.fromPreference(it)) {
            ReadingModeType.DEFAULT -> if (webtoon) ReadingModeType.WEBTOON else ReadingModeType.RIGHT_TO_LEFT
            else -> mode
        }
    }
    val volumeKeysEnabled by Settings.readWithVolumeKeys.collectAsState()
    val volumeKeysInverted by Settings.readWithVolumeKeysInverted.collectAsState()
    val fullscreen by Settings.fullscreen.collectAsState()
    val cutoutShort by Settings.cutoutShort.collectAsState()
    val keepScreenOn by Settings.keepScreenOn.collectAsState()
    val uiController = rememberSystemUiController()
    DisposableEffect(uiController) {
        uiController.showTransientSystemBarsBySwipe = true
        onDispose {
            uiController.isSystemBarsVisible = true
            uiController.showTransientSystemBarsBySwipe = false
        }
    }
    val lazyListState = rememberLazyListState(LazyLayoutCacheWindow(SCROLL_FRACTION, SCROLL_FRACTION), pageLoader.startPage)
    val pagerState = rememberPagerState(pageLoader.startPage) { pageLoader.size }
    val syncState = rememberSliderPagerDoubleSyncState(lazyListState, pagerState, pageLoader)
    var appbarVisible by remember { mutableStateOf(false) }
    val isWebtoon by rememberUpdatedState(ReadingModeType.isWebtoon(readingMode))
    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier.keyEventHandler(
            volumeKeysEnabled = { volumeKeysEnabled && !appbarVisible },
            volumeKeysInverted = { volumeKeysInverted },
            movePrevious = { launch { if (isWebtoon) lazyListState.scrollUp() else pagerState.moveToPrevious() } },
            moveNext = { launch { if (isWebtoon) lazyListState.scrollDown() else pagerState.moveToNext() } },
        ).focusRequester(focusRequester).focusable().thenIf(keepScreenOn) { keepScreenOn() },
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        syncState.Sync(isWebtoon) {
            appbarVisible = false
            // nhentai 每翻一页就落一次进度，不依赖退出阅读器时才存
            if (pageLoader.autoSaveProgress) pageLoader.saveProgress()
        }
        val bgColor by collectBackgroundColorAsState()
        val isDarkTheme = isSystemInDarkTheme()
        LaunchedEffect(isDarkTheme) {
            snapshotFlow { appbarVisible }.collect {
                uiController.isSystemBarsVisible = it || !fullscreen
                uiController.statusBarDarkContentEnabled = if (it) !isDarkTheme else bgColor == Color.White
            }
        }
        var showNavigationOverlay by remember {
            val showOnStart = Settings.showNavigationOverlayNewUser.value || Settings.showNavigationOverlayOnStart.value
            Settings.showNavigationOverlayNewUser.value = false
            mutableStateOf(showOnStart)
        }
        val onSelectPage = { page: Page ->
            if (Settings.readerLongTapAction.value) {
                launch {
                    val blocked = page.status is PageStatus.Blocked
                    dialog { cont ->
                        fun dispose() {
                            // Material3 的 sheet 会在「用户 dismiss」和「收起动画结束」各回调一次，
                            // 不判重就会二次 resume 续体，直接抛 IllegalStateException 闪退
                            if (cont.isActive) cont.resume(Unit)
                        }
                        val state = rememberBottomSheetState(SheetValue.Hidden)
                        ModalBottomSheet(
                            onDismissRequest = { dispose() },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                            sheetState = state,
                            contentWindowInsets = { WindowInsets() },
                        ) {
                            ReaderPageSheetMeta(
                                retry = { pageLoader.retryPage(page.index) },
                                retryOrigin = { pageLoader.retryPage(page.index, true) },
                                share = { launchIO { with(pageLoader) { shareImage(page, info) } } },
                                copy = { launchIO { with(pageLoader) { copy(page) } } },
                                save = { launchIO { with(pageLoader) { save(page) } } },
                                saveTo = { launchIO { with(pageLoader) { saveTo(page) } } },
                                showAds = { page.unblock() }.takeIf { blocked },
                                dismiss = { launch { state.hide().also { dispose() } } },
                            )
                        }
                    }
                }
            }
        }
        EhTheme(useDarkTheme = bgColor != Color.White) {
            val insets = if (fullscreen) {
                if (cutoutShort) {
                    WindowInsets()
                } else {
                    WindowInsets.displayCutout
                }
            } else {
                WindowInsets.systemBars
            }
            GalleryPager(
                type = readingMode,
                pagerState = pagerState,
                lazyListState = lazyListState,
                pageLoader = pageLoader,
                showNavigationOverlay = showNavigationOverlay,
                onNavigationModeChange = { showNavigationOverlay = true },
                onSelectPage = onSelectPage,
                onMenuRegionClick = { appbarVisible = !appbarVisible },
                modifier = Modifier.background(bgColor).pointerInput(syncState) {
                    awaitEachGesture {
                        waitForUpOrCancellation()
                        syncState.reset()
                        showNavigationOverlay = false
                    }
                }.fillMaxSize().windowInsetsPadding(insets),
            )
        }
        val brightness by Settings.customBrightness.collectAsState()
        val brightnessValue by Settings.customBrightnessValue.collectAsState()
        val colorOverlayEnabled by Settings.colorFilter.collectAsState()
        val colorOverlay by Settings.colorFilterValue.collectAsState()
        val colorOverlayMode by Settings.colorFilterMode.collectAsState {
            when (it) {
                0 -> BlendMode.SrcOver
                1 -> BlendMode.Multiply
                2 -> BlendMode.Screen
                3 -> BlendMode.Overlay
                4 -> BlendMode.Lighten
                5 -> BlendMode.Darken
                else -> unreachable()
            }
        }
        ReaderContentOverlay(
            brightness = { brightnessValue }.takeIf { brightness && brightnessValue < 0 },
            color = { colorOverlay }.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayMode,
        )
        if (brightness) {
            LaunchedEffect(Unit) {
                Settings.customBrightnessValue.valueFlow().sample(100)
                    .onCompletion { activity.setCustomBrightnessValue(0) }
                    .collect { activity.setCustomBrightnessValue(it) }
            }
        }
        val showPageNumber by Settings.showPageNumber.collectAsState()
        if (showPageNumber && !appbarVisible) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                PageIndicatorText(
                    currentPage = syncState.sliderValue,
                    totalPages = pageLoader.size,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
        // 多话本：滚/翻到两头时，左右边缘浮出「上一話 / 下一話」，点一下就换话
        val atStart by remember(isWebtoon, pageLoader) {
            derivedStateOf {
                if (isWebtoon) !lazyListState.canScrollBackward else pagerState.currentPage == 0
            }
        }
        val atEnd by remember(isWebtoon, pageLoader) {
            derivedStateOf {
                if (isWebtoon) !lazyListState.canScrollForward else pagerState.currentPage == pageLoader.size - 1
            }
        }
        if (!appbarVisible) {
            if (pageLoader.hasPreviousChapter && atStart) {
                ChapterSwitchHint(
                    text = "上一話",
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    pageLoader.saveProgress()
                    pageLoader.previousChapterId()?.let(pageLoader::switchChapter)
                }
            }
            if (pageLoader.hasNextChapter && atEnd) {
                ChapterSwitchHint(
                    text = "下一話",
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    pageLoader.saveProgress()
                    pageLoader.nextChapterId()?.let(pageLoader::switchChapter)
                }
            }
        }
        ReaderAppBars(
            visible = appbarVisible,
            title = pageLoader.title,
            isRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT,
            showSeekBar = showSeekbar,
            currentPage = syncState.sliderValue,
            totalPages = pageLoader.size,
            onSliderValueChange = syncState::sliderScrollTo,
            onClickChapters = pageLoader.chapters.takeIf { it.isNotEmpty() }?.let {
                {
                    launch {
                        dialog { cont ->
                            fun dispose() {
                            // Material3 的 sheet 会在「用户 dismiss」和「收起动画结束」各回调一次，
                            // 不判重就会二次 resume 续体，直接抛 IllegalStateException 闪退
                            if (cont.isActive) cont.resume(Unit)
                        }
                            ModalBottomSheet(
                                onDismissRequest = { dispose() },
                                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                                dragHandle = null,
                                contentWindowInsets = { WindowInsets() },
                            ) {
                                ChapterListSheet(
                                    chapters = pageLoader.chapters,
                                    currentIndex = pageLoader.currentChapterIndex,
                                    onSelect = { chapterId ->
                                        dispose()
                                        launch {
                                            pageLoader.saveProgress()
                                            pageLoader.switchChapter(chapterId)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            },
            onClickSettings = {
                launch {
                    dialog { cont ->
                        fun dispose() {
                            // Material3 的 sheet 会在「用户 dismiss」和「收起动画结束」各回调一次，
                            // 不判重就会二次 resume 续体，直接抛 IllegalStateException 闪退
                            if (cont.isActive) cont.resume(Unit)
                        }
                        var isColorFilter by remember { mutableStateOf(false) }
                        val scrim by animateColorAsState(
                            targetValue = if (isColorFilter) Color.Transparent else BottomSheetDefaults.ScrimColor,
                            label = "ScrimColor",
                        )
                        ModalBottomSheet(
                            onDismissRequest = { dispose() },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                            // Yeah, I know color state should not be read here, but we have to do it...
                            scrimColor = scrim,
                            dragHandle = null,
                            contentWindowInsets = { WindowInsets() },
                        ) {
                            SettingsPager(isWebtoon = isWebtoon, modifier = Modifier.fillMaxSize()) { page ->
                                isColorFilter = page == 2
                                appbarVisible = !isColorFilter
                            }
                        }
                    }
                }
            },
        )
    }
}

context(_: Context, _: DialogState, nav: DestinationsNavigator)
suspend inline fun <T> usePageLoader(args: ReaderScreenArgs, crossinline block: suspend (PageLoader) -> T) = when (args) {
    is ReaderScreenArgs.Gallery -> {
        val info = args.info
        val page = args.page.takeUnless { it == -1 } ?: EhDB.getReadProgress(info.gid)
        val archive = DownloadManager.getDownloadInfo(info.gid)?.archiveFile
        if (archive != null) {
            useArchivePageLoader(archive, info, page, info.hasAds, { error("Managed Archive have password???") }, block)
        } else {
            useEhPageLoader(info, page, block)
        }
    }
    is ReaderScreenArgs.Archive -> useArchivePageLoader(
        args.path.toPath(),
        passwdProvider = { invalidator ->
            awaitInputText(
                title = string(R.string.archive_need_passwd),
                hint = string(R.string.archive_passwd),
                onUserDismiss = { nav.popBackStack() },
            ) { text ->
                ensure(text.isNotBlank()) { string(R.string.passwd_cannot_be_empty) }
                ensure(invalidator(text)) { string(R.string.passwd_wrong) }
            }
        },
        block = block,
    )
    is ReaderScreenArgs.Nhentai -> useNhentaiPageLoader(args.id, args.page, block)
    is ReaderScreenArgs.Jm -> useJmPageLoader(
        albumId = args.albumId,
        chapterId = args.chapterId,
        startPage = args.page,
        onSwitchChapter = { nextChapterId ->
            // 先把阅读器退出栈，再进下一话的首屏，栈里不会越堆越深
            nav.popBackStack()
            nav.navigate(
                ReaderScreenDestination(ReaderScreenArgs.Jm(args.albumId, nextChapterId, 0)),
            ) {
                launchSingleTop = true
            }
        },
        block = block,
    )
}

/** 首/末页时贴在屏幕边缘的半透明「上一話 / 下一話」，点一下切话。 */
@Composable
private fun BoxScope.ChapterSwitchHint(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(52.dp)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.toCharArray().joinToString("\n"),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** 底部「章節」按钮弹出来的章节表。 */
@Composable
private fun ChapterListSheet(
    chapters: List<PageLoader.ChapterEntry>,
    currentIndex: Int,
    onSelect: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "章節（共 ${chapters.size} 話）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
            items(chapters.size) { i ->
                val item = chapters[i]
                val selected = i == currentIndex
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun collectBackgroundColorAsState(): State<Color> {
    val grey = colorResource(com.hippo.ehviewer.R.color.reader_background_dark)
    val dark = isSystemInDarkTheme()
    return Settings.readerTheme.collectAsState { theme ->
        when (theme) {
            0 -> Color.White
            2 -> grey
            3 -> if (dark) grey else Color.White
            else -> Color.Black
        }
    }
}
