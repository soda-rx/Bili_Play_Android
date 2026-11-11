@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import coil3.request.crossfade
import io.github.sodarx.biliplay.core.CoverPresentation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** The app uses Material's shared expressive springs; Compose honors the system duration scale. */
@Composable
internal fun MotionRoute(
    state: AppState,
    onMainPage: (String) -> Unit,
    pageOverlay: @Composable BoxScope.(AppState) -> Unit = {},
    content: @Composable (AppState) -> Unit,
) {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = .72f, stiffness = 380f)
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val entry =
        io.github.sodarx.biliplay.core.SceneEntry.of(state.profileId, state.route, state.routeVisit)
    val latest by rememberUpdatedState(state)
    AnimatedContent(
        entry,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            (if (targetState.group == "playback" || initialState.group == "playback")
                    EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished
                else if (latest.motionBack)
                    (slideInHorizontally(spatial) { -it / 4 } +
                        fadeIn(effects, initialAlpha = .7f)) togetherWith
                        slideOutHorizontally(spatial) { it }
                else
                    slideInHorizontally(spatial) { it } togetherWith
                        (slideOutHorizontally(spatial) { -it / 4 } +
                            fadeOut(effects, targetAlpha = .7f)))
                .apply { targetContentZIndex = CoverPresentation.sceneLayer(targetState.group) }
                .using(null)
        },
        label = "native page transition",
    ) { scene ->
        var retained by remember(scene) { mutableStateOf(state) }
        val active = scene == entry
        val screen = if (active) state else retained
        SideEffect { if (active) retained = state }
        if (screen.route in CoverPresentation.primaryRoutes) {
            val pager =
                rememberPagerState(
                    initialPage = CoverPresentation.primaryRoutes.indexOf(screen.route)
                ) {
                    CoverPresentation.primaryRoutes.size
                }
            val edge = rememberPrimaryEdgeMotion(pager)
            val dragging by pager.interactionSource.collectIsDraggedAsState()
            LaunchedEffect(dragging) { edge.dragChanged(dragging) }
            // Also release after a canceled drag; a cancellation need not dispatch a fling.
            LaunchedEffect(pager.isScrollInProgress) {
                if (!pager.isScrollInProgress) edge.release()
            }
            val pageSpring = spring<Float>(dampingRatio = .8f, stiffness = 420f)
            var previousPage by remember { mutableIntStateOf(pager.settledPage) }
            val currentScreen by rememberUpdatedState(screen)
            val currentActive by rememberUpdatedState(active)
            val navigate by rememberUpdatedState(onMainPage)
            var handledNavigation by remember { mutableLongStateOf(screen.tabNavigationId) }
            LaunchedEffect(screen.route, screen.tabNavigationId) {
                val button = screen.tabNavigationId != handledNavigation
                handledNavigation = screen.tabNavigationId
                val target = CoverPresentation.primaryRoutes.indexOf(screen.route)
                if (
                    io.github.sodarx.biliplay.core.PageInertia.shouldNavigate(
                        pager.currentPage,
                        target,
                        pager.currentPageOffsetFraction,
                        button,
                        dragging,
                    )
                )
                    edge.navigate(pager, target, button)
            }
            LaunchedEffect(pager) {
                snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, dragging) }
                    .distinctUntilChanged()
                    .collectLatest { (_, scrolling, userDragging) ->
                        if (!scrolling && !userDragging) {
                            withFrameNanos {}
                            if (pager.isScrollInProgress || dragging) return@collectLatest
                            val settled = pager.settledPage
                            if (settled != previousPage)
                                edge.settled(
                                    previousPage,
                                    settled,
                                    CoverPresentation.primaryRoutes.lastIndex,
                                )
                            previousPage = settled
                            val route = CoverPresentation.primaryRoutes[settled]
                            if (currentActive && currentScreen.route != route) navigate(route)
                        }
                    }
            }
            val baseConfiguration = LocalViewConfiguration.current
            val responsiveConfiguration =
                remember(baseConfiguration) {
                    object : ViewConfiguration by baseConfiguration {
                        override val touchSlop: Float
                            get() = baseConfiguration.touchSlop * .65f
                    }
                }
            CompositionLocalProvider(LocalViewConfiguration provides responsiveConfiguration) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().nestedScroll(edge.connection),
                    overscrollEffect = null,
                    flingBehavior =
                        PagerDefaults.flingBehavior(
                            pager,
                            snapAnimationSpec = pageSpring,
                            snapPositionalThreshold = .18f,
                        ),
                    beyondViewportPageCount = 1,
                    key = { CoverPresentation.primaryRoutes[it] },
                ) { index ->
                    val route = CoverPresentation.primaryRoutes[index]
                    pages.SaveableStateProvider(screen.profileId + ":" + route) {
                        val page = screen.copy(route = route, tab = route)
                        Box(Modifier.fillMaxSize().graphicsLayer { translationX = edge.offset }) {
                            content(page)
                            pageOverlay(page)
                        }
                    }
                }
            }
        } else {
            val playback = screen.route in listOf("player", "queue")
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val height = with(LocalDensity.current) { maxHeight.toPx() }
                val sheetOffset =
                    transition.animateFloat(
                        transitionSpec = {
                            if (playback) playerSpatialSpec() else snap()
                        },
                        label = "whole playback sheet",
                    ) { phase ->
                        when {
                            !playback || phase == EnterExitState.Visible -> 0f
                            phase == EnterExitState.PreEnter && screen.motionBack -> 0f
                            else -> height
                        }
                    }
                val corner =
                    transition.animateDp(label = "unfold page corners") { phase ->
                        if (phase == EnterExitState.Visible) 0.dp else 28.dp
                    }
                val motion = LocalCoverMotion.current
                val savedKey =
                    screen.profileId +
                        ":" +
                        if (playback) "playback" else scene.group + ":" + scene.visit
                DisposableEffect(savedKey) {
                    onDispose { if (!playback) pages.removeState(savedKey) }
                }
                DisposableEffect(motion, transition, playback) {
                    if (playback) {
                        motion?.playerSheet = transition
                        motion?.playerSheetOffset = sheetOffset
                    }
                    onDispose {
                        if (playback && motion?.playerSheet === transition) {
                            motion.playerSheet = null
                            motion.playerSheetOffset = null
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationY = sheetOffset.value
                        shape =
                            if (playback)
                                RoundedCornerShape(
                                    topStart = corner.value.coerceAtLeast(0.dp),
                                    topEnd = corner.value.coerceAtLeast(0.dp),
                                )
                            else
                                RoundedCornerShape(
                                    topStart = corner.value.coerceAtLeast(0.dp),
                                    bottomStart = corner.value.coerceAtLeast(0.dp),
                                )
                        clip = true
                    }
                ) {
                    if (playback) PlayerAtmosphere(LocalPlayerTones.current)
                    CompositionLocalProvider(LocalPlayerSheetOffset provides sheetOffset) {
                        pages.SaveableStateProvider(savedKey) {
                            content(screen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExpressiveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            if (pressed) .96f else 1f,
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "card press",
        )
    Card(
        modifier =
            modifier
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        shape = SongArtworkShape,
        border =
            if (selected)
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = .7f),
                )
            else null,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
            ),
        content = content,
    )
}

@Composable
internal fun MotionPlayIcon(playing: Boolean, modifier: Modifier = Modifier) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        playing,
        modifier,
        transitionSpec = {
            (fadeIn(effects) + scaleIn(spatial, initialScale = .7f)) togetherWith
                (fadeOut(effects) + scaleOut(spatial, targetScale = .7f))
        },
        label = "play pause",
    ) { active ->
        Icon(
            if (active) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (active) "暂停" else "播放",
            Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun artworkRequest(url: String): coil3.request.ImageRequest {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context, url) {
        coil3.request.ImageRequest.Builder(context).data(url).crossfade(false).build()
    }
}
