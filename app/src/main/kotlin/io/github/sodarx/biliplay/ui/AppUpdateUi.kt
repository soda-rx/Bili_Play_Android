@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sodarx.biliplay.BiliPlayApplication
import io.github.sodarx.biliplay.BuildConfig
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateSettings() {
    val updates = (LocalContext.current.applicationContext as BiliPlayApplication).updates
    val state by updates.state.collectAsStateWithLifecycle()
    Column {
        ListItem(onClick = { updates.check(manual = true) }, enabled = !state.checking,
            content = { Text(if (state.checking) "正在检查更新…" else "检查软件更新") },
            supportingContent = {
                Text(state.message ?: "当前版本：v${BuildConfig.VERSION_NAME}")
            })
        ListItem(content = { Text("自动下载更新") },
            trailingContent = { Switch(state.autoDownload, updates::automaticDownload) })
        ListItem(content = { Text("更新提醒") },
            trailingContent = {
                Switch(
                    checked = state.autoDownload || state.reminders,
                    onCheckedChange = updates::reminders,
                    enabled = !state.autoDownload,
                )
            })
    }
}

@Composable
internal fun AppUpdateUi(enabled: Boolean) {
    val context = LocalContext.current
    val updates = (context.applicationContext as BiliPlayApplication).updates
    val state by updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled) { if (enabled) updates.check() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (enabled) updates.check() }
    if (!enabled || !state.showDialog) return
    val release = state.release ?: return
    AlertDialog(
        onDismissRequest = updates::dismiss,
        title = { Text("发现新版本 v${release.version}") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(release.notes.ifBlank { "新版本已发布。" })
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("正在下载 ${state.progress}%")
                } else if (state.ready) Text("更新包已下载，点击更新后由系统确认安装。")
                if (!context.packageManager.canRequestPackageInstalls())
                    Text("首次更新需允许此应用请求安装，返回后再次点击更新。", style = MaterialTheme.typography.bodySmall)
                state.message?.let { Text(it) }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/soda-rx/Bili_Play_Android/releases/tag/${release.version}")))
                }) { Text("查看正式 Release") }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.downloading, onClick = {
                updates.download {
                    scope.launch {
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                        val file = updates.installationFile() ?: run {
                            updates.installationFailed()
                            return@launch
                        }
                        runCatching { if (!context.packageManager.canRequestPackageInstalls()) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")))
                        } else {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } }.onFailure { updates.installationFailed() }
                    }
                }
            }) { Text("更新") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { updates.reminders(false) },
                    enabled = !state.autoDownload,
                ) { Text("关闭提醒") }
                TextButton(onClick = updates::dismiss) { Text("取消") }
            }
        },
    )
}
