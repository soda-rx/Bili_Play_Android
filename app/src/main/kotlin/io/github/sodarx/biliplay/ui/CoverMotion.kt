@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import coil3.request.crossfade
import io.github.sodarx.biliplay.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal val LocalPlayerSheetOffset =
    staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

internal val LocalCoverMotion = staticCompositionLocalOf<CoverMotion?> { null }

internal class CoverMotion {
    private data class Target(val key: String, val rect: Rect?, val owner: Any?)

    private val bounds = mutableStateMapOf<CoverDestination, Target>()

    fun register(mode: CoverDestination, key: String, owner: Any) {
        bounds[mode] = Target(key, null, owner)
    }

    fun release(mode: CoverDestination, owner: Any) {
        if (bounds[mode]?.owner === owner) bounds.remove(mode)
    }

    val frame = Animatable(Rect.Zero, Rect.VectorConverter)
    var moving by mutableStateOf(false)
    var transferStartSide = 0f
    var transferTargetSide = 0f
    var presented by mutableStateOf(CoverDestination.Hidden)
    var displayRect: Rect? = null
    var playerPagingState by mutableStateOf(OwnedPagingFlag())
    var mediaPagingState by mutableStateOf(OwnedPagingFlag())
    val playerPaging
        get() = playerPagingState.paging

    val mediaPaging
        get() = mediaPagingState.paging

    private val idleDockLift = mutableFloatStateOf(0f)
    var dockLiftState: State<Float> = idleDockLift
    val dockLift: Float
        get() = dockLiftState.value

    var followDockLift by mutableStateOf(false)
    var navigationHeight by mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    var playerSheet by mutableStateOf<Transition<EnterExitState>?>(null)
    var playerSheetOffset: State<Float>? = null
    var requested by mutableStateOf(CoverDestination.Hidden)
    var capsuleInPager by mutableStateOf(false)
    var capsuleRect by mutableStateOf<Rect?>(null)
    var capsuleKey by mutableStateOf<String?>(null)

    data class FrozenSource(val rect: Rect?)

    private var source by mutableStateOf<FrozenSource?>(null)
    val playerOverlayVisible
        get() =
            CoverPresentation.playerOverlayVisible(
                requested,
                presented,
                moving,
                source?.rect != null,
                playerPaging || mediaPaging,
            )

    private val toolbarCenters = mutableStateMapOf<String, Float>()

    fun reportToolbar(route: String, center: Float) {
        if (center.isFinite()) toolbarCenters[route] = center
    }

    fun toolbarCenter(route: String) = toolbarCenters[route]

    fun visibleBounds(): Rect? {
        if (!CoverPresentation.canCaptureSource(requested, playerPaging || mediaPaging)) return null
        if (moving) return displayRect
        return when (presented) {
            CoverDestination.Dock ->
                if (capsuleInPager) capsuleRect
                else
                    bounds[CoverDestination.Dock]
                        ?.rect
                        ?.translate(0f, if (followDockLift) dockLift else 0f)
            CoverDestination.Player ->
                bounds[CoverDestination.Player]?.rect?.translate(0f, playerSheetOffset?.value ?: 0f)
            CoverDestination.Hidden -> null
        }
    }

    fun prepareDockRestore(rect: Rect?) {
        if (rect != null)
            bounds[CoverDestination.Dock]?.let {
                bounds[CoverDestination.Dock] = it.copy(rect = rect)
            }
    }

    fun freeze(rect: Rect? = visibleBounds()) {
        source = FrozenSource(rect)
    }

    fun frozenRect() = source?.rect

    fun takeSource(): FrozenSource? = source.also { source = null }

    fun report(mode: CoverDestination, key: String, rect: Rect, owner: Any? = null) {
        if (owner != null && bounds[mode]?.owner !== owner) return
        if (CoverPresentation.validBounds(rect.left, rect.top, rect.right, rect.bottom, 0f))
            bounds[mode] = Target(key, rect, owner)
    }

    fun target(mode: CoverDestination, key: String): Rect? =
        if (mode == CoverDestination.Dock && capsuleInPager && capsuleKey == key) capsuleRect
        else bounds[mode]?.takeIf { it.key == key }?.rect

    suspend fun finishPlayerTransfer(owner: Any) {
        if (!mediaPagingState.ownedBy(owner) || requested != CoverDestination.Player) return
        frame.stop()
        if (!mediaPagingState.ownedBy(owner) || requested != CoverDestination.Player) return
        moving = false
        presented = CoverDestination.Player
        takeSource()
    }
}

/**
 * One stable image node and decode size for dock, island and player. Only its GPU transform
 * changes.
 */
