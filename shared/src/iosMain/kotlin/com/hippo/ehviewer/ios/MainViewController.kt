@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.hippo.ehviewer.ios

import androidx.compose.ui.window.ComposeUIViewController
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

/**
 * iOS 端的诊断日志。和 Swift 侧共用 Documents 目录，能从「文件」App 取出来。
 *
 * 走 POSIX fopen/fputs 而不是 Foundation：崩溃路径上不能碰可能已经坏掉的运行时。
 * Kotlin/Native 的 posix 绑定会把 const char* 映射成 String，直接传就行。
 */
private fun diagPath(): String {
    val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val dir = dirs.firstOrNull() as? String ?: NSTemporaryDirectory()
    return "$dir/ios-diag.log"
}

private fun diag(text: String) {
    val file = fopen(diagPath(), "a") ?: return
    fputs(text, file)
    fputs("\n", file)
    fclose(file)
}

private fun Throwable.traceOrFallback(): String = try {
    stackTraceToString()
} catch (t: Throwable) {
    "<取不到堆栈: $t>\n$this"
}

/**
 * Swift 侧通过这个函数拿到 Compose 的视图控制器。
 *
 * 外面套了一层诊断：真机上启动即 SIGABRT，只拿到信号号看不到是哪一层挂的。
 * Kotlin/Native 的未捕获异常在这里能被钩住，中止前的堆栈会落到 ios-diag.log。
 */
fun MainViewController(): UIViewController {
    diag("[kotlin] MainViewController() 进入")

    setUnhandledExceptionHook { throwable ->
        diag("=== Kotlin 未捕获异常 ===\n" + throwable.toString() + "\n" + throwable.traceOrFallback())
    }
    diag("[kotlin] 未捕获异常钩子已安装")

    val controller = try {
        ComposeUIViewController { App() }
    } catch (t: Throwable) {
        diag("=== ComposeUIViewController 创建抛异常 ===\n" + t.toString() + "\n" + t.traceOrFallback())
        throw t
    }

    diag("[kotlin] ComposeUIViewController 创建完成")
    return controller
}
