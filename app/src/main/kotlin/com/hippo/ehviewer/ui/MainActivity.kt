/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ContentResolver.SCHEME_CONTENT
import android.content.ContentResolver.SCHEME_FILE
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberDrawerState2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.compose.rememberNavController
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.LabeledCheckbox
import com.ehviewer.core.ui.component.LocalSideSheetState
import com.ehviewer.core.ui.component.MutableSideSheet
import com.ehviewer.core.ui.icons.EhIcons
import com.ehviewer.core.ui.icons.filled.Subscriptions
import com.ehviewer.core.ui.util.LocalSnackBarFabPadding
import com.ehviewer.core.ui.util.LocalWindowSizeClass
import com.ehviewer.core.util.isAtLeastQ
import com.ehviewer.core.util.isAtLeastR
import com.ehviewer.core.util.isAtLeastS
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhApplication.Companion.initialized
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.Sites
import com.hippo.ehviewer.ui.main.SITE_SWITCH_ANIMATION_MS
import com.hippo.ehviewer.ui.main.SiteAccent
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.destinations.DownloadScreenDestination
import com.hippo.ehviewer.ui.destinations.DownloadsScreenDestination
import com.hippo.ehviewer.ui.destinations.FavouritesScreenDestination
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.HomePageScreenDestination
import com.hippo.ehviewer.ui.destinations.ProgressScreenDestination
import com.hippo.ehviewer.ui.destinations.SettingsScreenDestination
import com.hippo.ehviewer.ui.destinations.SignInScreenDestination
import com.hippo.ehviewer.ui.destinations.SubscriptionScreenDestination
import com.hippo.ehviewer.ui.destinations.ToplistScreenDestination
import com.hippo.ehviewer.ui.destinations.WhatshotScreenDestination
import com.hippo.ehviewer.ui.destinations.NhentaiListScreenDestination
import com.hippo.ehviewer.ui.destinations.JmListScreenDestination
import com.hippo.ehviewer.ui.destinations.JmFavoriteScreenDestination
import com.hippo.ehviewer.ui.destinations.JmHistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.NhentaiTagsScreenDestination
import com.hippo.ehviewer.ui.destinations.NhentaiHistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.NhentaiDownloadsScreenDestination
import com.hippo.ehviewer.nhentai.NhentaiApi
import com.hippo.ehviewer.ui.main.SiteSwitcher
import com.hippo.ehviewer.ui.screen.nhentai.NhentaiListArgs
import com.hippo.ehviewer.ui.screen.asDst
import com.hippo.ehviewer.ui.screen.asDstWith
import com.hippo.ehviewer.ui.screen.navWithUrl
import com.hippo.ehviewer.ui.settings.showNewVersion
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.updater.AppUpdater
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.addTextToClipboard
import com.hippo.ehviewer.util.calculateFraction
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.getParcelableExtraCompat
import com.hippo.ehviewer.util.getUrlFromClipboard
import com.hippo.ehviewer.util.sha1
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.spec.Direction
import com.ramcosta.composedestinations.utils.currentDestinationAsState
import com.ramcosta.composedestinations.utils.rememberDestinationsNavigator
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.tarsin.coroutines.runSuspendCatching
import splitties.systemservices.clipboardManager
import splitties.systemservices.connectivityManager

private data class DrawerEntry(
    val direction: Direction,
    val label: Int,
    val icon: ImageVector,
)

private val ehNavItems = arrayOf(
    DrawerEntry(HomePageScreenDestination, R.string.homepage, Icons.Default.Home),
    DrawerEntry(SubscriptionScreenDestination, R.string.subscription, EhIcons.Default.Subscriptions),
    DrawerEntry(WhatshotScreenDestination, R.string.whats_hot, Icons.Default.Whatshot),
    DrawerEntry(ToplistScreenDestination, R.string.toplist, Icons.Default.FormatListNumbered),
    DrawerEntry(FavouritesScreenDestination, R.string.favourite, Icons.Default.Favorite),
    DrawerEntry(HistoryScreenDestination, R.string.history, Icons.Default.History),
    DrawerEntry(DownloadsScreenDestination, R.string.downloads, Icons.Default.Download),
    DrawerEntry(SettingsScreenDestination, R.string.settings, Icons.Default.Settings),
)

