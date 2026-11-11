package io.github.sodarx.biliplay.core

object PageInertia {
    fun edgePull(current: Float, unconsumed: Float, limit: Float): Float =
        if (!current.isFinite() || !unconsumed.isFinite() || !limit.isFinite() || limit <= 0f) 0f
        else
            (current +
                    unconsumed * .28f * (1f - kotlin.math.abs(current) / limit).coerceIn(.15f, 1f))
                .coerceIn(-limit, limit)

    fun arrivalDirection(from: Int, to: Int, last: Int): Int =
        when {
            from == to || from !in 0..last || to !in 0..last -> 0
            to < from -> 1
            else -> -1
        }

    fun arrivalVelocity(
        from: Int,
        to: Int,
        last: Int,
        releaseVelocity: Float,
        limit: Float,
        button: Boolean,
    ): Float {
        if (!limit.isFinite() || limit <= 0f || from !in 0..last || to !in 0..last) return 0f
        val direction =
            if (button && from == to)
                when (to) {
                    0 -> 1
                    last -> -1
                    else -> 0
                }
            else arrivalDirection(from, to, last)
        if (direction == 0) return 0f
        val speed =
            if (button) limit * 6f
            else
                (kotlin.math.abs(releaseVelocity.takeIf { it.isFinite() } ?: 0f) * .35f).coerceIn(
                    limit * 1.4f,
                    limit * 8f,
                )
        return direction * speed
    }

    fun shouldNavigate(
        current: Int,
        target: Int,
        offset: Float,
        button: Boolean,
        dragging: Boolean,
    ): Boolean =
        button ||
            current != target ||
            (!dragging && offset.isFinite() && kotlin.math.abs(offset) > .001f)

    data class TravelFrame(val position: Float, val offset: Float)

    fun travelFrame(start: Float, target: Float, value: Float, limit: Float): TravelFrame {
        if (
            !start.isFinite() ||
                !target.isFinite() ||
                !value.isFinite() ||
                !limit.isFinite() ||
                limit <= 0f
        )
            return TravelFrame(target.takeIf { it.isFinite() } ?: 0f, 0f)
        val position = value.coerceIn(minOf(start, target), maxOf(start, target))
        return TravelFrame(position, (position - value).coerceIn(-limit * .4f, limit * .4f))
    }
}
