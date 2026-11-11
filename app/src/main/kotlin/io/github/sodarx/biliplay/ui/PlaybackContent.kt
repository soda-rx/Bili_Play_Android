@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Queue geometry has its own clock; data updates and cover state never restart or consume it. */
@Composable
internal fun PlaybackContent(
    s: AppState,
    m: BiliPlayViewModel,
    quality: () -> Unit,
    speed: () -> Unit,
) {
    val visible = s.route == "queue"
    val slide = remember(s.profileId, s.playerSession) { Animatable(1f) }
    var attached by remember(s.profileId, s.playerSession) { mutableStateOf(false) }
    LaunchedEffect(slide, visible, if (visible) s.routeVisit else -1L) {
        if (visible) {
            attached = true
            slide.animateTo(0f, playerSpatialSpec())
        } else if (attached) {
            slide.animateTo(1f, playerSpatialSpec())
            attached = false
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (visible) 0f else 1f }
                .then(if (visible || attached) Modifier.clearAndSetSemantics {} else Modifier)
        ) {
            key(s.profileId, s.playerSession) {
                FullPlayer(
                    if (attached && !visible) s.copy(route = "queue") else s,
                    m,
                    quality,
                    speed,
                )
            }
        }
        if (attached) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationY = size.height * slide.value }
                    .pointerInput(Unit) { detectTapGestures {} }
            ) {
                QueueScreen(s, m)
            }
        }
    }
}
