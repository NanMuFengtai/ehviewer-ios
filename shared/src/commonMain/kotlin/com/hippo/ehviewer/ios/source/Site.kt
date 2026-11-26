package com.hippo.ehviewer.ios.source

import androidx.compose.ui.graphics.Color

/** 内容源。和 Android 侧的 Sites 一一对应。 */
enum class Site(val label: String, val accent: Color, val hasToken: Boolean) {
    EHENTAI("E站", Color(0xFFE39A3B), true),
    NHENTAI("N站", Color(0xFFE0678A), false),
    JMCOMIC("禁漫", Color(0xFF7D6BD6), false),
}

/** 列表里的一条，两个站统一成同一个形状给 UI 用。 */
data class ListEntry(
    val id: Long,
    val token: String,
    val title: String,
    val thumb: String,
    val pages: Int,
    val meta: String,
)
