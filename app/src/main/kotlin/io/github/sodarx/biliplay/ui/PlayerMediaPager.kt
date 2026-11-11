@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.sodarx.biliplay.core.CoverPresentation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Composable
internal fun PlayerMediaPager(
    s: AppState,
    m: BiliPlayViewModel,
    sharedArtwork: Boolean,
    active: Boolean,
    modifier: Modifier,
) {
    val track = s.current ?: return
    // ViewModel owns the selected mode; a second saveable copy can restore stale lyrics.
    val pager = remember { PagerState(currentPage = if (active && s.playerLyrics) 1 else 0) { 2 } }
    val latest by rememberUpdatedState(s)
    val foregroundRouteFlow = remember(m) { m.state.map { it.route }.distinctUntilChanged() }
    val foregroundRoute by foregroundRouteFlow.collectAsStateWithLifecycle(initialValue = s.route)
    val motion = LocalCoverMotion.current
    var demonstrating by remember { mutableStateOf(false) }
    var demonstration by remember { mutableStateOf<Job?>(null) }
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (active) {
        var userPaging by remember { mutableStateOf(false) }
        val owner = remember { Any() }
        DisposableEffect(motion, owner) {
            motion?.let { it.mediaPagingState = it.mediaPagingState.claim(owner) }
            onDispose { motion?.let { it.mediaPagingState = it.mediaPagingState.release(owner) } }
        }
        SideEffect {
            motion?.let {
                it.mediaPagingState =
                    it.mediaPagingState.update(
                        owner,
                        pager.isScrollInProgress || pager.settledPage != 0,
                    )
            }
        }
        LaunchedEffect(s.playerLyrics) {
            val target = if (s.playerLyrics) 1 else 0
            if (!demonstrating && !pager.isScrollInProgress && pager.currentPage != target)
                pager.scrollToPage(target)
        }
        LaunchedEffect(pager) {
            snapshotFlow {
                Triple(
                    pager.settledPage,
                    pager.isScrollInProgress || demonstrating || dragging,
                    latest.route,
                )
            }
                .distinctUntilChanged()
                .collect { (page, scrolling, route) ->
                    val commit = CoverPresentation.shouldCommitPage(userPaging, scrolling, route)
                    if (!scrolling) userPaging = false
                    if (commit && (page == 1) != latest.playerLyrics)
                        m.setPlayerLyrics(page == 1, latest.playerSession, track.key)
                }
        }
        // Only a real finger takeover cancels the hero; restoring a pager position must not.
        LaunchedEffect(dragging, motion) {
            if (dragging) {
                userPaging = true
                motion?.finishPlayerTransfer(owner)
            }
        }
        LaunchedEffect(dragging) {
            if (dragging && demonstration != null) {
                demonstration?.cancel()
                m.finishPlayerDemo()
            }
        }
        LaunchedEffect(s.playerDemoPending, foregroundRoute, track.key, lifecycle) {
            if (
                !s.playerDemoPending ||
                    foregroundRoute != "player" ||
                    lifecycle != Lifecycle.State.RESUMED
            )
                return@LaunchedEffect
            val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (!scale.isFinite() || scale <= 0f) {
                m.finishPlayerDemo()
                return@LaunchedEffect
            }
            val ownJob = currentCoroutineContext().job
            demonstration = ownJob
            try {
                snapshotFlow { motion?.moving != true && motion?.playerSheet?.isRunning != true }
                    .first { it }
                delay((450 * scale).toLong())
                demonstrating = true
                pager.animateScrollToPage(
                    1,
                    animationSpec = tween(650, easing = FastOutSlowInEasing),
                )
                delay((550 * scale).toLong())
                pager.animateScrollToPage(
                    0,
                    animationSpec = tween(650, easing = FastOutSlowInEasing),
                )
                m.finishPlayerDemo()
            } finally {
                if (demonstrating) m.finishPlayerDemo()
                demonstrating = false
                if (demonstration === ownJob) demonstration = null
            }
        }
    }
    @Composable
    fun cover(shared: Boolean) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            val context = LocalContext.current
            val request =
                remember(context, track.cover) {
                    ImageRequest.Builder(context)
                        .data(track.cover)
                        .size(768)
                        .crossfade(false)
                        .build()
                }
            Box(Modifier.size(side).clip(SongArtworkShape)) {
                AsyncImage(
                    request,
                    null,
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (shared && motion?.playerOverlayVisible == true) 0f else 1f
                    },
                    contentScale = ContentScale.Crop,
                )
                if (shared) SharedPlayerArtwork(track, Modifier.matchParentSize())
            }
        }
    }
    Box(modifier) {
        if (!active) cover(false)
        else
            HorizontalPager(
                state = pager,
                modifier =
                    Modifier.fillMaxSize().semantics {
                        stateDescription = if (s.playerLyrics) "歌词" else "封面"
                        customActions =
                            listOf(
                                CustomAccessibilityAction(if (s.playerLyrics) "显示封面" else "显示歌词") {
                                    m.setPlayerLyrics(!s.playerLyrics, s.playerSession, track.key)
                                    true
                                }
                            )
                    },
                userScrollEnabled = s.route == "player",
                key = { it },
            ) { page ->
                if (page == 0)
                    cover(
                        sharedArtwork &&
                            !pager.isScrollInProgress &&
                            pager.settledPage == 0 &&
                            !s.playerLyrics
                    )
                else LyricsScreen(s, m)
            }
    }
}
