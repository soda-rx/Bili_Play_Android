@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.CoverPresentation

internal val LocalPlayerContentBottom = staticCompositionLocalOf { 0.dp }

/** A transparent overlay; only the visible player surface handles input. */
@Composable
internal fun BoxScope.PlayerDockLayer(s: AppState, m: BiliPlayViewModel) {
    if (s.current == null || s.account == null) return
    val visible = s.route in CoverPresentation.playerBarRoutes
    var lastRoute by remember(s.profileId) { mutableStateOf(s.tab) }
    val motion = LocalCoverMotion.current
    val restoring = visible && motion?.playerSheet != null
    SideEffect { if (visible) lastRoute = s.route }
    val travel = with(LocalDensity.current) { 24.dp.toPx() }
    AnimatedVisibility(
        visible,
        modifier = Modifier.fillMaxSize(),
        enter =
            fadeIn(
                if (restoring) playerSpatialSpec() else MaterialTheme.motionScheme.fastEffectsSpec()
            ),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        val lift =
            transition.animateFloat(
                transitionSpec = {
                    playerSpatialSpec()
                },
                label = "dock rise and sink",
            ) { phase ->
                if (phase == EnterExitState.Visible) 0f else travel
            }
        SideEffect { motion?.dockLiftState = lift }
        DisposableEffect(motion) { onDispose { motion?.dockLiftState = mutableFloatStateOf(0f) } }
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = lift.value }) {
            PlayerIsland(s.copy(route = if (visible) s.route else lastRoute), m)
        }
    }
}
