package com.hippo.ehviewer.ios.store

import com.hippo.ehviewer.ios.net.AppJson
import com.hippo.ehviewer.ios.source.Site
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.time.Clock

/** 浏览历史，本地存一份就够了，不同步服务器。 */
object HistoryStore {
    private const val KEY = "history.entries"
    private const val MAX = 200

    @Serializable
    data class Entry(
        val site: String,
        val id: Long,
        val token: String = "",
        val title: String = "",
        val thumb: String = "",
        val time: Long = 0,
    )

    private var cache: MutableList<Entry>? = null

    private fun load(): MutableList<Entry> {
        cache?.let { return it }
        val raw = Prefs.getString(KEY)
        val list = if (raw.isNullOrBlank()) {
            mutableListOf()
        } else {
            runCatching {
                AppJson.decodeFromString<List<Entry>>(raw).toMutableList()
            }.getOrDefault(mutableListOf())
        }
        cache = list
        return list
    }

    private fun persist(list: List<Entry>) {
        runCatching { Prefs.putString(KEY, AppJson.encodeToString(list)) }
    }

    fun all(): List<Entry> = load().toList()

    fun add(site: Site, id: Long, token: String, title: String, thumb: String) {
        val list = load()
        list.removeAll { it.site == site.name && it.id == id }
        list.add(
            0,
            Entry(
                site = site.name,
                id = id,
                token = token,
                title = title,
                thumb = thumb,
                time = Clock.System.now().epochSeconds,
            ),
        )
        while (list.size > MAX) list.removeAt(list.lastIndex)
        persist(list)
    }

    fun clear() {
        val list = load()
        list.clear()
        persist(list)
    }
}
