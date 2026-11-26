package com.ehviewer.core.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS 上没有「隐藏状态栏/导航栏」这种可随时改的系统 UI 控制，
 * 阅读器全屏靠 UIViewController 的 prefersStatusBarHidden，这里只保留状态。
 */
@Composable
actual fun rememberSystemUiController(): SystemUiController = remember { IosSystemUiController() }

private class IosSystemUiController : SystemUiController {
    override var showTransientSystemBarsBySwipe: Boolean = true
    override var isStatusBarVisible: Boolean = true
    override var isNavigationBarVisible: Boolean = true
    override var statusBarDarkContentEnabled: Boolean = false
}
