package com.hippo.ehviewer.ios

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Swift 侧通过这个函数拿到 Compose 的视图控制器。 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
