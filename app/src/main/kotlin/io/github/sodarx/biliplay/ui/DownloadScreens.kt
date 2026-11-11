@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.data.DownloadEntry

@Composable
internal fun DownloadsScreen(s: AppState, m: BiliPlayViewModel) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf<String?>(null) }
    fun open(entry: DownloadEntry, share: Boolean) {
        menu = null
        runCatching {
            io.github.sodarx.biliplay.download.DownloadInterop.open(
                context,
                entry,
                s.profileId,
                share,
            )
        }
            .onFailure { m.message("无法打开文件，请检查文件是否存在或其他应用是否可用") }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(!s.downloadTasks, { m.downloadTasks(false) }, label = { Text("已下载") })
            FilterChip(s.downloadTasks, { m.downloadTasks(true) }, label = { Text("下载任务") })
        }
        val rows =
            s.downloads
                .filter {
                    if (s.downloadTasks) it.status != "complete" else it.status == "complete"
                }
                .distinctBy {
                    if (it.status == "complete") it.track.bvid + ":" + it.track.page else it.id
                }
        LazyColumn {
            items(rows, key = { it.id }) { entry ->
                val selecting = s.selectedDownloads.isNotEmpty()
                Column {
                    ListItem(
                        onClick = {
                            if (selecting) m.selectDownload(entry.id)
                            else if (entry.status == "complete") m.play(entry.track)
                            else m.retryDownload(entry)
                        },
                        onLongClick = { m.selectDownload(entry.id) },
                        onLongClickLabel = "选择下载",
                        content = {
                            Text(
                                songTitle(entry.track.title),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    if (entry.status == "complete") entry.track.uploader
                                    else downloadStatus(entry)
                                )
                                if (entry.error.isNotBlank())
                                    Text(entry.error, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        leadingContent = {
                            if (selecting)
                                Checkbox(
                                    entry.id in s.selectedDownloads,
                                    { m.selectDownload(entry.id) },
                                )
                            else Artwork(entry.track, 48)
                        },
                        trailingContent = {
                            if (entry.status in listOf("queued", "downloading", "encoding"))
                                IconButton(onClick = { m.cancelDownload(entry) }) {
                                    Icon(Icons.Default.Close, "取消下载")
                                }
                            else if (entry.status in listOf("failed", "cancelled"))
                                IconButton(onClick = { m.retryDownload(entry) }) {
                                    Icon(Icons.Default.Refresh, "重试下载")
                                }
                            else
                                Box {
                                    IconButton(onClick = { menu = entry.id }) {
                                        Icon(Icons.Default.MoreVert, "下载操作")
                                    }
                                    DropdownMenu(
                                        expanded = menu == entry.id,
                                        onDismissRequest = { menu = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("用其他应用打开") },
                                            onClick = { open(entry, false) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("分享文件") },
                                            onClick = { open(entry, true) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除") },
                                            onClick = {
                                                menu = null
                                                m.selectDownload(entry.id)
                                                m.dialog("deleteDownloads")
                                            },
                                        )
                                    }
                                }
                        },
                    )
                    val progress by
                        animateFloatAsState(
                            entry.progress / 100f,
                            MaterialTheme.motionScheme.fastEffectsSpec(),
                            label = "download progress",
                        )
                    androidx.compose.animation.AnimatedVisibility(
                        entry.status in listOf("downloading", "encoding"),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        LinearWavyProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            if (rows.isEmpty()) item { EmptyContent(if (s.downloadTasks) "暂无下载任务" else "暂无下载") }
        }
    }
}

private fun downloadStatus(e: DownloadEntry) =
    when (e.status) {
        "queued" -> "等待下载"
        "downloading" -> "下载中 ${e.progress}%"
        "encoding" -> "转码中 ${e.progress}%"
        "cancelled" -> "已取消"
        "failed" -> "下载失败"
        else -> "已下载"
    }
