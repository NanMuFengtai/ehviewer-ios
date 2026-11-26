package com.hippo.ehviewer.ios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.ios.store.Account

/**
 * 登录页。三站都是粘贴凭据的形式：
 * - E 站：浏览器登录后从开发者工具里复制整段 Cookie
 * - N 站：/api/v2/auth 拿到的 access token
 * - 禁漫：浏览器登录后的 Cookie
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var ehCookie by remember { mutableStateOf(Account.ehCookie) }
    var nhToken by remember { mutableStateOf(Account.nhToken) }
    var nhUser by remember { mutableStateOf(Account.nhUser) }
    var jmCookie by remember { mutableStateOf(Account.jmCookie) }
    var jmUser by remember { mutableStateOf(Account.jmUser) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("登录") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("E站", "N站", "禁漫").forEachIndexed { i, label ->
                    val selected = tab == i
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            )
                            .clickable { tab = i; message = null }
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                    ) {
                        Text(
                            label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (tab) {
                0 -> {
                    Text(
                        "在浏览器登录 e-hentai / exhentai 后，打开开发者工具 → Application → Cookies，" +
                            "复制整段 cookie 粘到这里。里面带 igneous 就自动走里站。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ehCookie,
                        onValueChange = { ehCookie = it },
                        label = { Text("Cookie") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            Account.setEhCookie(ehCookie)
                            message = if (ehCookie.contains("igneous")) "已保存，走里站" else "已保存（表站）"
                        }) { Text("保存") }
                        TextButton(onClick = {
                            Account.clearEh()
                            ehCookie = ""
                            message = "已退出"
                        }) { Text("退出登录") }
                    }
                    Text(
                        "当前状态：" + if (Account.isEhLoggedIn()) "已登录" else "未登录",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                1 -> {
                    Text(
                        "N 站用 access token。登录接口 /api/v2/auth/login 返回的 access_token 粘下面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = nhToken,
                        onValueChange = { nhToken = it },
                        label = { Text("access token") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = nhUser,
                        onValueChange = { nhUser = it },
                        label = { Text("用户名（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            Account.setNhentai(nhToken, nhUser)
                            message = "已保存"
                        }) { Text("保存") }
                        TextButton(onClick = {
                            Account.clearNhentai()
                            nhToken = ""
                            nhUser = ""
                            message = "已退出"
                        }) { Text("退出登录") }
                    }
                    Text(
                        "当前状态：" + if (Account.isNhentaiLoggedIn()) "已登录" else "未登录",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                else -> {
                    Text(
                        "禁漫用浏览器登录后的 Cookie，粘整段即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = jmCookie,
                        onValueChange = { jmCookie = it },
                        label = { Text("Cookie") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = jmUser,
                        onValueChange = { jmUser = it },
                        label = { Text("用户名（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            Account.setJm(jmCookie, jmUser)
                            message = "已保存"
                        }) { Text("保存") }
                        TextButton(onClick = {
                            Account.clearJm()
                            jmCookie = ""
                            jmUser = ""
                            message = "已退出"
                        }) { Text("退出登录") }
                    }
                    Text(
                        "当前状态：" + if (Account.isJmLoggedIn()) "已登录" else "未登录",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
