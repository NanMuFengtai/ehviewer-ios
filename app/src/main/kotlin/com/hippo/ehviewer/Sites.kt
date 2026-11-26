package com.hippo.ehviewer

/**
 * 内容源常量。
 *
 * [Settings.contentSource] 里存的就是这里的值，决定抽屉里的导航项和页面走哪套实现。
 * E 站内部还有 E / EX 之分，那个仍然由 [Settings.gallerySite] 管，不要和本值混淆。
 */
object Sites {
    const val EH = 0
    const val NHENTAI = 1
    const val JM = 2

    val all = intArrayOf(EH, NHENTAI, JM)

    fun isNhentai(value: Int) = value == NHENTAI

    fun isJm(value: Int) = value == JM
}