@Composable
internal fun PlayerMotionRoot(s: AppState, m: BiliPlayViewModel, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val paletteTrack = s.current ?: s.queue.firstOrNull().takeIf { s.route == "queue" }
    val artworkPalette = rememberArtworkPalette(paletteTrack)
    val neutralTone = rememberUpdatedState(MaterialTheme.colorScheme.background)
    val playerTones =
        if (paletteTrack != null) artworkTones(artworkPalette)
        else remember(neutralTone) { listOf(neutralTone, neutralTone) }
    val destination =
        if (s.route in listOf("player", "queue") && s.playerLyrics) CoverDestination.Hidden
        else
            CoverPresentation.destination(
                if (s.route == "queue") "player" else s.route,
                s.account != null,
                s.current != null,
            )
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .preferredFrameRate(FrameRateCategory.High)
            .background(MaterialTheme.colorScheme.background)
    ) {
        val motion = remember(s.profileId, maxWidth, maxHeight) { CoverMotion() }
        val rotation = remember(s.profileId) { mutableFloatStateOf(0f) }
        val lifecycle by
            androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow
                .collectAsState()
        val isCircle = s.islandMinimized && destination == CoverDestination.Dock
        val circleCorner =
            animateFloatAsState(
                if (isCircle) 50f else 20f,
                MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "album circle shape",
            )
        val turnOffSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        LaunchedEffect(isCircle, s.playing, s.current?.key, lifecycle) {
            if (isCircle) {
                if (!s.playing || lifecycle != androidx.lifecycle.Lifecycle.State.RESUMED)
                    return@LaunchedEffect
                var previous = withFrameNanos { it }
                while (isActive) {
                    val frame = withFrameNanos { it }
                    val scale =
                        kotlinx.coroutines
                            .currentCoroutineContext()[androidx.compose.ui.MotionDurationScale]
                            ?.scaleFactor ?: 1f
                    if (scale <= 0f || !scale.isFinite()) return@LaunchedEffect
                    rotation.floatValue =
                        DiscRotation.advance(rotation.floatValue, frame - previous, scale)
                    previous = frame
                }
            } else if (rotation.floatValue != 0f) {
                val finish = kotlin.math.round(rotation.floatValue / 360f) * 360f
                animate(rotation.floatValue, finish, animationSpec = turnOffSpec) { value, _ ->
                    rotation.floatValue = value
                }
                rotation.floatValue = 0f
            }
        }
        val backdrop = rememberGraphicsLayer()
        val captureBackdrop =
            s.current != null && !s.islandMinimized && s.route in CoverPresentation.playerBarRoutes
        val track = s.current
        SideEffect { motion.requested = destination }
        val motionRoute = if (s.route in listOf("player", "queue")) "playback" else s.route
        LaunchedEffect(motion, destination, track?.key, motionRoute, s.playerSession) {
            if (destination == CoverDestination.Hidden || track == null) {
                motion.moving = false
                motion.presented = CoverDestination.Hidden
                motion.takeSource()
                return@LaunchedEffect
            }
            val frozen = motion.takeSource()
            val captured = if (frozen != null) frozen.rect else motion.visibleBounds()
            motion.followDockLift =
                destination == CoverDestination.Dock && motion.presented == CoverDestination.Hidden
            val from =
                if (
                    CoverPresentation.shouldMorph(
                        motion.presented,
                        destination,
                        captured != null,
                        motion.moving,
                    )
                )
                    captured
                else null
            motion.transferStartSide = from?.width ?: 0f
            motion.transferTargetSide = 0f
            if (from != null) {
                motion.moving = true
                motion.frame.snapTo(from)
            }
            withFrameNanos {}
            val endpoint =
                withTimeoutOrNull(1000) {
                    snapshotFlow { motion.target(destination, track.key) }.first { it != null }
                }
            if (endpoint != null) {
                motion.transferTargetSide = endpoint.width
                if (from != null)
                    motion.frame.animateTo(
                        endpoint,
                        playerSpatialSpec(),
                    )
                else motion.frame.snapTo(endpoint)
            }
            if (destination == CoverDestination.Player && from != null && endpoint != null) {
                // Keep the global artwork until the sheet really stops, not its first zero
                // crossing; releasing during overshoot would move the cover after landing.
                withTimeoutOrNull(1600) {
                    snapshotFlow { motion.playerSheet?.isRunning == false }.first { it }
                }
            }
            motion.presented = destination
            motion.moving = false
        }
        CompositionLocalProvider(
            LocalPlayerTones provides playerTones,
            LocalGlassBackdrop provides backdrop,
            LocalPlayerContentBottom provides
                if (
                    s.current != null &&
                        !s.islandMinimized &&
                        s.route in CoverPresentation.playerBarRoutes
                )
                    88.dp
                else 0.dp,
            LocalDiscRotation provides rotation,
            LocalCoverMotion provides motion,
            LocalNowPlaying provides s.current?.key,
            LocalPlaybackActive provides s.playing,
            LocalSmartTitles provides s.appearance.smartSongTitles,
        ) {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    if (captureBackdrop) {
                        backdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(backdrop)
                    } else drawContent()
                }
            ) {
                content()
            }
            PlayerDockLayer(s, m)
        }
        if (track != null) {
            val context = LocalContext.current
            val request =
                remember(track.cover) {
                    coil3.request.ImageRequest.Builder(context)
                        .data(track.cover)
                        .size(768)
                        .crossfade(false)
                        .build()
                }
            val base = with(density) { 300.dp.toPx() }
            val rimBrush = remember { playerGlassBorder() }
            val rimTint = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .7f)
            Canvas(Modifier.fillMaxSize()) {
                if (destination == CoverDestination.Dock && s.islandMinimized && motion.moving) {
                    val rect = motion.frame.value
                    val progress =
                        CoverPresentation.capsuleRimProgress(
                            motion.transferStartSide,
                            rect.width,
                            motion.transferTargetSide,
                        )
                    if (progress > 0f) {
                        val gap = 4.dp.toPx() * progress
                        val left = rect.left - gap
                        val top = rect.top - gap
                        val side = rect.width + 2 * gap
                        inset(left, top, size.width - left - side, size.height - top - side) {
                            val stroke = 1.dp.toPx()
                            drawRoundRect(
                                rimTint,
                                cornerRadius = CornerRadius(side / 2),
                                alpha = progress,
                            )
                            drawRoundRect(
                                rimBrush,
                                topLeft = Offset(stroke / 2, stroke / 2),
                                size =
                                    Size(
                                        (side - stroke).coerceAtLeast(0f),
                                        (side - stroke).coerceAtLeast(0f),
                                    ),
                                cornerRadius = CornerRadius((side - stroke) / 2),
                                style = Stroke(stroke),
                                alpha = progress,
                            )
                        }
                    }
                }
            }
            AsyncImage(
                request,
                null,
                Modifier.layout { measurable, _ ->
                        val side = base.toInt()
                        val placeable = measurable.measure(Constraints.fixed(side, side))
                        layout(0, 0) { placeable.place(0, 0) }
                    }
                    .size(300.dp)
                    .graphicsLayer {
                        val baseRect =
                            when {
                                motion.moving -> motion.frame.value
                                destination == CoverDestination.Player &&
                                    motion.presented == CoverDestination.Dock -> motion.frozenRect()
                                else -> motion.target(destination, track.key)
                            }
                        val rect =
                            if (destination == CoverDestination.Dock && motion.followDockLift)
                                baseRect?.translate(0f, motion.dockLift)
                            else baseRect
                        if (rect == null || rect.width <= 0f || rect.height <= 0f) {
                            alpha = 0f
                            return@graphicsLayer
                        }
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = rect.left
                        translationY = rect.top
                        scaleX = rect.width / base
                        scaleY = rect.height / base
                        shape =
                            RoundedCornerShape(percent = circleCorner.value.toInt().coerceIn(0, 50))
                        clip = true
                        shadowElevation =
                            with(density) {
                                (if (destination == CoverDestination.Player) 10.dp else 0.dp).toPx()
                            }
                        ambientShadowColor =
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = .12f)
                        spotShadowColor =
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = .18f)
                        alpha =
                            if (
                                CoverPresentation.foregroundArtworkVisible(
                                    destination,
                                    motion.playerOverlayVisible,
                                    motion.moving,
                                    motion.capsuleInPager,
                                    s.route == "queue" || motion.playerPaging || motion.mediaPaging,
                                )
                            )
                                1f
                            else 0f
                        if (
                            destination == CoverDestination.Dock &&
                                motion.followDockLift &&
                                !motion.moving
                        )
                            alpha *=
                                (1f - motion.dockLift / with(density) { 24.dp.toPx() }).coerceIn(
                                    0f,
                                    1f,
                                )
                        if (alpha > 0f) motion.displayRect = rect
                    }
                    .drawWithContent {
                        rotate(rotation.floatValue, pivot = center) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun SharedPlayerArtwork(
    track: Track,
    modifier: Modifier,
    visible: Boolean? = null,
    anchor: CoverDestination = CoverDestination.Player,
) {
    val motion = LocalCoverMotion.current
    val sheetOffset = LocalPlayerSheetOffset.current
    val owner = remember { Any() }
    DisposableEffect(motion, anchor, track.key) {
        motion?.register(anchor, track.key, owner)
        onDispose { motion?.release(anchor, owner) }
    }
    Box(
        modifier.onGloballyPositioned {
            if (visible != false)
                motion?.report(
                    anchor,
                    track.key,
                    it.positionInRoot().let { position ->
                        val side = minOf(it.size.width, it.size.height).toFloat()
                        val lift =
                            if (anchor == CoverDestination.Dock) motion?.dockLift ?: 0f else 0f
                        val top =
                            if (anchor == CoverDestination.Player)
                                CoverPresentation.playerAnchorTop(position.y, sheetOffset.value)
                            else position.y - lift
                        Rect(position.x, top, position.x + side, top + side)
                    },
                    owner,
                )
        }
    )
}
