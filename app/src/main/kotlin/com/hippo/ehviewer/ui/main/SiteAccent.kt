package com.hippo.ehviewer.ui.main

import androidx.compose.ui.graphics.Color
import com.hippo.ehviewer.Sites

/**
 * 每个内容源一个主色。
 *
 * 抽屉底色、胶囊指示块都会用它，切换站点时颜色平滑过渡，
 * 这样「从 E 滑到 N」时背后那层背景是跟着一起变的，不是硬切。
 */
object SiteAccent {
    val EH = Color(0xFFE39A3B)
    val NHENTAI = Color(0xFFE0678A)
    val JM = Color(0xFF7E6BD6)

    fun of(site: Int): Color = when (site) {
        Sites.EH -> EH
        Sites.NHENTAI -> NHENTAI
        Sites.JM -> JM
        else -> EH
    }
}
