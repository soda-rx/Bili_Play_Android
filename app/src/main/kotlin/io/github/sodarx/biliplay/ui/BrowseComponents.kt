@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.core.Track
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FeatureTrackRow(
    track: Track,
    model: BiliPlayViewModel,
    onPlay: () -> Unit = { model.play(track) },
) {
    ListItem(
        onClick = onPlay,
        onLongClick = { model.dialog("trackActions", track) },
        onLongClickLabel = "歌曲操作",
        modifier = Modifier,
        content = { Text(songTitle(track.title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (track.part.isBlank()) track.uploader
                else "${track.uploader} · P${track.page} ${track.part}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Artwork(track, 48) },
        trailingContent = {
            IconButton(onClick = { model.dialog("trackActions", track) }) {
                Icon(Icons.Default.MoreVert, "歌曲操作")
            }
        },
    )
}

@Composable
internal fun Artwork(track: Track, size: Int) {
    SongArtwork(track, Modifier.size(size.dp), compact = size < 64)
}

@Composable
internal fun FeatureEntry(
    title: String,
    icon: ImageVector,
    value: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    inlineValue: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        content = { Text(title) },
        supportingContent = value?.takeUnless { inlineValue }?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (inlineValue && value != null)
                    Text(
                        value,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        },
    )
}

@Composable
internal fun EmptyContent(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
internal fun LoadMore(visible: Boolean, state: AppState, model: BiliPlayViewModel) {
    if (!visible) return
    val view = androidx.compose.ui.platform.LocalView.current
    var onScreen by remember { mutableStateOf(false) }
    val count =
        when (state.route) {
            "home" -> state.recommendations.size
            "search" -> state.results.size + state.uploaders.size
            "folder" -> state.folderTracks.size
            "uploader" -> state.uploaderTracks.size
            "social" -> state.socialUsers.size
            "comments" -> state.comments.size
            "replies" -> state.replies.size
            "notifications" -> state.notifications.size
            "conversation" -> state.conversationMessages.size
            else -> 0
        }
    var requested by remember(state.profileId, state.route) { mutableStateOf<Int?>(null) }
    val busy = state.loading || state.homeRefreshing
    LaunchedEffect(onScreen, busy, count, state.pageError) {
        if (onScreen && !busy && state.pageError == null && requested != count) {
            requested = count
            model.more()
        }
    }
    Box(
        Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned {
            val rect = it.boundsInWindow()
            onScreen = rect.height > 0 && rect.bottom > 0 && rect.top < view.height
        },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularWavyProgressIndicator(Modifier.size(24.dp))
        else if (state.pageError != null)
            IconButton(onClick = { model.more() }) { Icon(Icons.Default.Refresh, "重试载入") }
    }
}

internal fun safeBili(value: String): Boolean {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    val host = uri.host.orEmpty()
    return uri.scheme == "https" &&
        uri.port in listOf(-1, 443) &&
        uri.userInfo == null &&
        (host == "bilibili.com" || host.endsWith(".bilibili.com"))
}

internal fun date(value: Long) =
    if (value <= 0) ""
    else
        runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(Math.multiplyExact(value, 1000L)))
            }
            .getOrDefault("")
