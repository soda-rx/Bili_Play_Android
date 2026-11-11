@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.sodarx.biliplay.core.CoverColors
import io.github.sodarx.biliplay.core.Track

internal val LocalNowPlaying = staticCompositionLocalOf<String?> { null }

internal val LocalPlayerTones =
    staticCompositionLocalOf<List<State<Color>>> { listOf(mutableStateOf(Color(0xff707070))) }

@Composable
internal fun rememberArtworkPalette(track: Track?): io.github.sodarx.biliplay.core.CoverPalette? {
    val context = LocalContext.current
    var palette by remember { mutableStateOf<io.github.sodarx.biliplay.core.CoverPalette?>(null) }
    LaunchedEffect(track?.cover) {
        palette =
            try {
                track?.cover?.takeIf { it.isNotBlank() }?.let { sampleCover(context, it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
    }
    return palette
}

@Composable
internal fun artworkTones(
    palette: io.github.sodarx.biliplay.core.CoverPalette?
): List<State<Color>> {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val actual =
        palette
            ?: io.github.sodarx.biliplay.core.CoverPalette(0xff707070.toInt(), 0xff707070.toInt())
    val points = listOf(.9 to .1, .2 to .5, .5 to 1.0)
    val states = points.map { (x, y) ->
        val raw = CoverColors.backdrop(actual, x, y, dark)
        val color = raw
        animateColorAsState(
            Color(color),
            MaterialTheme.motionScheme.slowEffectsSpec(),
            label = "artwork surface tone",
        )
    }
    return remember(states) { states }
}

@Composable
internal fun PlayerAtmosphere(tones: List<State<Color>>) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.verticalGradient(tones.map { it.value }))
        }
    )
}

@Composable
internal fun Modifier.playerActionBackground(): Modifier {
    val lower = LocalPlayerTones.current.last()
    return drawBehind {
        val color = lower.value
        drawRect(
            Brush.verticalGradient(
                listOf(lerp(color, Color.Black, .025f), Color(CoverColors.deeper(color.toArgb())))
            )
        )
    }
}

@Composable
internal fun ExpressivePlayButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by
        animateDpAsState(
            if (pressed) 16.dp else if (playing) 22.dp else 30.dp,
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "play button shape",
        )
    val scale by
        animateFloatAsState(
            if (pressed) .91f else 1f,
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "play button press",
        )
    val haptic = LocalHapticFeedback.current
    FilledIconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier =
            Modifier.size(60.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
    ) {
        if (buffering)
            CircularWavyProgressIndicator(
                Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = .18f),
            )
        else MotionPlayIcon(playing, Modifier.size(30.dp))
    }
}

@Composable
internal fun MotionSelectionIcon(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    description: String,
) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    androidx.compose.animation.AnimatedContent(
        selected,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(effects) +
                androidx.compose.animation.scaleIn(spatial, initialScale = .75f)) togetherWith
                (androidx.compose.animation.fadeOut(effects) +
                    androidx.compose.animation.scaleOut(spatial, targetScale = .75f))
        },
        label = "selection feedback",
    ) { active ->
        Icon(
            if (active) selectedIcon else unselectedIcon,
            description,
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

@Composable
internal fun ExpressiveSnackbarHost(state: SnackbarHostState, bottomInset: Dp = 0.dp) {
    SnackbarHost(state, modifier = Modifier.padding(bottom = bottomInset)) { data ->
        val message = data.visuals.message
        val failed = listOf("失败", "无法", "错误", "过期", "受限").any { it in message }
        val succeeded = !failed && (message.startsWith("已") || "完成" in message)
        val colors = MaterialTheme.colorScheme
        val container =
            if (failed) colors.errorContainer
            else if (succeeded) colors.primaryContainer else colors.surfaceContainerHighest
        val foreground =
            if (failed) colors.onErrorContainer
            else if (succeeded) colors.onPrimaryContainer else colors.onSurface
        val label = data.visuals.actionLabel
        Snackbar(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = container,
            contentColor = foreground,
            action =
                if (label == null) null
                else {
                    { TextButton(onClick = data::performAction) { Text(label) } }
                },
            dismissAction =
                if (!data.visuals.withDismissAction) null
                else {
                    { IconButton(onClick = data::dismiss) { Icon(Icons.Default.Close, "关闭提示") } }
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (failed) Icons.Default.ErrorOutline
                    else if (succeeded) Icons.Default.CheckCircle else Icons.Default.Info,
                    null,
                )
                Text(
                    message,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Alpha-only edge fade, so lyrics keep their actual background and uniform inactive color. */
internal fun Modifier.lyricEdgeFade(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                .07f to Color.Black,
                .90f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
