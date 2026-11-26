package com.hippo.ehviewer.ui.screen.jm

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.jm.JmAuth
import com.hippo.ehviewer.jm.JmFavorites
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch

/** 禁漫账号登录。cookie 只存本机，登录成功后直接退回上一页。 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.JmLoginScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (JmAuth.loggedIn) "禁漫帳號" else "禁漫登入") },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { padding ->
        val currentUser = JmAuth.user
        if (currentUser != null) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "已登入", style = MaterialTheme.typography.titleMedium)
                Text(text = "帳號：${currentUser.label}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "等級：${currentUser.level.ifBlank { "-" }}　J Coins：${currentUser.coin.ifBlank { "-" }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "收藏：${currentUser.favorites}/${currentUser.favoritesMax}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        JmAuth.clear()
                        JmFavorites.clear()
                        navigator.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登出")
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("帳號 / 郵箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密碼") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    if (loading) return@Button
                    if (username.isBlank() || password.isBlank()) {
                        error = "請填帳號和密碼"
                        return@Button
                    }
                    loading = true
                    error = null
                    scope.launch {
                        runCatching { JmAuth.login(username, password) }
                            .onSuccess {
                                loading = false
                                navigator.popBackStack()
                            }
                            .onFailure {
                                loading = false
                                error = it.message ?: "登入失敗"
                            }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "登入中…" else "登入")
            }
            Text(
                text = "用你自己的禁漫帳號登入。帳號密碼只在這台手機上提交給禁漫，登入後的 cookie 存在本地，App 不會上傳。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
