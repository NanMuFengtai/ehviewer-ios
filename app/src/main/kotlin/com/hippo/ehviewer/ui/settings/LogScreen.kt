package com.hippo.ehviewer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.util.AppLog
import com.hippo.ehviewer.util.CrashHandler
import com.hippo.ehviewer.util.ReadableTime
import com.hippo.ehviewer.util.addTextToClipboard
import com.hippo.ehviewer.util.displayPath
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tarsin.snackbar

/**
 * 日志设置页。
 *
 * 日志始终写一份在应用内，另外让用户自己挑一个文件夹，同一份内容自动同步过去，
 * 出问题直接用文件管理器把那个文件发出来就行。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.LogScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var showRecent by remember { mutableStateOf(false) }
    var recentText by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf(AppLog.mirrorName()) }
    var sizeKb by remember { mutableStateOf(AppLog.internalSizeKb()) }

    LaunchedEffect(refreshKey) {
        folderName = AppLog.mirrorName()
        sizeKb = AppLog.internalSizeKb()
    }

    val folderSet = stringResource(R.string.logs_folder_set)
    val folderFailed = stringResource(R.string.logs_folder_failed)

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { applyFolder(context, treeUri) }
            if (ok) {
                AppLog.i("Logs", "folder selected: ${AppLog.mirrorName()}")
                AppLog.seedMirror()
                AppLog.flushNow()
                folderName = AppLog.mirrorName()
                snackbar(folderSet.format(folderName))
                refreshKey++
            } else {
                snackbar(folderFailed)
            }
        }
    }

    if (showRecent) {
        AlertDialog(
            onDismissRequest = { showRecent = false },
            title = { Text(text = stringResource(R.string.logs_view)) },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = recentText.ifBlank { stringResource(R.string.logs_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecent = false }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        addTextToClipboard(recentText, useToast = true)
                    },
                ) {
                    Text(text = stringResource(R.string.logs_copy))
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(text = stringResource(R.string.logs_clear)) },
            text = { Text(text = stringResource(R.string.logs_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLog.clear()
                        showClearDialog = false
                        refreshKey++
                        scope.launch { snackbar(context.getString(R.string.logs_cleared)) }
                    },
                ) {
                    Text(text = stringResource(R.string.logs_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_logs)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(paddingValues),
        ) {
            SwitchPreference(
                title = stringResource(R.string.logs_enabled),
                summary = stringResource(R.string.logs_enabled_summary),
                state = Settings.logEnabled.asMutableState(),
            )
            Preference(
                title = stringResource(R.string.logs_folder),
                summary = folderName.ifBlank { stringResource(R.string.logs_folder_none) },
                onClick = {
                    runCatching { pickFolder.launch(null) }
                        .onFailure { scope.launch { snackbar(folderFailed) } }
                },
            )
            Preference(
                title = stringResource(R.string.logs_current),
                summary = AppLog.fileNameOfDay(),
                onClick = {
                    AppLog.flushNow()
                    scope.launch { snackbar(context.getString(R.string.logs_synced)) }
                    refreshKey++
                },
            )
            Preference(
                title = stringResource(R.string.logs_view),
                summary = stringResource(R.string.logs_size, sizeKb),
                onClick = {
                    recentText = AppLog.recent(300)
                    showRecent = true
                },
            )
            LauncherPreference(
                title = stringResource(R.string.logs_export),
                summary = stringResource(R.string.logs_export_summary),
                contract = ActivityResultContracts.CreateDocument("application/zip"),
                key = "很太之家日志-${ReadableTime.getFilenamableTime()}.zip",
            ) { uri ->
                if (uri != null) {
                    val ok = runCatching { exportLogs(context, uri) }.isSuccess
                    scope.launch {
                        snackbar(context.getString(if (ok) R.string.logs_export_done else R.string.logs_export_failed))
                    }
                }
            }
            Preference(
                title = stringResource(R.string.logs_clear),
                summary = stringResource(R.string.logs_clear_summary),
                onClick = { showClearDialog = true },
            )
            Text(
                text = stringResource(R.string.logs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

/** 记录用户选中的目录：整个目录树授权 + 记住目录文档 URI，之后直接往里写文件。 */
private fun applyFolder(context: Context, treeUri: Uri): Boolean = runCatching {
    val resolver = context.contentResolver
    resolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    val document = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    Settings.logFolderUri.value = document.toString()
    Settings.logFolderName.value = document.displayPath ?: treeUri.displayPath ?: document.toString()
    AppLog.resetMirrorCache()
    true
}.getOrDefault(false)

private fun exportLogs(context: Context, uri: Uri) {
    context.contentResolver.openOutputStream(uri)?.use { output ->
        ZipOutputStream(output).use { zip ->
            fun addFile(file: File, prefix: String) {
                if (!file.isFile) return
                zip.putNextEntry(ZipEntry(prefix + file.name))
                file.inputStream().use { it.copyTo(zip) }
            }
            AppLog.internalDir.listFiles()?.forEach { addFile(it, "") }
            AppLog.backupDir.listFiles()?.forEach { addFile(it, "crash/") }
            zip.putNextEntry(ZipEntry("info.txt"))
            CrashHandler.collectInfo(zip.writer())
            zip.putNextEntry(ZipEntry("recent-log.txt"))
            zip.write(AppLog.recent(500).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
