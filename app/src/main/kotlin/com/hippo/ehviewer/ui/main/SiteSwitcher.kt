package com.hippo.ehviewer.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.Sites

/** 胶囊滑过去的时长，抽屉的关闭要等它走完再开始。 */
const val SITE_SWITCH_ANIMATION_MS = 520L

/**
 * 抽屉顶部的站点胶囊开关：E（E-Hentai）/ N（nhentai）/ 禁（JM）。
 *
 * 指示块用低阻尼弹簧滑过去，会先冲过头再弹回来，手感像果冻被甩到下一个槽位；
 * 滑动过程中还会被横向拉长一点再回弹。底色由调用方按站点色渐变。
 */
@Composable
fun SiteSwitcher(
    site: Int,
    onSwitch: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(Sites.EH to "E", Sites.NHENTAI to "N", Sites.JM to "禁")
    val selected = options.indexOfFirst { it.first == site }.coerceAtLeast(0)

    BoxWithConstraints(modifier = modifier.height(44.dp)) {
        val segment = maxWidth / options.size.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current

        // 位置：弹簧，阻尼调低才有「果冻」的过冲回弹
        val offset = remember { Animatable(with(density) { (segment * selected).toPx() }) }
        // 拉伸：每次切换先拉长 12%，再弹回去
        val stretch = remember { Animatable(1f) }

        LaunchedEffect(selected, segment) {
            val target = with(density) { (segment * selected).toPx() }
            if (offset.targetValue == target && !offset.isRunning) {
                // 首次布局或宽度变化时直接对齐，别让指示块从 0 滑过来
                offset.snapTo(target)
            } else {
                offset.animateTo(
                    targetValue = target,
                    animationSpec = spring(
                        // 阻尼压到 0.42：到站时会先冲过头一点再弹回来，像果冻撞到槽位
                        dampingRatio = 0.42f,
                        stiffness = 520f,
                    ),
                )
            }
        }
        LaunchedEffect(selected) {
            stretch.snapTo(1.18f)
            stretch.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.36f, stiffness = Spring.StiffnessMedium),
            )
        }

        val indicatorColor by animateColorAsState(
            targetValue = SiteAccent.of(site),
            animationSpec = tween(durationMillis = 380),
            label = "siteIndicatorColor",
        )

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }
                        .width(segment)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .graphicsLayer { scaleX = stretch.value }
                        .clip(CircleShape)
                        .background(indicatorColor),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    options.forEachIndexed { index, (value, letter) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // 不要点击涟漪：切换本身有胶囊动画，涟漪会在深色胶囊上糊出一块白
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { if (value != site) onSwitch(value) },
                            contentAlignment = Alignment.Center,
                        ) {
                            val letterColor by animateColorAsState(
                                targetValue = if (index == selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                animationSpec = tween(durationMillis = 260),
                                label = "siteLetterColor",
                            )
                            Text(
                                text = letter,
                                style = MaterialTheme.typography.titleMedium,
                                color = letterColor,
                            )
                        }
                    }
                }
            }
        }
    }
}
