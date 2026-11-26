package com.hippo.ehviewer.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.hippo.ehviewer.ios.generated.resources.Res
import com.hippo.ehviewer.ios.generated.resources.sadpanda_low_poly
import com.hippo.ehviewer.ios.source.Site
import com.hippo.ehviewer.ios.store.Account
import com.hippo.ehviewer.ios.store.Prefs
import com.hippo.ehviewer.ios.ui.DetailScreen
import com.hippo.ehviewer.ios.ui.GalleryListPage
import com.hippo.ehviewer.ios.ui.HistoryScreen
import com.hippo.ehviewer.ios.ui.ListMode
import com.hippo.ehviewer.ios.ui.LoginScreen
import com.hippo.ehviewer.ios.ui.PageRef
import com.hippo.ehviewer.ios.ui.ReaderScreen
import com.hippo.ehviewer.ios.ui.SettingsScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

sealed interface Screen {
    data object Main : Screen
    data class Detail(val site: Site, val id: Long, val token: String, val title: String) : Screen
    data class Reader(
        val site: Site,
        val id: Long,
        val title: String,
        val pages: List<PageRef>,
    ) : Screen

    data object Login : Screen
    data object History : Screen
    data object Settings : Screen
}

private data class NavEntry(val label: String, val icon: ImageVector)

/** 每个站点的抽屉导航项，和 Android 的 navItemsOf 对齐。 */
private fun navItemsOf(site: Site): List<NavEntry> = when (site) {
    Site.EHENTAI -> listOf(
        NavEntry("主页", Icons.Default.Home),
        NavEntry("收藏", Icons.Default.Favorite),
        NavEntry("历史", Icons.Default.History),
        NavEntry("设置", Icons.Default.Settings),
    )

    Site.NHENTAI -> listOf(
        NavEntry("主页", Icons.Default.Home),
        NavEntry("热门", Icons.Default.Whatshot),
        NavEntry("收藏", Icons.Default.Favorite),
        NavEntry("历史", Icons.Default.History),
        NavEntry("设置", Icons.Default.Settings),
    )

    Site.JMCOMIC -> listOf(
        NavEntry("主页", Icons.Default.Home),
        NavEntry("收藏", Icons.Default.Favorite),
        NavEntry("历史", Icons.Default.History),
        NavEntry("设置", Icons.Default.Settings),
    )
}

@Composable
fun App() {
    Account.load()

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }

    var dark by remember { mutableStateOf(Prefs.getString("theme.dark") == "1") }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppRoot(dark) { value ->
                dark = value
                Prefs.putString("theme.dark", if (value) "1" else "0")
            }
        }
    }
}

@Composable
private fun AppRoot(darkTheme: Boolean, onDarkThemeChange: (Boolean) -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var site by remember { mutableStateOf(Site.EHENTAI) }
    var navIndex by remember { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun goMain() {
        screen = Screen.Main
    }

    when (val s = screen) {
        is Screen.Detail -> DetailScreen(
            site = s.site,
            galleryId = s.id,
            token = s.token,
            fallbackTitle = s.title,
            onBack = { goMain() },
            onRead = { si, id, title, pages -> screen = Screen.Reader(si, id, title, pages) },
            onTagClick = { tag ->
                screen = Screen.Main
                navIndex = 0
                searchRequest = tag
                searchSeq += 1
            },
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )

        is Screen.Reader -> ReaderScreen(
            site = s.site,
            galleryId = s.id,
            title = s.title,
            pages = s.pages,
            onBack = { screen = Screen.Main },
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )

        is Screen.Login -> LoginScreen(
            onBack = { screen = Screen.Settings },
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )

        is Screen.History -> HistoryScreen(
            onBack = { screen = Screen.Main },
            onOpen = { si, id, token, title -> screen = Screen.Detail(si, id, token, title) },
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )

        is Screen.Settings -> SettingsScreen(
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
            onOpenLogin = { screen = Screen.Login },
            onOpenHistory = { screen = Screen.History },
            onBack = { screen = Screen.Main },
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )

        is Screen.Main -> {
            // 站点切换时把导航归位，避免停在「热门」这种新站点没有的项上
            LaunchedEffect(site) {
                if (navIndex >= navItemsOf(site).size) navIndex = 0
            }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerState = drawerState) {
                        DrawerPanel(
                            site = site,
                            navIndex = navIndex,
                            onSwitchSite = { target ->
                                site = target
                                navIndex = 0
                                scope.launch { drawerState.close() }
                            },
                            onNav = { index, label ->
                                scope.launch { drawerState.close() }
                                when (label) {
                                    "历史" -> screen = Screen.History
                                    "设置" -> screen = Screen.Settings
                                    else -> navIndex = index
                                }
                            },
                        )
                    }
                },
            ) {
                val entries = navItemsOf(site)
                val label = entries.getOrNull(navIndex)?.label ?: "主页"
                val mode = when (label) {
                    "收藏" -> ListMode.FAVORITES
                    "热门" -> ListMode.POPULAR
                    else -> ListMode.LATEST
                }
                GalleryListPage(
                    site = site,
                    mode = mode,
                    title = when {
                        label == "主页" -> "很太之家"
                        else -> label
                    },
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenDetail = { si, id, token, title ->
                        screen = Screen.Detail(si, id, token, title)
                    },
                    searchSeq = searchSeq,
                    searchText = searchRequest,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
        }
    }
}

/**
 * 抽屉面板：顶部熊猫插画 + 站点胶囊 + 导航项，背景是站点色的纵向渐变。
 * 和 Android 的 ModalDrawerSheet 内容一一对应。
 */
@Composable
private fun DrawerPanel(
    site: Site,
    navIndex: Int,
    onSwitchSite: (Site) -> Unit,
    onNav: (Int, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    0f to site.accent.copy(alpha = 0.26f),
                    0.42f to site.accent.copy(alpha = 0.10f),
                    1f to Color.Transparent,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(Res.drawable.sadpanda_low_poly),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

        SiteCapsule(
            site = site,
            onSelect = onSwitchSite,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )

        navItemsOf(site).forEachIndexed { index, entry ->
            NavigationDrawerItem(
                label = { Text(entry.label) },
                selected = index == navIndex,
                onClick = { onNav(index, entry.label) },
                icon = { Icon(entry.icon, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** 站点胶囊：E / N / 禁，选中项用站点色填一个圆角块滑过去。 */
@Composable
private fun SiteCapsule(
    site: Site,
    onSelect: (Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = Site.entries
    val selected = options.indexOf(site).coerceAtLeast(0)

    BoxWithConstraints(modifier = modifier.height(44.dp)) {
        val segment = maxWidth / options.size
        // 指示块 + 底色是固定的一层，文字压在上面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        )
        Box(
            modifier = Modifier
                .offset(x = segment * selected)
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(site.accent),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, s ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (s) {
                            Site.EHENTAI -> "E"
                            Site.NHENTAI -> "N"
                            Site.JMCOMIC -> "禁"
                        },
                        fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == selected) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// 搜索请求通过这两个全局状态从详情页传回列表页
private var searchSeq by mutableStateOf(0)
private var searchRequest by mutableStateOf("")
