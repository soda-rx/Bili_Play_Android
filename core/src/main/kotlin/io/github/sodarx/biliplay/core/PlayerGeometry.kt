package io.github.sodarx.biliplay.core

data class FloatingGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cover: Float,
    val padding: Float,
)

object PlayerGeometry {
    /** Spatial springs may overshoot zero; layout padding must never see the raw value. */
    fun spacing(value: Float, maximum: Float): Float =
        if (!value.isFinite()) 0f
        else value.coerceIn(0f, if (maximum.isFinite()) maximum.coerceAtLeast(0f) else 0f)

    private fun finite(v: Float, fallback: Float) = if (v.isFinite()) v else fallback

    fun resolve(
        viewWidth: Float,
        viewHeight: Float,
        top: Float,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        cover: Float,
        padding: Float,
    ): FloatingGeometry {
        val vw = finite(viewWidth, 48f).coerceAtLeast(1f)
        val vh = finite(viewHeight, 48f).coerceAtLeast(1f)
        val w = finite(width, 48f).coerceIn(minOf(48f, vw), vw)
        val h = finite(height, 48f).coerceIn(minOf(48f, vh), vh)
        val p = finite(padding, 4f).coerceIn(0f, (minOf(w, h) - 1f) / 2f)
        val c = finite(cover, 40f).coerceIn(1f, (minOf(w, h) - 2 * p).coerceAtLeast(1f))
        val minY = finite(top, 0f).coerceIn(0f, (vh - h).coerceAtLeast(0f))
        return FloatingGeometry(
            finite(x, 0f).coerceIn(0f, (vw - w).coerceAtLeast(0f)),
            finite(y, minY).coerceIn(minY, (vh - h).coerceAtLeast(minY)),
            w,
            h,
            c,
            p,
        )
    }
}

/** Color extraction uses only the decoded cover's pixels, never account/user data. */
data class CoverPalette(val primary: Int, val secondary: Int)

object CoverColors {
    fun deeper(color: Int): Int {
        fun channel(shift: Int) = ((color ushr shift and 255) * .94f).toInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Full-window, opaque cover-derived atmosphere; no theme accent participates. */
    fun backdrop(palette: CoverPalette, x: Double, y: Double, dark: Boolean): Int {
        val first =
            (if (dark) .28 else .13) +
                kotlin.math.exp(-((x - .2) * (x - .2) / .28 + (y - .52) * (y - .52) / .4)) *
                    (if (dark) .18 else .10)
        val second =
            kotlin.math.exp(-((x - .92) * (x - .92) / .22 + (y - .12) * (y - .12) / .55)) *
                (if (dark) .22 else .12)
        val neutral = if (dark) 24.0 else 246.0
        fun channel(shift: Int): Int {
            val a = palette.primary ushr shift and 255
            val b = palette.secondary ushr shift and 255
            return (neutral * (1 - first - second) + a * first + b * second)
                .toInt()
                .coerceIn(if (dark) 16 else 192, if (dark) 120 else 250)
        }
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun palette(pixels: IntArray): CoverPalette? {
        val first = dominant(pixels) ?: return null
        fun difference(pixel: Int): Int =
            kotlin.math.abs((pixel ushr 16 and 255) - (first ushr 16 and 255)) +
                kotlin.math.abs((pixel ushr 8 and 255) - (first ushr 8 and 255)) +
                kotlin.math.abs((pixel and 255) - (first and 255))
        val second = dominant(pixels.filter { difference(it) > 100 }.toIntArray()) ?: first
        return CoverPalette(first, second)
    }

    fun dominant(pixels: IntArray): Int? {
        val counts = IntArray(512)
        val red = LongArray(512)
        val green = LongArray(512)
        val blue = LongArray(512)
        for (p in pixels) {
            if ((p ushr 24) < 200) continue
            val r = p ushr 16 and 255
            val g = p ushr 8 and 255
            val b = p and 255
            val high = maxOf(r, g, b)
            val low = minOf(r, g, b)
            if (high < 28 || low > 238) continue
            val bucket = (r shr 5) * 64 + (g shr 5) * 8 + (b shr 5)
            val weight = 1 + (high - low) / 32
            counts[bucket] += weight
            red[bucket] += r.toLong() * weight
            green[bucket] += g.toLong() * weight
            blue[bucket] += b.toLong() * weight
        }
        val index = counts.indices.maxByOrNull { counts[it] } ?: return null
        val count = counts[index]
        if (count == 0) return null
        return (255 shl 24) or
            ((red[index] / count).toInt() shl 16) or
            ((green[index] / count).toInt() shl 8) or
            (blue[index] / count).toInt()
    }
}

object DiscRotation {
    fun advance(angle: Float, elapsedNanos: Long, durationScale: Float): Float {
        val safe = if (angle.isFinite()) ((angle % 360f) + 360f) % 360f else 0f
        if (!durationScale.isFinite() || durationScale <= 0f) return safe
        val elapsed = elapsedNanos.coerceIn(0L, 100_000_000L) / 1_000_000_000f
        return (safe + elapsed * 18f / durationScale.coerceAtLeast(.01f)) % 360f
    }
}
