package com.hippo.ehviewer.ios.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

/**
 * iOS 的 Ktor 引擎。走 NSURLSession（Darwin），和系统的网络栈一致，
 * ATS 设置沿用 Info.plist 里的 NSAllowsArbitraryLoads。
 */
internal actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    // 不自动装 ContentNegotiation，JSON 全都走 kotlinx.serialization 手动解码
    install(HttpTimeout) {
        requestTimeoutMillis = 25_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 25_000
    }
    followRedirects = true
    expectSuccess = false
}
