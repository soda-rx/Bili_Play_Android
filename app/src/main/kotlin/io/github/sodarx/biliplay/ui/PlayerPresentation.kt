@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.sodarx.biliplay.core.CoverDestination
import io.github.sodarx.biliplay.core.CoverPresentation
import kotlinx.coroutines.launch

@Composable
internal fun PlayerNavigation(s: AppState, m: BiliPlayViewModel) {
    if (s.account == null) return
    val visible = s.route in CoverPresentation.primaryRoutes
    val reveal =
        animateFloatAsState(
            if (visible) 1f else 0f,
            MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "navigation reveal",
        )
    val motion = LocalCoverMotion.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    NavigationBar(
        Modifier.graphicsLayer { alpha = reveal.value }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .onGloballyPositioned { coordinates ->
                motion?.navigationHeight = with(density) { coordinates.size.height.toDp() }
            }
    ) {
        NavigationBarItem(
            enabled = visible,
            selected = s.tab == "home",
            onClick = { m.selectTab("home") },
            icon = { Icon(Icons.Default.Home, null) },
            label = { Text("首页") },
        )
        NavigationBarItem(
            enabled = visible,
            selected = s.tab == "favorites",
            onClick = { m.selectTab("favorites") },
            icon = { Icon(Icons.Default.FavoriteBorder, null) },
            label = { Text("收藏") },
        )
        NavigationBarItem(
            enabled = visible,
            selected = s.tab == "mine",
            onClick = { m.selectTab("mine") },
            icon = { Icon(Icons.Default.PersonOutline, null) },
            label = { Text("我的") },
        )
    }
}

