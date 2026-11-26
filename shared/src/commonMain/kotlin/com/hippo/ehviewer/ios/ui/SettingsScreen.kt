package com.hippo.ehviewer.ios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.ios.source.Site
import com.hippo.ehviewer.ios.store.Account
import com.hippo.ehviewer.ios.store.HistoryStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("账号")
            SettingRow("登录 / 切换账号") { onOpenLogin() }
            SmallLine(
                "E站 " + (if (Account.isEhLoggedIn()) "已登录" else "未登录") +
                    " · N站 " + (if (Account.isNhentaiLoggedIn()) "已登录" else "未登录") +
                    " · 禁漫 " + (if (Account.isJmLoggedIn()) "已登录" else "未登录"),
            )

            HorizontalDivider()

            SectionTitle("浏览")
            SettingRow("阅读记录") { onOpenHistory() }
            SettingRow("清空阅读记录") { HistoryStore.clear() }

            HorizontalDivider()

            SectionTitle("外观")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
            }

            HorizontalDivider()

            SectionTitle("关于")
            SmallLine("很太之家 iOS 版")
            SmallLine("1.15.28")
            SmallLine("内容来源：E-Hentai / nhentai / 禁漫")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SmallLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpen: (Site, Long, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = HistoryStore.all()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("阅读记录") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { HistoryStore.clear() }) { Text("清空") }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("还没有记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                androidx.compose.foundation.lazy.items(entries) { e ->
                    val site = runCatching { Site.valueOf(e.site) }.getOrDefault(Site.EHENTAI)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(site, e.id, e.token, e.title) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = e.thumb,
                            contentDescription = e.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(56.dp)
                                .height(76.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                e.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                site.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = site.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 保留一位小数。 */
fun Float.oneDecimalText(): String {
    val i = (this * 10).toInt()
    return "${i / 10}.${i % 10}"
}
