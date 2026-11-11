@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import io.github.sodarx.biliplay.core.Folder
import kotlinx.coroutines.*

private data class FolderFrame(val id: Long, val index: Int, val bounds: Rect)

private class FolderDrag(
    val frames: () -> List<FolderFrame>,
    val height: () -> Int,
    val scope: CoroutineScope,
    val scroll: suspend (Float) -> Float,
    val edge: Float,
    val slop: Float,
    val move: (Long, Long) -> Unit,
    val save: () -> Unit,
    val menu: (Long) -> Unit,
    val haptic: () -> Unit,
) {
    var id by mutableStateOf<Long?>(null)
    var offset by mutableStateOf(Offset.Zero)
    var ignoreClicksUntil = 0L
    private var finger = Offset.Zero
    private var origin = Offset.Zero
    private var moved = false
    private var expectedIndex: Int? = null
    private var scrolling: Job? = null

    fun begin(key: Long, local: Offset) {
        val frame = frames().firstOrNull { it.id == key } ?: return
        id = key
        offset = Offset.Zero
        moved = false
        expectedIndex = null
        finger = frame.bounds.topLeft + local
        origin = finger
        haptic()
        scrolling = scope.launch {
            while (isActive && id != null) {
                val amount =
                    when {
                        finger.y < edge -> -12f
                        finger.y > height() - edge -> 12f
                        else -> 0f
                    }
                if (moved && amount != 0f) {
                    val consumed = scroll(amount)
                    offset += Offset(0f, consumed)
                    place()
                }
                delay(16)
            }
        }
    }

    fun drag(delta: Offset) {
        finger += delta
        offset += delta
        moved = moved || (finger - origin).getDistance() > slop
        if (moved) place()
    }

    private fun place() {
        val key = id ?: return
        val all = frames()
        val current = all.firstOrNull { it.id == key } ?: return
        if (expectedIndex != null && current.index != expectedIndex) return
        expectedIndex = null
        val center = current.bounds.center + offset
        val target = all.firstOrNull { it.id != key && it.bounds.contains(center) } ?: return
        val top =
            if (target.index > current.index && target.bounds.left == current.bounds.left)
                Offset(target.bounds.left, target.bounds.bottom - current.bounds.height)
            else target.bounds.topLeft
        offset += current.bounds.topLeft - top
        expectedIndex = target.index
        move(key, target.id)
    }

    fun finish(cancel: Boolean = false) {
        val key = id ?: return
        scrolling?.cancel()
        ignoreClicksUntil = android.os.SystemClock.uptimeMillis() + 350
        if (moved) save() else if (!cancel) menu(key)
        id = null
        offset = Offset.Zero
        expectedIndex = null
    }
}

@Composable
internal fun ReorderableFavorites(s: AppState, m: BiliPlayViewModel, folders: List<Folder>) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val profile = s.profileId
    val currentFolders by rememberUpdatedState(folders)
    val drag =
        remember(profile) {
            FolderDrag(
                frames = {
                    list.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                        (item.key as? Long)?.let {
                            FolderFrame(
                                it,
                                item.index,
                                Rect(
                                    0f,
                                    item.offset.toFloat(),
                                    list.layoutInfo.viewportSize.width.toFloat(),
                                    (item.offset + item.size).toFloat(),
                                ),
                            )
                        }
                    }
                },
                height = { list.layoutInfo.viewportSize.height },
                scope = scope,
                scroll = { list.scrollBy(it) },
                edge = with(density) { 64.dp.toPx() },
                slop = slop,
                move = { from, to ->
                    if (m.state.value.profileId == profile) m.reorderFolder(from, to)
                },
                save = {
                    if (
                        m.state.value.profileId == profile &&
                            m.state.value.account != null &&
                            m.state.value.route == "favorites"
                    )
                        m.saveFolderOrder()
                },
                menu = { key ->
                    if (m.state.value.profileId == profile)
                        currentFolders.firstOrNull { it.id == key }?.let(m::folderActions)
                },
                haptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }
    DisposableEffect(drag) { onDispose { drag.finish(true) } }
    @Composable
    fun card(folder: Folder, modifier: Modifier) {
        LaunchedEffect(folder.id, folder.count, s.folderCovers.containsKey(folder.id)) {
            m.previewFolder(folder)
        }
        val lifted = drag.id == folder.id
        val scale by
            animateFloatAsState(
                if (lifted) 1.045f else 1f,
                MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "folder lift",
            )
        ExpressiveCard(
            onClick = {
                if (android.os.SystemClock.uptimeMillis() > drag.ignoreClicksUntil)
                    m.openFolder(folder)
            },
            modifier =
                modifier
                    .fillMaxWidth()
                    .zIndex(if (lifted) 2f else 0f)
                    .graphicsLayer {
                        translationX = if (lifted) drag.offset.x else 0f
                        translationY = if (lifted) drag.offset.y else 0f
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = if (lifted) 10.dp.toPx() else 0f
                    }
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction("更多操作") {
                                    m.folderActions(folder)
                                    true
                                },
                                CustomAccessibilityAction("上移") {
                                    m.moveFolder(folder, -1)
                                    true
                                },
                                CustomAccessibilityAction("下移") {
                                    m.moveFolder(folder, 1)
                                    true
                                },
                            )
                    }
                    .pointerInput(folder.id, drag) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { drag.begin(folder.id, it) },
                            onDragEnd = { drag.finish() },
                            onDragCancel = { drag.finish(true) },
                            onDrag = { change, amount ->
                                change.consume()
                                drag.drag(amount)
                            },
                        )
                    },
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(8.dp)) {
                val coverWidth = (maxWidth * .34f).coerceIn(96.dp, 136.dp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LargeFolderPreview(
                        s.folderCovers[folder.id].orEmpty(),
                        Modifier.width(coverWidth).aspectRatio(1.55f),
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            folder.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (folder.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                                null,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                " · ${folder.count} 首",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalIconButton(
                                onClick = { m.playFolder(folder) },
                                enabled = folder.count > 0 && !s.loading,
                            ) {
                                Icon(Icons.Default.PlayArrow, "播放${folder.title}")
                            }
                        }
                    }
                }
            }
        }
    }
    LazyColumn(
        state = list,
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 16.dp + LocalPlayerContentBottom.current,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(folders, key = { it.id }) {
            card(
                it,
                Modifier.animateItem(
                    placementSpec =
                        if (drag.id == it.id) null
                        else MaterialTheme.motionScheme.defaultSpatialSpec()
                ),
            )
        }
    }
}

