package com.ehviewer.core.network

import io.ktor.http.Cookie
import io.ktor.http.Url
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL

/**
 * iOS 的 Cookie 统一交给系统的 NSHTTPCookieStorage 管。
 *
 * 好处是以后用 WKWebView 做网页登录时，WebView 登录完的 Cookie 这边能直接读到，
 * 不用在两边各存一份。
 */
class IosCookieManager : CookieManager {
    private val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage

    override fun getCookies(url: Url): Map<String, String>? {
        val nsUrl = NSURL.URLWithString(url.toString()) ?: return null
        val cookies = storage.cookiesForURL(nsUrl) ?: return null
        if (cookies.isEmpty()) return null
        return cookies.mapNotNull { it as? NSHTTPCookie }
            .associate { it.name to it.value }
            .ifEmpty { null }
    }

    override fun setCookie(url: Url, cookie: Cookie) {
        val properties = mutableMapOf<Any?, Any?>(
            NSHTTPCookieName to cookie.name,
            NSHTTPCookieValue to cookie.value,
            NSHTTPCookieDomain to (cookie.domain ?: url.host),
            NSHTTPCookiePath to (cookie.path ?: "/"),
        )
        val nsCookie = NSHTTPCookie.cookieWithProperties(properties) ?: return
        storage.setCookie(nsCookie)
    }

    override fun removeAllCookies() {
        storage.cookies?.forEach { cookie ->
            (cookie as? NSHTTPCookie)?.let(storage::deleteCookie)
        }
    }

    override fun flush() = Unit
}

actual fun getCookieManager(): CookieManager = IosCookieManager()
