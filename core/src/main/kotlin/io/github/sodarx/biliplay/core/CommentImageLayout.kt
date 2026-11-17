package io.github.sodarx.biliplay.core

/** Sizes are independent of density; ordinary photos keep their original aspect ratio. */
object CommentImageLayout {
    data class Size(val width: Float, val height: Float)

    fun columns(count: Int): Int = when (count) {
        0, 1 -> 1
        2, 4 -> 2
        else -> 3
    }

    fun single(width: Int, height: Int, available: Float): Size {
        val ratio = (width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)).coerceIn(.55f, 2.2f)
        val limit = when {
            ratio >= 1.5f -> 320f
            ratio > 1.05f -> 240f
            else -> 180f
        }
        val safeAvailable = if (available.isFinite()) available.coerceAtLeast(0f) else 0f
        val side = minOf(safeAvailable, limit, 260f * ratio)
        return Size(side, side / ratio)
    }
}
