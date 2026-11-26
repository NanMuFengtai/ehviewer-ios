package com.hippo.ehviewer.ui.screen.nhentai

import com.ehviewer.core.ui.util.SETNodeGenerator

/**
 * nhentai 列表封面与详情封面的共享元素节点。
 *
 * 和 E 站一样两个生成器互连：列表里注册的节点会被详情页认领，
 * 于是「列表点开本子 → 详情封面从卡片位置放大」「返回 → 缩回卡片」都有动画。
 */
val nhentaiListThumbs = SETNodeGenerator()
val nhentaiDetailThumbs = SETNodeGenerator()

fun initNhentaiSETConnection() {
    nhentaiListThumbs connectTo nhentaiDetailThumbs
}

/** 列表卡片与详情封面共用的 key。 */
internal fun nhentaiCoverKey(id: Long) = "nhentai-$id"
