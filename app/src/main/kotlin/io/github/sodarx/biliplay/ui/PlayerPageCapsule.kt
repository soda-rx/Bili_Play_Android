@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.sodarx.biliplay.core.CoverPresentation

/** Native pager child: its capsule follows the same transform as the rest of the page. */
@Composable
internal fun BoxScope.PlayerPageCapsule(s: AppState, m: BiliPlayViewModel) {
    val motion = LocalCoverMotion.current ?: return
    val track = s.current ?: return
    val displayTitle = songTitle(track.title)
    if (!s.islandMinimized || !motion.capsuleInPager || s.account == null) return
    val settled =
        CoverPresentation.capsuleSurfaceVisible(motion.requested, motion.presented, motion.moving)
    val density = LocalDensity.current
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top =
        (motion.toolbarCenter(s.route)?.let { with(density) { it.toDp() } - 24.dp }
                ?: (status + if (s.route == "home") 12.dp else 8.dp))
            .coerceAtLeast(status + 4.dp)
    val context = LocalContext.current
    val request =
        remember(context, track.cover) {
            ImageRequest.Builder(context).data(track.cover).size(768).build()
        }
    val rotation = LocalDiscRotation.current
    val border = remember { BorderStroke(1.dp, playerGlassBorder()) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun bounds(): Rect? =
        coordinates
            ?.takeIf { it.isAttached }
            ?.let {
                val p = it.positionInRoot()
                Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
            }
    fun selectPage() {
        if (m.state.value.route != s.route) m.navigate(s.route)
    }
    Surface(
        Modifier.align(Alignment.TopEnd)
            .padding(top = top, end = 12.dp)
            .size(48.dp)
            .graphicsLayer {
                val lift = if (motion.followDockLift) motion.dockLift else 0f
                translationY = lift
                alpha =
                    if (settled) (1f - lift / with(density) { 24.dp.toPx() }).coerceIn(0f, 1f)
                    else 0f
            }
            .then(if (settled) Modifier else Modifier.clearAndSetSemantics {})
            .combinedClickable(
                enabled = settled,
                onClick = {
                    selectPage()
                    motion.prepareDockRestore(bounds())
                    m.minimizeIsland(false)
                },
                onLongClick = {
                    selectPage()
                    motion.freeze(bounds())
                    m.navigate("player")
                },
                onLongClickLabel = "打开播放详情",
            )
            .semantics { contentDescription = "${displayTitle}，点击还原播放条" },
        shape = CircleShape,
        border = border,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .7f),
        shadowElevation = 2.dp,
    ) {
        AsyncImage(
            request,
            null,
            Modifier.padding(4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .onGloballyPositioned {
                    coordinates = it
                    if (m.state.value.route == s.route) {
                        motion.capsuleRect =
                            bounds()
                                ?.translate(0f, if (motion.followDockLift) -motion.dockLift else 0f)
                        motion.capsuleKey = track.key
                    }
                }
                .drawWithContent { rotate(rotation.value) { this@drawWithContent.drawContent() } },
            contentScale = ContentScale.Crop,
        )
    }
}
