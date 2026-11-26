package com.hippo.ehviewer.nhentai

import com.hippo.ehviewer.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * nhentai 登录态。
 *
 * 登录在 WebView 里完成（站点用 Cloudflare Turnstile 做验证码，走不了纯接口登录），
 * 成功后从 WebView 的 Cookie 里取出 `access_token` / `refresh_token` 存起来。
 *
 * 之后所有需要鉴权的请求带 `Authorization: User <access_token>`。
 * access_token 寿命很短，过期后（401）用 refresh_token 换新的；refresh 也过期就要求重新登录。
 */
object NhentaiSession {
    private val refreshLock = Mutex()

    val isSignedIn: Boolean
        get() = Settings.nhentaiAccessToken.value.isNotBlank()

    val username: String
        get() = Settings.nhentaiUsername.value

    fun save(accessToken: String, refreshToken: String, name: String?) {
        Settings.nhentaiAccessToken.value = accessToken
        Settings.nhentaiRefreshToken.value = refreshToken
        if (name != null) Settings.nhentaiUsername.value = name
    }

    fun signOut() {
        Settings.nhentaiAccessToken.value = ""
        Settings.nhentaiRefreshToken.value = ""
        Settings.nhentaiUsername.value = ""
    }

    /**
     * 用 refresh_token 换新的 access_token。
     * 失败说明 refresh 也过期了，直接清空登录态并返回 null。
     */
    suspend fun refresh(): String? = refreshLock.withLock {
        val refreshToken = Settings.nhentaiRefreshToken.value
        if (refreshToken.isBlank()) return@withLock null
        val result = runCatching { NhentaiApi.refresh(refreshToken) }
        val pair = result.getOrNull()
        if (pair == null) {
            signOut()
            return@withLock null
        }
        save(pair.access_token, pair.refresh_token, pair.user?.username)
        pair.access_token
    }
}
