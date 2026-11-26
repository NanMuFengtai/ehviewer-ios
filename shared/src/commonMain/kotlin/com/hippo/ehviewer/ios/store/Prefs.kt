package com.hippo.ehviewer.ios.store

import com.hippo.ehviewer.ios.source.EhSource
import com.hippo.ehviewer.ios.source.JmSource
import com.hippo.ehviewer.ios.source.NhentaiApi

/**
 * 轻量键值存储。iOS 侧走 NSUserDefaults。
 * 只放少量账号和设置，不放列表数据。
 */
expect object Prefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** 三个站的登录态从这里读写，改完立即同步给各自的 Source。 */
object Account {
    private const val KEY_EH_COOKIE = "eh.cookie"
    private const val KEY_NH_TOKEN = "nh.token"
    private const val KEY_NH_USER = "nh.user"
    private const val KEY_JM_COOKIE = "jm.cookie"
    private const val KEY_JM_USER = "jm.user"

    var ehCookie: String = ""
        private set
    var nhToken: String = ""
        private set
    var nhUser: String = ""
        private set
    var jmCookie: String = ""
        private set
    var jmUser: String = ""
        private set

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        ehCookie = Prefs.getString(KEY_EH_COOKIE) ?: ""
        nhToken = Prefs.getString(KEY_NH_TOKEN) ?: ""
        nhUser = Prefs.getString(KEY_NH_USER) ?: ""
        jmCookie = Prefs.getString(KEY_JM_COOKIE) ?: ""
        jmUser = Prefs.getString(KEY_JM_USER) ?: ""
        apply()
    }

    private fun apply() {
        EhSource.cookie = ehCookie
        EhSource.useExHentai = ehCookie.contains("igneous")
        NhentaiApi.token = nhToken
        JmSource.cookie = jmCookie
    }

    fun setEhCookie(value: String) {
        ehCookie = value.trim()
        Prefs.putString(KEY_EH_COOKIE, ehCookie)
        apply()
    }

    fun setNhentai(token: String, user: String) {
        nhToken = token.trim()
        nhUser = user
        Prefs.putString(KEY_NH_TOKEN, nhToken)
        Prefs.putString(KEY_NH_USER, nhUser)
        apply()
    }

    fun setJm(cookie: String, user: String) {
        jmCookie = cookie.trim()
        jmUser = user
        Prefs.putString(KEY_JM_COOKIE, jmCookie)
        Prefs.putString(KEY_JM_USER, jmUser)
        apply()
    }

    fun clearEh() {
        ehCookie = ""
        Prefs.remove(KEY_EH_COOKIE)
        apply()
    }

    fun clearNhentai() {
        nhToken = ""
        nhUser = ""
        Prefs.remove(KEY_NH_TOKEN)
        Prefs.remove(KEY_NH_USER)
        apply()
    }

    fun clearJm() {
        jmCookie = ""
        jmUser = ""
        Prefs.remove(KEY_JM_COOKIE)
        Prefs.remove(KEY_JM_USER)
        apply()
    }

    fun isEhLoggedIn(): Boolean = ehCookie.isNotBlank()
    fun isNhentaiLoggedIn(): Boolean = nhToken.isNotBlank()
    fun isJmLoggedIn(): Boolean = jmCookie.isNotBlank()
}
