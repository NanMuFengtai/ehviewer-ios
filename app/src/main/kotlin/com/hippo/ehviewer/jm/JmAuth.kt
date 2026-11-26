package com.hippo.ehviewer.jm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hippo.ehviewer.util.AppLog
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

private const val TAG = "JmAuth"

/** 登录后拿到的账号信息（字段名跟禁漫 /login 返回的一致）。 */
@Serializable
data class JmUser(
    val uid: String = "",
    val username: String = "",
    val email: String = "",
    val photo: String = "",
    val level: String = "",
    val levelName: String = "",
    val exp: String = "",
    val expPercent: String = "",
    val coin: String = "",
    val favorites: Int = 0,
    val favoritesMax: Int = 0,
) {
    val label: String get() = username.ifBlank { uid }
}

@Serializable
private data class StoredSession(
    val cookie: String = "",
    val user: JmUser = JmUser(),
)

/**
 * 禁漫登录态。
 *
 * 登录成功后服务端发 `ipb_member_id` / `ipb_pass_hash` 这些 cookie，另外响应里还有 `s`，
 * 按官方客户端约定拼成 `AVS=<s>` 一起带上。cookie 整串持久化到 `files/jm/auth.json`，
 * 重启后照样有效，直到用户退出登录或服务端让 cookie 失效。
 */
object JmAuth {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = File(appCtx.filesDir, "jm/auth.json")

    /** 当前登录用户；null 表示没登录。Compose 可以直接观察。 */
    var user: JmUser? by mutableStateOf(null)
        private set

    /** 请求要带的 Cookie 头（整串）。 */
    var cookie: String = ""
        private set

    val loggedIn: Boolean get() = user != null

    fun load() {
        runCatching {
            if (!file.exists()) return
            val stored = json.decodeFromString<StoredSession>(file.readText())
            if (stored.cookie.isNotBlank()) {
                cookie = stored.cookie
                user = stored.user.takeIf { it.uid.isNotBlank() || it.username.isNotBlank() }
            }
            AppLog.i(TAG, "session loaded ${user?.label ?: "(none)"}")
        }.onFailure { AppLog.w(TAG, "session read failed", it) }
    }

    fun save(cookie: String, user: JmUser) {
        this.cookie = cookie
        this.user = user
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(StoredSession(cookie, user)))
        }.onFailure { AppLog.w(TAG, "session write failed", it) }
        AppLog.i(TAG, "login ok ${user.label}")
    }

    /** 只更新账号信息（刷新资料时用），cookie 不动。 */
    fun updateUser(user: JmUser) {
        if (this.user == null) return
        this.user = user
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(StoredSession(cookie, user)))
        }.onFailure { AppLog.w(TAG, "session write failed", it) }
    }

    fun clear() {
        cookie = ""
        user = null
        runCatching { if (file.exists()) file.delete() }
        AppLog.i(TAG, "logged out")
    }

    /** 账号密码登录，成功直接写进登录态。 */
    suspend fun login(username: String, password: String): JmUser {
        val (cookie, user) = JmApi.login(username.trim(), password)
        save(cookie, user)
        return user
    }
}
