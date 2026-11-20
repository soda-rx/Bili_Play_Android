@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sodarx.biliplay.BiliPlayApplication
import io.github.sodarx.biliplay.BuildConfig
import kotlinx.coroutines.delay
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
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(enabled) { if (enabled) updates.check() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (enabled) updates.check() }
    // Automatic downloads only begin while the app is actively being used, never at cold start.
    LaunchedEffect(
        enabled,
        state.release?.version,
        state.autoDownload,
        state.ready,
        state.downloading,
        lifecycleState,
    ) {
        if (
            !enabled ||
                !lifecycleState.isAtLeast(Lifecycle.State.RESUMED) ||
                state.release == null ||
                !state.autoDownload ||
                state.ready ||
                state.downloading
        )
            return@LaunchedEffect
        delay(12_000)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            updates.startDeferredDownload()
    }
    if (!enabled || !state.showDialog) return
    val release = state.release ?: return
    androidx.compose.ui.window.Dialog(onDismissRequest = updates::dismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(
                        max =
                            androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                                .dp * .9f
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "发现新版本 v${release.version}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "当前版本：v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("修复了一些已知问题", style = MaterialTheme.typography.bodyMedium)
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("正在下载 ${state.progress}%")
                } else if (state.ready) {
                    Text("更新包已下载，点击“下载并安装”由系统确认安装。")
                }
                if (!context.packageManager.canRequestPackageInstalls())
                    Text(
                        "首次更新需允许此应用请求安装，返回后再次点击“下载并安装”。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            value = state.autoDownload || state.reminders,
                            enabled = !state.autoDownload,
                            role = Role.Checkbox,
                            onValueChange = updates::reminders,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.autoDownload || state.reminders,
                        onCheckedChange = updates::reminders,
                        enabled = !state.autoDownload,
                    )
                    Text("更新提醒", style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://github.com/soda-rx/Bili_Play_Android/releases/tag/${release.version}"
                                ),
                            )
                        )
                    }) { Text("了解详情") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = updates::dismiss) { Text("取消") }
                    Button(
                        enabled = !state.downloading,
                        onClick = {
                            updates.download {
                                scope.launch {
                                    if (
                                        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    )
                                        return@launch
                                    val file = updates.installationFile() ?: run {
                                        updates.installationFailed()
                                        return@launch
                                    }
                                    runCatching {
                                            if (!context.packageManager.canRequestPackageInstalls()) {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                        Uri.parse("package:${context.packageName}"),
                                                    )
                                                )
                                            } else {
                                                val uri =
                                                    FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.files",
                                                        file,
                                                    )
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW)
                                                        .setDataAndType(
                                                            uri,
                                                            "application/vnd.android.package-archive",
                                                        )
                                                        .addFlags(
                                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        )
                                                )
                                            }
                                        }
                                        .onFailure { updates.installationFailed() }
                                }
                            }
                        },
                    ) { Text("下载并安装") }
                }
            }
        }
    }
}
