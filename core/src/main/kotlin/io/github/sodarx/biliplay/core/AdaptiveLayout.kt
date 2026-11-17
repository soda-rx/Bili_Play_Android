package io.github.sodarx.biliplay.core

object AdaptiveLayout {
    fun homeColumns(width: Float): Int =
        if (!width.isFinite()) 2 else ((width - 16f) / 196f).toInt().coerceIn(2, 8)

    fun splitPlayer(width: Float, height: Float): Boolean =
        width.isFinite() && height.isFinite() && width >= 720f && height >= 360f

    fun dragTravel(height: Float, top: Float, dock: Float): Float =
        if (!height.isFinite() || !top.isFinite() || !dock.isFinite()) 0f
        else (dock - top).coerceIn(0f, height.coerceAtLeast(0f))
}
