package com.hippo.ehviewer.ui.screen.nhentai

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.getCookieManager
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.hippo.ehviewer.nhentai.NhentaiApi
import com.hippo.ehviewer.nhentai.NhentaiSession
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.NhentaiAccountScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.util.setDefaultSettings
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val LOGIN_URL = "https://nhentai.net/login"

/**
 * nhentai 账号页。
 *
 * 未登录时直接内嵌站点登录页：站点用 Cloudflare Turnstile 做验证码，
 * 纯接口登录（PoW + captcha）走不通，只能用真实 WebView 让用户自己登。
 * 登录成功后从 WebView 的 Cookie 里取 access_token / refresh_token 存下来。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NhentaiAccountScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    DrawerHandle(true)

    var signedIn by remember { mutableStateOf(NhentaiSession.isSignedIn) }
    var username by remember { mutableStateOf(NhentaiSession.username) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val invalidTokenMessage = stringResource(R.string.nhentai_token_invalid)

    fun applyToken() {
        val input = tokenInput
        val access = Regex("access_token=([^;\\s]+)").find(input)?.groupValues?.get(1)
            ?: input.trim().takeIf { it.isNotBlank() && !it.contains('=') }
        if (access.isNullOrBlank()) {
            loginError = invalidTokenMessage
            return
        }
        val refresh = Regex("refresh_token=([^;\\s]+)").find(input)?.groupValues?.get(1).orEmpty()
        scope.launch {
            val name = runCatching { NhentaiApi.me(access).username }.getOrNull()
            if (name.isNullOrBlank()) {
                loginError = invalidTokenMessage
            } else {
                NhentaiSession.save(access, refresh, name)
                signedIn = true
                username = name
                loginError = null
                tokenInput = ""
            }
        }
    }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text(text = stringResource(R.string.nhentai_token_title)) },
            text = {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = { Text(text = stringResource(R.string.nhentai_token_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTokenDialog = false
                        applyToken()
                    },
                ) {
                    Text(text = stringResource(R.string.nhentai_login))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenDialog = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_nhentai_account)) },
                navigationIcon = { NavigationIcon() },
                actions = {
                    IconButton(
                        onClick = { showTokenDialog = true },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(imageVector = Icons.Default.Key, contentDescription = stringResource(R.string.nhentai_token_title))
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (signedIn) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = username.ifBlank { stringResource(R.string.nhentai_login) },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.nhentai_login_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = {
                            NhentaiSession.signOut()
                            signedIn = false
                            username = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.nhentai_logout))
                    }
                }
            } else {
                val state = rememberWebViewState(url = LOGIN_URL)
                LaunchedEffect(state) {
                    snapshotFlow { !state.isLoading }.collect { finished ->
                        if (!finished) return@collect
                        val cookies = runCatching {
                            getCookieManager().getCookies(
                                URLBuilder(URLProtocol.HTTPS, "nhentai.net").build(),
                            )
                        }.getOrNull()
                        val access = cookies?.get("access_token")
                        val refresh = cookies?.get("refresh_token")
                        if (access.isNullOrBlank() || refresh.isNullOrBlank()) return@collect
                        val name = runCatching { NhentaiApi.me(access).username }.getOrNull()
                        NhentaiSession.save(access, refresh, name)
                        signedIn = true
                        username = name.orEmpty()
                    }
                }
                WebView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onCreated = { it.setDefaultSettings() },
                )
                loginError?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** 未登录时统一的入口按钮，详情页、收藏页都会用到。 */
@Composable
fun NhentaiSignInButton(navigator: DestinationsNavigator, modifier: Modifier = Modifier) {
    Button(
        onClick = { navigator.navigate(NhentaiAccountScreenDestination) },
        modifier = modifier,
    ) {
        Text(text = stringResource(R.string.nhentai_login))
    }
}
