package com.hippo.ehviewer.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.hippo.ehviewer.ios.ui.DetailScreen
import com.hippo.ehviewer.ios.ui.HomeScreen
import com.hippo.ehviewer.ios.ui.PageRef
import com.hippo.ehviewer.ios.ui.ReaderScreen

/** 站点主题色，和 Android 侧保持一致：E 琥珀 / N 粉 / 禁 紫。 */
enum class SiteTheme(val accent: Color) {
    EHENTAI(Color(0xFFE39A3B)),
    NHENTAI(Color(0xFFE0678A)),
    JMCOMIC(Color(0xFF7D6BD6)),
}

sealed interface Route {
    data object Home : Route
    data class Detail(val id: Long, val title: String) : Route
    data class Reader(val id: Long, val title: String, val pages: List<PageRef>) : Route
}

@Composable
fun App() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppNavigation()
        }
    }
}

@Composable
private fun AppNavigation() {
    val backStack = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateListOf<Route>(Route.Home)
    }
    val current = backStack.last()

    fun push(route: Route) {
        backStack.add(route)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (val route = current) {
            is Route.Home -> HomeScreen(
                onOpenDetail = { id, title -> push(Route.Detail(id, title)) },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )

            is Route.Detail -> DetailScreen(
                galleryId = route.id,
                fallbackTitle = route.title,
                onBack = { pop() },
                onRead = { id, title, pages -> push(Route.Reader(id, title, pages)) },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )

            is Route.Reader -> ReaderScreen(
                galleryId = route.id,
                title = route.title,
                pages = route.pages,
                onBack = { pop() },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}
