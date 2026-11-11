package io.github.sodarx.biliplay.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal val LocalGlassBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

internal fun playerGlassBorder(): Brush =
    Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = .36f),
            Color.White.copy(alpha = .04f),
            Color.White.copy(alpha = .18f),
        )
    )

@Composable
internal fun BoxScope.PlayerGlassBackground(blurEnabled: Boolean) {
    val source = LocalGlassBackdrop.current
    val origin = remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { 16.dp.toPx() }
    val blur =
        remember(radius) {
            if (Build.VERSION.SDK_INT >= 31) BlurEffect(radius, radius, TileMode.Clamp) else null
        }
    val shape = RoundedCornerShape(28.dp)
    val border = remember { playerGlassBorder() }
    val tint = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        Modifier.matchParentSize().clip(shape).onGloballyPositioned {
            origin.value = it.positionInRoot()
        }
    ) {
        if (blurEnabled && blur != null && source != null)
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { renderEffect = blur }
                    .drawWithContent {
                        translate(-origin.value.x, -origin.value.y) { drawLayer(source) }
                    }
            )
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.verticalGradient(listOf(tint.copy(alpha = .68f), tint.copy(alpha = .52f)))
                )
                .border(
                    1.dp,
                    border,
                    shape,
                )
        )
    }
}