private val nhentaiNavItems = arrayOf(
    DrawerEntry(
        NhentaiListScreenDestination(NhentaiListArgs(NhentaiApi.MODE_LATEST)),
        R.string.nhentai_home,
        Icons.Default.Home,
    ),
    DrawerEntry(
        NhentaiListScreenDestination(NhentaiListArgs(NhentaiApi.MODE_POPULAR)),
        R.string.nhentai_popular,
        Icons.Default.Whatshot,
    ),
    DrawerEntry(NhentaiTagsScreenDestination, R.string.nhentai_tags, Icons.AutoMirrored.Default.Label),
    DrawerEntry(
        NhentaiListScreenDestination(NhentaiListArgs(NhentaiApi.MODE_FAVORITES)),
        R.string.nhentai_favorites,
        Icons.Default.Favorite,
    ),
    DrawerEntry(NhentaiHistoryScreenDestination, R.string.history, Icons.Default.History),
    DrawerEntry(NhentaiDownloadsScreenDestination, R.string.downloads, Icons.Default.Download),
    DrawerEntry(SettingsScreenDestination, R.string.settings, Icons.Default.Settings),
)

private val jmNavItems = arrayOf(
    DrawerEntry(JmListScreenDestination(), R.string.jm_site, Icons.Default.Home),
    DrawerEntry(JmFavoriteScreenDestination, R.string.jm_favorites, Icons.Default.Favorite),
    DrawerEntry(JmHistoryScreenDestination, R.string.history, Icons.Default.History),
    DrawerEntry(SettingsScreenDestination, R.string.settings, Icons.Default.Settings),
)

private fun navItemsOf(site: Int) = when {
    Sites.isNhentai(site) -> nhentaiNavItems
    Sites.isJm(site) -> jmNavItems
    else -> ehNavItems
}

private fun defaultDirectionOf(site: Int, launchPage: Int): Direction = when {
    Sites.isNhentai(site) -> nhentaiNavItems[0].direction
    Sites.isJm(site) -> jmNavItems[0].direction
    else -> ehNavItems[launchPage.coerceIn(0, ehNavItems.size - 1)].direction
}

class MainActivity : AppCompatActivity() {

    private var shareUrl: String? = null

