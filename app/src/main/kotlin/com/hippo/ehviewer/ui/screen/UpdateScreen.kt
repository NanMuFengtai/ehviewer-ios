package com.hippo.ehviewer.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.update.Updater
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 檢查更新：读 GitHub Release，下载 APK，校验后调系统安装器。
 * 更新源仓库固定在 Settings.updateRepo（默认 NanMuFengtai/ehviewer-apk），界面上不展示。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.UpdateScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repoState = Settings.updateRepo.collectAsState()

    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var downloadedLabel by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checkedAt by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadToken by remember { mutableStateOf(0) }

    fun check() {
        if (checking || downloading) return
        checking = true
        message = null
        release = null
        scope.launch {
            runCatching { Updater.checkLatest(repoState.value) }
                .onSuccess { latest ->
                    checkedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    if (latest == null) {
                        message = "這個倉庫還沒有發佈版本"
                    } else if (Updater.isNewer(latest)) {
                        release = latest
                        message = null
                    } else {
                        message = "已是最新版本"
                    }
                }
                .onFailure { message = "檢查失敗：${it.message ?: "網路錯誤"}" }
            checking = false
        }
    }

    fun downloadAndInstall(target: Updater.Release) {
        if (downloading) return
        downloading = true
        progress = 0f
        downloadedLabel = ""
        // 每次下载一个编号，旧任务即使还在回调也写不进新一次的进度
        val token = ++downloadToken
        downloadJob = scope.launch {
            try {
                val file = Updater.download(context, target) { done, total ->
                    if (token != downloadToken) return@download
                    progress = if (total > 0) (done.toFloat() / total) else 0f
                    downloadedLabel = formatSize(done) + if (total > 0) " / " + formatSize(total) else ""
                }
                if (token != downloadToken) return@launch
                if (Updater.verify(file, target)) {
                    downloading = false
                    Updater.install(context, file)
                } else {
                    message = "下載的檔案校驗失敗，已丟棄，請重試"
                    file.delete()
                    downloading = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "下載失敗：${e.message ?: "網路錯誤"}"
                downloading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "檢查更新", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "目前版本", style = MaterialTheme.typography.titleSmall)
            Text(
                text = Updater.currentVersionLine(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "構建時間：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(Updater.currentBuildTime() * 1000)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { check() }, enabled = !checking && !downloading) {
                    Text(if (checking) "檢查中…" else "檢查更新")
                }
                if (checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                checkedAt?.let {
                    Text(
                        text = "上次檢查 $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.startsWith("檢查失敗") || it.startsWith("下載失敗") || it.contains("校驗失敗")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            release?.let { latest ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "新版本 ${latest.title}（${latest.displayTime}）",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (latest.notes.isNotBlank()) {
                    Text(
                        text = latest.notes.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "安裝包 " + formatSize(latest.apkSize) + (if (latest.sha256 != null) " · 含 SHA256 校驗" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloading) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = downloadedLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { downloadAndInstall(latest) },
                        enabled = !downloading,
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(if (downloading) "下載中…" else "下載並安裝")
                    }
                    if (downloading) {
                        OutlinedButton(onClick = {
                            downloadToken += 1
                            downloadJob?.cancel()
                            downloadJob = null
                            progress = 0f
                            downloadedLabel = ""
                            downloading = false
                            Toast.makeText(context, "已取消下載", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("取消")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "安裝時系統會問一次「允許安裝未知應用」，允許後才能覆蓋安裝。" +
                    "更新只能裝同一個簽名的包，換簽名必須先卸載。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val mb = bytes / 1024.0 / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