/** App-local floating player, with a manually minimized top island. */
@Composable
internal fun BoxScope.PlayerIsland(
    s: AppState,
    m: BiliPlayViewModel,
) {
    val track = s.current ?: return
    if (s.account == null || s.route !in CoverPresentation.playerBarRoutes) return
    val motion = LocalCoverMotion.current
    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val dragSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val displayTitle = songTitle(track.title)
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val spec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.Dp>()
        val toolbarY = motion?.toolbarCenter(s.route)
        val alignedTop =
            toolbarY?.let { with(density) { it.toDp() } - 24.dp }
                ?: (status + if (s.route == "home") 12.dp else 8.dp)
        val navHeight =
            if (s.route in CoverPresentation.primaryRoutes)
                motion?.navigationHeight ?: (80.dp + bottom)
            else bottom
        val phase = updateTransition(s.islandMinimized, label = "dock and capsule")
        val x by
            phase.animateDp(
                transitionSpec = {
                    if (motion?.capsuleInPager == true && s.islandMinimized) snap() else spec
                },
                label = "island x",
            ) {
                if (it) maxWidth - 60.dp else 12.dp
            }
        val y by
            phase.animateDp(
                transitionSpec = {
                    if (motion?.capsuleInPager == true && s.islandMinimized) snap() else spec
                },
                label = "island y",
            ) {
                if (it) alignedTop else (maxHeight - navHeight - 88.dp).coerceAtLeast(status)
            }
        val width by
            phase.animateDp(
                transitionSpec = {
                    if (motion?.capsuleInPager == true && s.islandMinimized) snap() else spec
                },
                label = "island width",
            ) {
                if (it) 48.dp else maxWidth - 24.dp
            }
        val height by
            phase.animateDp(
                transitionSpec = {
                    if (motion?.capsuleInPager == true && s.islandMinimized) snap() else spec
                },
                label = "island height",
            ) {
                if (it) 48.dp else 76.dp
            }
        val cover by
            phase.animateDp(
                transitionSpec = {
                    if (motion?.capsuleInPager == true && s.islandMinimized) snap() else spec
                },
                label = "island cover",
            ) {
                if (it) 40.dp else 52.dp
            }
        val imagePadding by
            phase.animateDp(
                transitionSpec = {
                    if (motion?.capsuleInPager == true && s.islandMinimized) snap() else spec
                },
                label = "circle image padding",
            ) {
                if (it) 4.dp else 12.dp
            }
        val safeY = y.coerceAtLeast(status + 4.dp)
        val dockedInPage =
            s.islandMinimized &&
                s.route in CoverPresentation.primaryRoutes &&
                (motion?.capsuleInPager == true ||
                    (phase.currentState && phase.targetState && !phase.isRunning))
        SideEffect { motion?.capsuleInPager = dockedInPage }
        val geometry =
            io.github.sodarx.biliplay.core.PlayerGeometry.resolve(
                maxWidth.value,
                maxHeight.value,
                status.value + 4f,
                x.value,
                safeY.value + with(density) { dragOffset.toDp().value },
                width.value,
                height.value,
                cover.value,
                imagePadding.value,
            )
        Surface(
            enabled = !dockedInPage,
            onClick = {
                if (s.islandMinimized) m.minimizeIsland(false)
                else {
                    motion?.freeze()
                    m.navigate("player")
                }
            },
            modifier =
                Modifier.offset(geometry.x.dp, geometry.y.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        if (dockedInPage) layout(0, 0) {}
                        else layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .then(if (dockedInPage) Modifier.clearAndSetSemantics {} else Modifier)
                    .width(geometry.width.dp)
                    .height(geometry.height.dp)
                    .pointerInput(s.islandMinimized, track.key) {
                        if (s.islandMinimized) return@pointerInput
                        var distance = 0f
                        val velocity = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = {
                                settling?.cancel()
                                distance = dragOffset
                                velocity.resetTracking()
                            },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                distance = (distance + amount).coerceIn(-160.dp.toPx(), 0f)
                                velocity.addPosition(
                                    change.uptimeMillis,
                                    androidx.compose.ui.geometry.Offset(0f, distance),
                                )
                                dragOffset = distance
                            },
                            onDragEnd = {
                                if (
                                    distance < -28.dp.toPx() ||
                                        velocity.calculateVelocity().y < -700f
                                )
                                    m.minimizeIsland(true)
                                settling = scope.launch {
                                    animate(dragOffset, 0f, animationSpec = dragSpec) { value, _ ->
                                        dragOffset = value
                                    }
                                }
                            },
                            onDragCancel = {
                                settling = scope.launch {
                                    animate(dragOffset, 0f, animationSpec = dragSpec) { value, _ ->
                                        dragOffset = value
                                    }
                                }
                            },
                        )
                    },
            color = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 6.dp,
        ) {
            PlayerGlassBackground(blurEnabled = !s.islandMinimized)
            Box(
                Modifier.fillMaxSize().padding(start = geometry.padding.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                SharedPlayerArtwork(
                    track,
                    Modifier.size(geometry.cover.dp),
                    anchor = CoverDestination.Dock,
                )
            }
            if (s.islandMinimized)
                Box(
                    Modifier.fillMaxSize()
                        .combinedClickable(
                            enabled = !dockedInPage,
                            onClick = { m.minimizeIsland(false) },
                            onLongClick = {
                                motion?.freeze()
                                m.navigate("player")
                            },
                            onLongClickLabel = "打开播放详情",
                        )
                        .semantics {
                            contentDescription = "${displayTitle}，点击还原播放条"
                            customActions =
                                listOf(
                                    CustomAccessibilityAction("打开播放详情") {
                                        motion?.freeze()
                                        m.navigate("player")
                                        true
                                    },
                                    CustomAccessibilityAction(if (s.playing) "暂停" else "播放") {
                                        m.toggle()
                                        true
                                    },
                                )
                        }
                )
            AnimatedVisibility(
                !s.islandMinimized,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.size(geometry.cover.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        AnimatedVisibility(
                            !s.islandMinimized,
                            enter =
                                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                    expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
                            exit =
                                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                    shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
                        ) {
                            Text(
                                track.uploader,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    IconButton(onClick = m::toggle, enabled = !s.islandMinimized) {
                        MotionPlayIcon(s.playing)
                    }
                    if (!s.islandMinimized)
                        IconButton(onClick = { m.next() }, enabled = !s.islandMinimized) {
                            Icon(Icons.Default.SkipNext, "下一首")
                        }
                    IconButton(onClick = { m.minimizeIsland(!s.islandMinimized) }) {
                        Icon(
                            if (s.islandMinimized) Icons.Default.ExpandMore
                            else Icons.Default.ExpandLess,
                            if (s.islandMinimized) "还原播放条" else "最小化为顶部灵动岛",
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun WaveSeekBar(
    position: Long,
    duration: Long,
    playing: Boolean,
    onPreview: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val end = duration.coerceAtLeast(1).toFloat()
    var preview by remember { mutableStateOf<Float?>(null) }
    val slider = key(end) { rememberSliderState(trackRange = 0f..end) }
    slider.value = preview ?: position.toFloat().coerceIn(0f, end)
    val amplitude by
        animateFloatAsState(
            if (playing) 1f else 0f,
            MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "seek wave",
        )
    Slider(
        state = slider,
        enabled = duration > 0,
        onValueChange = {
            preview = it
            onPreview(it)
        },
        onValueChangeFinished = {
            preview?.let { onSeek(it.toLong()) }
            preview = null
            onPreview(null)
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "播放进度" },
        thumb = {
            Box(
                Modifier.width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        },
        track = { value ->
            LinearWavyProgressIndicator(
                progress = { (value.value / end).coerceIn(0f, 1f) },
                amplitude = { amplitude },
                waveSpeed = if (playing) 28.dp else 0.dp,
                modifier = Modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics {},
            )
        },
    )
}

/** Insets are sampled by layout, so squeezing the toolbar never recomposes the whole route. */
@Composable
internal fun rememberPlayerToolbarPadding(
    reservation: State<Dp>,
    horizontal: Dp = 0.dp,
    vertical: Dp = 0.dp,
): PaddingValues =
    remember(reservation, horizontal, vertical) {
        object : PaddingValues {
            private fun space() =
                io.github.sodarx.biliplay.core.PlayerGeometry.spacing(reservation.value.value, 64f)
                    .dp

            override fun calculateLeftPadding(layoutDirection: LayoutDirection) =
                horizontal + if (layoutDirection == LayoutDirection.Rtl) space() else 0.dp

            override fun calculateRightPadding(layoutDirection: LayoutDirection) =
                horizontal + if (layoutDirection == LayoutDirection.Ltr) space() else 0.dp

            override fun calculateTopPadding() = vertical

            override fun calculateBottomPadding() = vertical
        }
    }