    @Composable
    fun ProvideAssistContent(url: String) {
        val urlState by rememberUpdatedState(url)
        DisposableEffect(urlState) {
            shareUrl = urlState
            onDispose {
                shareUrl = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentChannel.trySend(intent)
    }

    private val tipFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val intentChannel = Channel<Intent>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val intentFlow = intentChannel.receiveAsFlow()

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !initialized }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // https://issuetracker.google.com/204791558
        // Fix system bars insets still exist in fullscreen mode on API < 30
        @Suppress("DEPRECATION")
        if (!isAtLeastR) {
            with(window.decorView) {
                systemUiVisibility = systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_STABLE.inv()
            }
        }
        setMD3Content {
            val navDrawerState = rememberDrawerState(DrawerValue.Closed)
            val sideSheetState = rememberDrawerState2(DrawerValue.Closed)
            val snackbarState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val navController = rememberNavController()
            val navigator = navController.rememberDestinationsNavigator()
            fun closeDrawer(callback: () -> Unit = {}) = scope.launch {
                navDrawerState.close()
                callback()
            }

            suspend fun DialogState.checkDownloadLocation() {
                val valid = withIOContext { runCatching { downloadLocation.isDirectory }.getOrDefault(false) }
                if (!valid) {
                    awaitConfirmationOrCancel(
                        confirmText = R.string.open_settings,
                        title = R.string.waring,
                        showCancelButton = false,
                    ) {
                        Text(
                            text = stringResource(id = R.string.invalid_download_location),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    navigator.navigate(DownloadScreenDestination)
                }
            }

            val hasNetwork = remember { connectivityManager.activeNetwork != null }
            if (!AppConfig.isBenchmark) {
                val noNetwork = stringResource(R.string.no_network)
                LaunchedEffect(Unit) {
                    runCatching { checkDownloadLocation() }
                    runCatching { checkAppLinkVerify() }
                    if (hasNetwork) {
                        runSuspendCatching {
                            withIOContext {
                                AppUpdater.checkForUpdate()?.let { showNewVersion(it) }
                            }
                        }.onFailure {
                            snackbarState.showSnackbar(getString(R.string.update_failed, it.displayString()))
                        }
                    } else {
                        snackbarState.showSnackbar(noNetwork)
                    }
                }
            }

            LaunchedEffect(Unit) {
                Settings.enabledSecurity.valueFlow().collect {
                    window.setSecureScreen(it)
                }
            }
            if (isAuthenticationSupported()) {
                SecurityScreen(onError = { moveTaskToBack(true) }, modifier = Modifier.zIndex(1f))
            }

            val cannotParse = stringResource(R.string.error_cannot_parse_the_url)
            LaunchedEffect(Unit) {
                intentFlow.collect { intent ->
                    when (intent.action) {
                        Intent.ACTION_VIEW -> with(navigator) {
                            val uri = intent.data ?: return@collect
                            when (uri.scheme) {
                                SCHEME_FILE -> navToReader(uri.path!!)
                                SCHEME_CONTENT -> navToReader(uri.toString())
                                else -> {
                                    val url = uri.toString()
                                    if (!navWithUrl(url)) {
                                        val new = awaitInputText(initial = url, title = cannotParse)
                                        addTextToClipboard(new)
                                    }
                                }
                            }
                        }
                        Intent.ACTION_SEND -> with(navigator) {
                            val type = intent.type
                            if ("text/plain" == type) {
                                val keyword = intent.getStringExtra(Intent.EXTRA_TEXT)
                                if (keyword != null && !navWithUrl(keyword)) {
                                    navigate(ListUrlBuilder(keyword = keyword).asDst())
                                }
                            } else if (type != null && type.startsWith("image/")) {
                                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                                if (null != uri) {
                                    val hash = withIOContext { uri.toOkioPath().sha1() }
                                    navigate(
                                        ListUrlBuilder(
                                            mode = ListUrlBuilder.MODE_IMAGE_SEARCH,
                                            hash = hash,
                                        ).asDst(),
                                    )
                                }
                            }
                        }
                        DownloadService.ACTION_START_DOWNLOADSCENE -> {
                            val args = intent.getBundleExtra(DownloadService.ACTION_START_DOWNLOADSCENE_ARGS)!!
                            if (args.getString(DownloadService.KEY_ACTION) == DownloadService.ACTION_CLEAR_DOWNLOAD_SERVICE) {
                                DownloadService.clear()
                            }
                            navigator.navigate(DownloadsScreenDestination)
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                tipFlow.collectLatest {
                    snackbarState.showSnackbar(it)
                }
            }
            val warning = stringResource(R.string.metered_network_warning)
            val settings = stringResource(R.string.settings)
            val checkMeteredNetwork by Settings.meteredNetworkWarning.collectAsState()
            if (checkMeteredNetwork) {
                LaunchedEffect(Unit) {
                    if (connectivityManager.isActiveNetworkMetered) {
                        if (isAtLeastQ) {
                            val ret = snackbarState.showSnackbar(warning, settings, true)
                            if (ret == SnackbarResult.ActionPerformed) {
                                val panelIntent = Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                                startActivity(panelIntent)
                            }
                        } else {
                            snackbarState.showSnackbar(warning)
                        }
                    }
                }
            }
            val snackMessage = stringResource(R.string.clipboard_gallery_url_snack_message)
            val snackAction = stringResource(R.string.clipboard_gallery_url_snack_action)
            LifecycleResumeEffect(scope) {
                val job = scope.launch {
                    delay(300)
                    val text = clipboardManager.getUrlFromClipboard(applicationContext)
                    val hashCode = text?.hashCode() ?: 0
                    if (text != null && hashCode != 0 && Settings.clipboardTextHashCode != hashCode) {
                        val result1 = GalleryDetailUrlParser.parse(text, false)
                        var launch: (() -> Unit)? = null
                        if (result1 != null) {
                            launch = { navigator.navigate(result1.gid asDstWith result1.token) }
                        }
                        val result2 = GalleryPageUrlParser.parse(text, false)
                        if (result2 != null) {
                            launch = { navigator.navigate(ProgressScreenDestination(result2.gid, result2.pToken, result2.page)) }
                        }
                        launch?.let {
                            val ret = snackbarState.showSnackbar(snackMessage, snackAction, true)
                            if (ret == SnackbarResult.ActionPerformed) it()
                        }
                    }
                    Settings.clipboardTextHashCode = hashCode
                }
                onPauseOrDispose { job.cancel() }
            }
            val currentDestination by navController.currentDestinationAsState()
            val drawerHandle = remember { mutableStateListOf<Long>() }
            var snackbarFabPadding by remember { mutableStateOf(0.dp) }
            val drawerEnabled = drawerHandle.isNotEmpty()
            val density = LocalDensity.current
            val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
            val adaptiveInfo = currentWindowAdaptiveInfoV2()
            val needSignIn by Settings.needSignIn.collectAsState()
            val launchPage by Settings.launchPage.collectAsState()
            val site by Settings.contentSource.collectAsState()
            // 站点切换时先播完胶囊动画再收抽屉，连点就把上一次的收尾取消掉
            var switchSiteJob by remember { mutableStateOf<Job?>(null) }
            // 胶囊和底色先动起来，真正的换源等动画走完再写进设置，否则 DataStore 的写入延迟会让动画看起来是卡的
            var visualSite by remember { mutableIntStateOf(site) }
            LaunchedEffect(site) { if (visualSite != site) visualSite = site }
            // 每次拉开抽屉都跟真实设置对齐，避免上次切换没写完时胶囊和列表对不上
            LaunchedEffect(navDrawerState.isOpen) {
                if (navDrawerState.isOpen) visualSite = site
            }
            CompositionLocalProvider(
                LocalNavDrawerState provides navDrawerState,
                LocalSideSheetState provides sideSheetState,
                LocalDrawerHandle provides drawerHandle,
                LocalSnackBarHostState provides snackbarState,
                LocalSnackBarFabPadding provides animateDpAsState(snackbarFabPadding, label = "SnackbarFabPadding"),
                LocalWindowSizeClass provides adaptiveInfo.windowSizeClass,
            ) {
                Scaffold(
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarState,
                            modifier = Modifier.onGloballyPositioned {
                                with(density) {
                                    snackbarFabPadding = it.size.height.toDp()
                                }
                            },
                        )
                    },
                ) {
                    var minOffset by remember {
                        mutableFloatStateOf(-with(density) { DrawerDefaults.MaximumDrawerWidth.toPx() })
                    }
                    ModalNavigationDrawer(
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerState = navDrawerState,
                                modifier = Modifier.widthIn(max = (windowWidth - 56.dp).coerceAtLeast(0.dp))
                                    .onSizeChanged { minOffset = -it.width.toFloat() },
                                windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Start),
                            ) {
                                val scrollState = rememberScrollState()
                                val drawerAccent by animateColorAsState(
                                    targetValue = SiteAccent.of(visualSite),
                                    animationSpec = tween(durationMillis = 380),
                                    label = "drawerAccent",
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .background(
                                            Brush.verticalGradient(
                                                0f to drawerAccent.copy(alpha = 0.26f),
                                                0.42f to drawerAccent.copy(alpha = 0.10f),
                                                1f to Color.Transparent,
                                            ),
                                        ),
                                ) {
                                Column(
                                    modifier = Modifier.verticalScroll(scrollState)
                                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
                                ) {
                                    Image(
                                        painter = painterResource(id = com.hippo.ehviewer.R.drawable.sadpanda_low_poly),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        contentScale = ContentScale.FillWidth,
                                    )
                                    SiteSwitcher(
                                        site = visualSite,
                                        onSwitch = { target ->
                                            if (target != site) {
                                                // 只先动 visuals：胶囊滑过去、底色渐变，设置本身等动画结束再写
                                                visualSite = target
                                                switchSiteJob?.cancel()
                                                switchSiteJob = scope.launch {
                                                    delay(SITE_SWITCH_ANIMATION_MS)
                                                    Settings.contentSource.value = target
                                                    navigator.navigate(navItemsOf(target)[0].direction)
                                                    // 给抽屉里的导航项留一点切换时间，再整块收起
                                                    delay(240)
                                                    navDrawerState.close()
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 8.dp),
                                    )
                                    // 直接按视觉站点渲染：不做交叉淡化，避免新旧两组列表半透明叠在一起发白
                                    Column {
                                        navItemsOf(visualSite).forEach { entry ->
                                            NavigationDrawerItem(
                                                label = {
                                                    Text(text = stringResource(id = entry.label))
                                                },
                                                selected = currentDestination == entry.direction,
                                                onClick = {
                                                    navigator.navigate(entry.direction)
                                                    closeDrawer()
                                                },
                                                modifier = Modifier.padding(horizontal = 12.dp),
                                                icon = {
                                                    Icon(imageVector = entry.icon, contentDescription = null)
                                                },
                                            )
                                        }
                                    }
                                }
                                }
                            }
                        },
                        drawerState = navDrawerState,
                        gesturesEnabled = drawerEnabled && sideSheetState.isClosed || navDrawerState.isOpen,
                    ) {
                        val radius by remember {
                            snapshotFlow {
                                val step = calculateFraction(minOffset, 0f, navDrawerState.currentOffset)
                                with(density) { lerp(0, 10, step).dp.toPx() }
                            }
                        }.collectAsState(0f)
                        MutableSideSheet(
                            drawerState = sideSheetState,
                            modifier = Modifier.graphicsLayer {
                                if (radius != 0f) {
                                    renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                                    shape = RectangleShape
                                    clip = true
                                }
                            },
                            enabled = drawerEnabled,
                        ) {
                            SharedTransitionLayout {
                                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                                    val start = when {
                                        needSignIn -> SignInScreenDestination
                                        !hasNetwork -> DownloadsScreenDestination
                                        else -> defaultDirectionOf(site, launchPage)
                                    }
                                    DestinationsNavHost(
                                        navGraph = NavGraphs.root,
                                        start = start,
                                        defaultTransitions = rememberEhNavAnim(),
                                        navController = navController,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            if (intent.action != Intent.ACTION_MAIN) {
                onNewIntent(intent)
            }
        }
    }

    private suspend fun DialogState.checkAppLinkVerify() {
        if (isAtLeastS && !Settings.appLinkVerifyTip) {
            val manager = getSystemService(DomainVerificationManager::class.java)
            val packageName = packageName
            val userState = manager.getDomainVerificationUserState(packageName) ?: return
            val hasUnverified = userState.hostToStateMap.values.any { it == DOMAIN_STATE_NONE }
            if (hasUnverified) {
                var checked by mutableStateOf(false)
                awaitConfirmationOrCancel(
                    confirmText = R.string.open_settings,
                    title = R.string.app_link_not_verified_title,
                    onCancelButtonClick = {
                        if (checked) Settings.appLinkVerifyTip = true
                    },
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.app_link_not_verified_message),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LabeledCheckbox(
                            modifier = Modifier.fillMaxWidth(),
                            checked = checked,
                            onCheckedChange = { checked = it },
                            label = stringResource(id = R.string.dont_show_again),
                            indication = null,
                        )
                    }
                }
                if (checked) {
                    Settings.appLinkVerifyTip = true
                }
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                        "package:$packageName".toUri(),
                    )
                    startActivity(intent)
                } catch (_: Throwable) {
                    val intent = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:$packageName".toUri(),
                    )
                    startActivity(intent)
                }
            }
        }
    }

    fun showTip(message: String, useToast: Boolean = false) {
        if (useToast || !tipFlow.tryEmit(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent?) {
        super.onProvideAssistContent(outContent)
        shareUrl?.let { outContent?.webUri = it.toUri() }
    }
}

val LocalNavDrawerState = compositionLocalOf<DrawerState> { error("CompositionLocal LocalNavDrawerState not present!") }

val LocalDrawerHandle = compositionLocalOf<SnapshotStateList<Long>> { error("CompositionLocal LocalDrawerHandle not present!") }
val LocalSnackBarHostState = compositionLocalOf<SnackbarHostState> { error("CompositionLocal LocalSnackBarHostState not present!") }
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope> { error("CompositionLocal LocalSharedTransitionScope not present!") }

@Composable
fun DrawerHandle(enabled: Boolean) {
    if (enabled) {
        val current = currentCompositeKeyHashCode
        val handle = LocalDrawerHandle.current
        DisposableEffect(current) {
            handle.add(current)
            onDispose {
                handle.remove(current)
            }
        }
    }
}