/** Shares the favorite-list drag controller, including edge scroll and release menus. */
@Composable
internal fun ReorderableQueue(s: AppState, m: BiliPlayViewModel) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val profile = s.profileId
    val ids = remember(profile) { mutableMapOf<String, Long>() }
    val counter = remember(profile) { longArrayOf(0) }
    val occurrences = mutableMapOf<String, Int>()
    val keys =
        s.queue.map { track ->
            val occurrence = occurrences[track.key] ?: 0
            occurrences[track.key] = occurrence + 1
            ids.getOrPut("${track.key}:$occurrence") { ++counter[0] }
        }
    val currentKeys by rememberUpdatedState(keys)
    val currentQueue by rememberUpdatedState(s.queue)
    val drag =
        remember(profile) {
            FolderDrag(
                frames = {
                    list.layoutInfo.visibleItemsInfo.mapNotNull { row ->
                        (row.key as? Long)?.let {
                            FolderFrame(
                                it,
                                row.index,
                                Rect(
                                    0f,
                                    row.offset.toFloat(),
                                    list.layoutInfo.viewportSize.width.toFloat(),
                                    (row.offset + row.size).toFloat(),
                                ),
                            )
                        }
                    }
                },
                height = { list.layoutInfo.viewportSize.height },
                scope = scope,
                scroll = { list.scrollBy(it) },
                edge = with(density) { 64.dp.toPx() },
                slop = slop,
                move = { from, to ->
                    val a = currentKeys.indexOf(from)
                    val b = currentKeys.indexOf(to)
                    if (m.state.value.profileId == profile && a >= 0 && b >= 0) m.dragQueue(a, b)
                },
                save = { if (m.state.value.profileId == profile) m.saveQueueOrder() },
                menu = { key ->
                    currentQueue.getOrNull(currentKeys.indexOf(key))?.let {
                        m.dialog("trackActions", it)
                    }
                },
                haptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }
    DisposableEffect(drag) { onDispose { drag.finish(true) } }
    LazyColumn(state = list) {
        item(key = "queue-header") {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${s.queue.size} 首")
                TextButton(onClick = m::cycleMode) { Text(s.mode.label) }
            }
        }
        items(s.queue.size, key = { keys[it] }) { index ->
            val track = s.queue[index]
            val token = keys[index]
            val lifted = drag.id == token
            val lift by
                animateFloatAsState(
                    if (lifted) 1.025f else 1f,
                    MaterialTheme.motionScheme.fastSpatialSpec(),
                    label = "queue lift",
                )
            ListItem(
                colors =
                    ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                onClick = {
                    if (android.os.SystemClock.uptimeMillis() > drag.ignoreClicksUntil)
                        m.playFromQueue(track, index)
                },
                modifier =
                    Modifier.animateItem(
                            placementSpec =
                                if (lifted) null
                                else MaterialTheme.motionScheme.defaultSpatialSpec()
                        )
                        .zIndex(if (lifted) 2f else 0f)
                        .graphicsLayer {
                            translationX = if (lifted) drag.offset.x else 0f
                            translationY = if (lifted) drag.offset.y else 0f
                            scaleX = lift
                            scaleY = lift
                            shadowElevation = if (lifted) 8.dp.toPx() else 0f
                        }
                        .semantics {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction("上移") {
                                        m.moveQueue(currentKeys.indexOf(token), -1)
                                        true
                                    },
                                    CustomAccessibilityAction("下移") {
                                        m.moveQueue(currentKeys.indexOf(token), 1)
                                        true
                                    },
                                )
                        }
                        .pointerInput(token, drag) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { drag.begin(token, it) },
                                onDragEnd = { drag.finish() },
                                onDragCancel = { drag.finish(true) },
                                onDrag = { change, delta ->
                                    change.consume()
                                    drag.drag(delta)
                                },
                            )
                        },
                content = {
                    Text(songTitle(track.title), maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { Text(track.uploader) },
                leadingContent = {
                    CompositionLocalProvider(
                        LocalNowPlaying provides s.current?.key.takeIf { index == s.queueIndex }
                    ) {
                        Artwork(track, 40)
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            val i = currentKeys.indexOf(token)
                            if (i >= 0) m.removeQueue(i)
                        }
                    ) {
                        Icon(Icons.Default.Close, "移出队列")
                    }
                },
            )
        }
        if (s.queue.isEmpty()) item { EmptyContent("暂无播放内容") }
    }
}
