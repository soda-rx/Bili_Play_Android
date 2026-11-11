@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.*
import kotlinx.coroutines.launch

@Composable
private fun FeatureDialog(
    title: String,
    m: BiliPlayViewModel,
    confirm: String = "关闭",
    onConfirm: () -> Unit = { m.dismissDialog() },
    cancel: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = m::dismissDialog,
        title = { Text(title) },
        text = content,
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirm) } },
        dismissButton = { if (cancel) TextButton(onClick = m::dismissDialog) { Text("取消") } },
    )
}

@Composable
internal fun FeatureDialogs(s: AppState, m: BiliPlayViewModel, onDownload: () -> Unit) {
    val type = s.dialog ?: return
    if (type == "trackActions") {
        val track = s.actionTrack ?: return
        val sheet =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = m::dismissDialog, sheetState = sheet) {
            BackHandler {
                scope.launch {
                    sheet.hide()
                    m.dismissDialog()
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(
                        max =
                            androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                                .dp * .9f
                    )
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    songTitle(track.title),
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                )
                FeatureEntry(
                    "播放",
                    Icons.Default.PlayArrow,
                    onClick = {
                        m.dismissDialog()
                        m.play(track)
                    },
                )
                FeatureEntry(
                    "加入播放队列",
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    onClick = { m.enqueue(track) },
                )
                FeatureEntry(
                    "下一首播放并加入播放队列",
                    Icons.Default.SkipNext,
                    onClick = { m.enqueue(track, true) },
                )
                val folderMembership =
                    s.route == "folder" &&
                        s.selectedFolder?.owned == true &&
                        s.folderTracks.any { it.key == track.key }
                if (folderMembership) {
                    FeatureEntry(
                        "取消收藏",
                        Icons.Default.FavoriteBorder,
                        onClick = { s.selectedFolder?.let { m.setFavorite(it, false) } },
                    )
                } else if (s.route != "folder") {
                    val membership = s.actionFavorites
                    FeatureEntry(
                        if (membership == null) "读取收藏状态…"
                        else if (membership.values.any { it }) "取消收藏" else "收藏",
                        if (membership?.values?.any { it } == true) Icons.Default.Favorite
                        else Icons.Default.FavoriteBorder,
                        enabled = membership != null,
                        onClick = { m.chooseFavorite(track) },
                    )
                    if (membership == null && s.pageError != null)
                        TextButton(onClick = { m.dialog("trackActions", track) }) {
                            Text("重试读取收藏状态")
                        }
                }
                FeatureEntry("下载", Icons.Default.Download, onClick = { m.download(track) })
                val context = LocalContext.current
                FeatureEntry(
                    "分享链接",
                    Icons.Default.Share,
                    onClick = {
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                this.type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "${track.title} https://www.bilibili.com/video/${track.bvid}?p=${track.page}",
                                )
                            }
                        runCatching { context.startActivity(Intent.createChooser(intent, "分享歌曲")) }
                            .onFailure { m.message("没有可用的分享应用") }
                        m.dismissDialog()
                    },
                )
                if (track.uploaderId > 0)
                    FeatureEntry(
                        "UP 主",
                        Icons.Default.Person,
                        onClick = {
                            m.dismissDialog()
                            m.openUploader(Uploader(track.uploaderId, track.uploader))
                        },
                    )
                Spacer(Modifier.height(24.dp))
            }
        }
        return
    }
    when (type) {
        "queueMode" ->
            FeatureDialog("播放方式", m) {
                Column {
                    QueueMode.entries.forEach { mode ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            onClick = { m.setMode(mode) },
                            content = { Text(mode.label) },
                            leadingContent = { RadioButton(mode == s.mode, { m.setMode(mode) }) },
                        )
                    }
                }
            }
        "newFolder",
        "editFolder" ->
            FeatureDialog(
                if (type == "newFolder") "新建收藏夹" else "编辑收藏夹",
                m,
                "保存",
                { m.saveFolder() },
                true,
                s.folderDraft.isNotBlank() && !s.loading,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        s.folderDraft,
                        m::folderDraft,
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("公开", Modifier.weight(1f))
                        Switch(!s.folderPrivate, { m.folderPrivate(!it) })
                    }
                }
            }
        "favorite" ->
            AlertDialog(
                onDismissRequest = m::dismissDialog,
                title = { Text("收藏到") },
                confirmButton = { TextButton(onClick = m::dismissDialog) { Text("完成") } },
                dismissButton = {
                    TextButton(onClick = m::newFolder, enabled = !s.favoriteSaving) { Text("新建") }
                },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        s.folders
                            .filter { it.owned }
                            .forEach { folder ->
                                val selected = s.actionFavorites?.get(folder.id) == true
                                ListItem(
                                    colors =
                                        ListItemDefaults.colors(containerColor = Color.Transparent),
                                    onClick = { m.setFavorite(folder, !selected) },
                                    enabled =
                                        s.actionFavorites != null &&
                                            !s.favoriteSaving &&
                                            !s.loading,
                                    content = { Text(folder.title) },
                                    trailingContent = {
                                        Checkbox(
                                            checked = selected,
                                            onCheckedChange = { m.setFavorite(folder, it) },
                                            enabled =
                                                s.actionFavorites != null &&
                                                    !s.favoriteSaving &&
                                                    !s.loading,
                                        )
                                    },
                                )
                            }
                        if (s.folders.none { it.owned }) Text("暂无可写收藏夹")
                        if (s.actionFavorites == null) {
                            if (s.pageError != null)
                                TextButton(onClick = { s.actionTrack?.let(m::chooseFavorite) }) {
                                    Text("重试读取收藏状态")
                                }
                            else CircularWavyProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                },
            )
        "folderActions" -> {
            val folder = s.selectedFolder ?: return
            ModalBottomSheet(onDismissRequest = m::dismissDialog) {
                Text(
                    folder.title,
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (folder.owned) {
                    FeatureEntry("编辑收藏夹", Icons.Default.Edit, onClick = m::editFolder)
                    FeatureEntry(
                        "删除收藏夹",
                        Icons.Default.DeleteOutline,
                        onClick = { m.dialog("deleteFolder") },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        "subtitleLanguage" ->
            AlertDialog(
                onDismissRequest = m::dismissDialog,
                title = { Text("字幕语言") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        s.subtitleTracks.forEachIndexed { index, track ->
                            ListItem(
                                colors =
                                    ListItemDefaults.colors(containerColor = Color.Transparent),
                                onClick = {
                                    m.subtitle(index)
                                    m.dismissDialog()
                                },
                                content = {
                                    Text(track.label + if (track.automatic) " · AI" else "")
                                },
                                leadingContent = {
                                    RadioButton(
                                        s.subtitleIndex == index,
                                        {
                                            m.subtitle(index)
                                            m.dismissDialog()
                                        },
                                    )
                                },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = m::dismissDialog) { Text("关闭") } },
            )
        "coins" ->
            FeatureDialog(
                "投币",
                m,
                "确认投币",
                m::confirmCoin,
                true,
                !s.coinPosting && !s.loading && s.coinCount + s.coinAmount <= 2,
            ) {
                Column {
                    if (s.coinCount >= 2) Text("该视频已达到投币上限")
                    else
                        (1..(2 - s.coinCount)).forEach { amount ->
                            ListItem(
                                colors =
                                    ListItemDefaults.colors(containerColor = Color.Transparent),
                                onClick = { m.coinAmount(amount) },
                                content = { Text("$amount 枚硬币") },
                                leadingContent = {
                                    RadioButton(s.coinAmount == amount, { m.coinAmount(amount) })
                                },
                            )
                        }
                }
            }
        "download" ->
            FeatureDialog(
                "下载音乐",
                m,
                "下载",
                onDownload,
                true,
                s.downloadSources.isNotEmpty() && !s.loading,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        s.actionTrack?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    s.downloadSources.forEach { source ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            onClick = { m.downloadQuality(source.quality) },
                            content = { Text(source.quality) },
                            leadingContent = {
                                RadioButton(
                                    s.downloadQuality == source.quality,
                                    { m.downloadQuality(source.quality) },
                                )
                            },
                        )
                    }
                    HorizontalDivider()
                    listOf(192000, 320000).forEach { bitrate ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            onClick = { m.downloadBitrate(bitrate) },
                            content = { Text("MP3 · ${bitrate/1000} kbps") },
                            leadingContent = {
                                RadioButton(
                                    s.downloadBitrate == bitrate,
                                    { m.downloadBitrate(bitrate) },
                                )
                            },
                        )
                    }
                    if (s.downloadQuality.startsWith("Hi-Res"))
                        Text("MP3 转码不保留无损音质", style = MaterialTheme.typography.bodySmall)
                }
            }
        "clickPolicy" ->
            FeatureDialog("点击歌曲时", m) {
                Column {
                    listOf("append" to "追加并播放", "replace" to "替换并播放", "ask" to "每次询问").forEach {
                        (key, label) ->
                        val select = {
                            m.appearance(s.appearance.copy(clickPolicy = key))
                            m.dismissDialog()
                        }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            onClick = select,
                            content = { Text(label) },
                            leadingContent = {
                                RadioButton(s.appearance.clickPolicy == key, select)
                            },
                        )
                    }
                }
            }
        "queueChange" ->
            AlertDialog(
                onDismissRequest = m::dismissDialog,
                title = { Text("播放队列") },
                text = { Text("添加到现有队列，还是替换队列？") },
                confirmButton = {
                    TextButton(onClick = { m.confirmQueue(true) }) { Text("追加并播放") }
                },
                dismissButton = {
                    Column {
                        TextButton(onClick = { m.confirmQueue(false) }) { Text("替换并播放") }
                        TextButton(onClick = m::dismissDialog) { Text("取消") }
                    }
                },
            )
        else -> {
            val title =
                when (type) {
                    "deleteFolder" -> "删除收藏夹？"
                    "deleteDownloads" -> "删除所选下载？"
                    "clearHistory" -> "清除播放记录？"
                    "clearQueue" -> "清空播放队列？"
                    "logout" -> "退出当前账号？"
                    else -> "确认操作？"
                }
            FeatureDialog(
                title,
                m,
                "确认",
                onConfirm = {
                    when (type) {
                        "deleteFolder" -> m.deleteFolder()
                        "deleteDownloads" -> m.deleteDownloads()
                        "clearHistory" -> m.clearHistory()
                        "clearQueue" -> m.clearQueue()
                        "logout" -> m.logout()
                        else -> m.dismissDialog()
                    }
                },
                cancel = true,
            ) {
                Text(
                    when (type) {
                        "deleteDownloads" -> "将删除所选文件，操作无法撤销。"
                        "deleteFolder" -> "此操作会同步到哔哩哔哩。"
                        "logout" -> "本地记录和下载文件会保留。"
                        else -> "仅影响当前账号的本机记录。"
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionsDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        onClick = { onSelect(index) },
                        content = { Text(label) },
                        leadingContent = { RadioButton(index == selected, { onSelect(index) }) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun QualityDialog(state: AppState, model: BiliPlayViewModel, onDismiss: () -> Unit) =
    OptionsDialog(
        "音质",
        state.sources.map { it.quality },
        state.qualityIndex,
        {
            model.changeQuality(it)
            onDismiss()
        },
        onDismiss,
    )

@Composable
internal fun SpeedDialog(state: AppState, model: BiliPlayViewModel, onDismiss: () -> Unit) {
    val rates = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    OptionsDialog(
        "播放速度",
        rates.map { "${it}×" },
        rates.indexOf(state.speed),
        {
            model.setSpeed(rates[it])
            onDismiss()
        },
        onDismiss,
    )
}
