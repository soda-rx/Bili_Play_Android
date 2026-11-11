@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.sodarx.biliplay.core.CoverPresentation
import kotlinx.coroutines.flow.distinctUntilChanged

/** A native, finger-tracked queue. Adjacent artwork and metadata are composed before release. */
@Composable
internal fun FullPlayer(
    s: AppState,
    m: BiliPlayViewModel,
    onQuality: () -> Unit,
    onSpeed: () -> Unit,
) {
    val current = s.current
    if (current == null) {
        EmptyContent("播放列表为空")
        return
    }
    val tracks =
        remember(s.queue, current.key) {
            s.queue.takeIf { q -> q.any { it.key == current.key } } ?: listOf(current)
        }
    val playingIndex =
        io.github.sodarx.biliplay.core.QueueMembership.restoredIndex(tracks, current, s.queueIndex)
            .coerceAtLeast(0)
    val displayKeys =
        remember(tracks) { io.github.sodarx.biliplay.core.QueueMembership.displayKeys(tracks) }
    val pageCount by rememberUpdatedState(tracks.size)
    val pager = remember { PagerState(currentPage = playingIndex) { pageCount } }
    val latest by rememberUpdatedState(s)
    val latestTracks by rememberUpdatedState(tracks)
    var switching by remember { mutableStateOf(false) }
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    var userPaging by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { if (dragging) userPaging = true }
    val motion = LocalCoverMotion.current
    val shared = !pager.isScrollInProgress && !switching && pager.settledPage == playingIndex
    val owner = remember { Any() }
    DisposableEffect(motion, owner) {
        motion?.let { it.playerPagingState = it.playerPagingState.claim(owner) }
        onDispose { motion?.let { it.playerPagingState = it.playerPagingState.release(owner) } }
    }
    SideEffect {
        motion?.let { it.playerPagingState = it.playerPagingState.update(owner, !shared) }
    }

    LaunchedEffect(current.key, tracks, playingIndex, switching) {
        if (!switching && !pager.isScrollInProgress && pager.currentPage != playingIndex)
            pager.scrollToPage(playingIndex)
    }
    LaunchedEffect(pager) {
        snapshotFlow {
            Triple(pager.settledPage, pager.isScrollInProgress || dragging, latest.route)
        }
            .distinctUntilChanged()
            .collect { (index, scrolling, route) ->
                val commit = CoverPresentation.shouldCommitPage(userPaging, scrolling, route)
                if (!scrolling) userPaging = false
                val target = latestTracks.getOrNull(index)
                if (commit && !switching && target != null && index != latest.queueIndex) {
                    switching = true
                    try {
                        if (!m.switchPlayerTrack(target, index)) {
                            val old =
                                io.github.sodarx.biliplay.core.QueueMembership.restoredIndex(
                                        latestTracks,
                                        latest.current,
                                        latest.queueIndex,
                                    )
                                    .coerceAtLeast(0)
                            pager.animateScrollToPage(old)
                        }
                    } finally {
                        switching = false
                    }
                }
            }
    }
    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        beyondViewportPageCount = 1,
        userScrollEnabled = !switching && s.route == "player" && tracks.size > 1,
        key = { displayKeys[it] },
    ) { index ->
        val track = tracks[index]
        val active = index == playingIndex
        val page =
            if (active) s
            else
                s.copy(
                    current = track,
                    playerLyrics = false,
                    position = 0,
                    duration = track.duration * 1000,
                    playing = false,
                    buffering = false,
                    favorite = false,
                    currentFollowing = false,
                )
        Box(
            Modifier.fillMaxSize().then(if (active) Modifier else Modifier.clearAndSetSemantics {})
        ) {
            PlayerPageContent(
                page,
                m,
                shared && active,
                active,
                onQuality,
                onSpeed,
            )
            if (!active) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures {} })
        }
    }
}
