package com.hippo.ehviewer.ios

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.hippo.ehviewer.ios.source.Site
import com.hippo.ehviewer.ios.store.Account
import com.hippo.ehviewer.ios.store.Prefs
import com.hippo.ehviewer.ios.ui.DetailScreen
import com.hippo.ehviewer.ios.ui.HistoryScreen
import com.hippo.ehviewer.ios.ui.HomeScreen
import com.hippo.ehviewer.ios.ui.LoginScreen
import com.hippo.ehviewer.ios.ui.PageRef
import com.hippo.ehviewer.ios.ui.ReaderScreen
import com.hippo.ehviewer.ios.ui.SettingsScreen

sealed interface Route {
    data object Home : Route
    data class Detail(val site: Site, val id: Long, val token: String, val title: String) : Route
    data class Reader(
        val site: Site,
        val id: Long,
        val title: String,
        val pages: List<PageRef>,
    ) : Route

    data object Login : Route
    data object Settings : Route
    data object History : Route
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
        Surface(modifier = Modifier.fillMaxSize()) {
            AppNavigation(dark) { value ->
                dark = value
                Prefs.putString("theme.dark", if (value) "1" else "0")
            }
        }
    }
}

@Composable
private fun AppNavigation(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
) {
    val backStack = remember { mutableStateListOf<Route>(Route.Home) }
    var site by remember { mutableStateOf(Site.EHENTAI) }
    var searchSeq by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }

    fun push(route: Route) {
        backStack.add(route)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (val route = backStack.last()) {
            is Route.Home -> HomeScreen(
                site = site,
                onSwitchSite = { site = it },
                onOpenDetail = { s, id, token, title -> push(Route.Detail(s, id, token, title)) },
                onOpenSettings = { push(Route.Settings) },
                searchSeq = searchSeq,
                searchText = searchText,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )

            is Route.Detail -> DetailScreen(
                site = route.site,
                galleryId = route.id,
                token = route.token,
                fallbackTitle = route.title,
                onBack = { pop() },
                onRead = { s, id, title, pages -> push(Route.Reader(s, id, title, pages)) },
                onTagClick = { tag ->
                    searchText = tag
                    searchSeq += 1
                    while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )

            is Route.Reader -> ReaderScreen(
                site = route.site,
                galleryId = route.id,
                title = route.title,
                pages = route.pages,
                onBack = { pop() },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )

            is Route.Login -> LoginScreen(
                onBack = { pop() },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )

            is Route.Settings -> SettingsScreen(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                onOpenLogin = { push(Route.Login) },
                onOpenHistory = { push(Route.History) },
                onBack = { pop() },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )

            is Route.History -> HistoryScreen(
                onBack = { pop() },
                onOpen = { s, id, token, title -> push(Route.Detail(s, id, token, title)) },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}
