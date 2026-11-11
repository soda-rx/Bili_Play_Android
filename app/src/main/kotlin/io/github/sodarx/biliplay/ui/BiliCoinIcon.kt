package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Bilibili-style coin silhouette, redrawn as a rounded single-tone Material icon. */
@Composable
internal fun BiliCoinIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp).semantics { contentDescription = "投币" }) {
        val unit = size.minDimension / 24f
        drawCircle(color, 9f * unit, Offset(12f * unit, 12f * unit), style = Stroke(1.8f * unit))
        val path =
            Path().apply {
                moveTo(8f * unit, 8f * unit)
                lineTo(16f * unit, 7f * unit)
                moveTo(12f * unit, 8f * unit)
                lineTo(12f * unit, 18f * unit)
                moveTo(8f * unit, 15f * unit)
                lineTo(8f * unit, 11f * unit)
                lineTo(16f * unit, 11f * unit)
                lineTo(16f * unit, 15f * unit)
            }
        drawPath(path, color, style = Stroke(1.6f * unit, cap = StrokeCap.Round))
    }
}
