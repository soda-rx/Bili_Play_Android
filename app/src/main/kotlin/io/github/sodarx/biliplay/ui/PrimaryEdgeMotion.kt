package io.github.sodarx.biliplay.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.PageInertia
import kotlinx.coroutines.*

internal class PrimaryEdgeMotion(
    private val scope: CoroutineScope,
    private val limit: Float,
    private val canStretch: (Float) -> Boolean,
) {
    private var pull by mutableFloatStateOf(0f)
    private var arrival by mutableFloatStateOf(0f)
    val offset
        get() = (pull + arrival).coerceIn(-limit, limit)

    private var settling: Job? = null
    private var dragging = false
    private var releaseVelocity = 0f
    private var navigationGeneration = 0
    private var navigationTarget: Int? = null
    private var handledTarget: Int? = null

    fun stop() {
        settling?.cancel()
    }

    fun dragChanged(value: Boolean) {
        dragging = value
        if (value) {
            stop()
            arrival = 0f
            releaseVelocity = 0f
            handledTarget = null
        }
    }

    fun release() {
        if (pull == 0f || settling?.isActive == true) return
        settling = scope.launch {
            animate(pull, 0f, animationSpec = spring(dampingRatio = .72f, stiffness = 300f)) {
                value,
                _ ->
                pull = value.coerceIn(-limit, limit)
            }
            pull = 0f
        }
    }

    private fun kick(from: Int, to: Int, last: Int, button: Boolean) {
        val velocity = PageInertia.arrivalVelocity(from, to, last, releaseVelocity, limit, button)
        releaseVelocity = 0f
        if (velocity == 0f || (dragging && !button)) return
        stop()
        val initial = (arrival + pull).coerceIn(-limit * .4f, limit * .4f)
        pull = 0f
        settling = scope.launch {
            animate(
                initial,
                0f,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = .68f, stiffness = 300f),
            ) { value, _ ->
                arrival = value.coerceIn(-limit * .4f, limit * .4f)
            }
            arrival = 0f
        }
    }

    fun settled(from: Int, to: Int, last: Int) {
        if (navigationTarget != null) return
        if (handledTarget == to) {
            handledTarget = null
            return
        }
        kick(from, to, last, false)
    }

    suspend fun navigate(pager: PagerState, target: Int, button: Boolean) {
        if (target !in 0 until pager.pageCount) return
        val generation = ++navigationGeneration
        val from = pager.currentPage
        val extent = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing).toFloat()
        if (extent <= 0f) {
            pager.scrollToPage(target)
            return
        }
        val start = (pager.currentPage + pager.currentPageOffsetFraction) * extent
        val destination = target * extent
        if (kotlin.math.abs(start - destination) < .5f) {
            if (button) kick(from, target, pager.pageCount - 1, true)
            return
        }
        navigationTarget = target
        handledTarget = target
        stop()
        pull = 0f
        arrival = 0f
        releaseVelocity = 0f
        var completed = false
        try {
            pager.scroll {
                with(pager) { updateTargetPage(target) }
                var position = start
                animate(
                    start,
                    destination,
                    animationSpec = spring(dampingRatio = .72f, stiffness = 380f),
                ) { value, _ ->
                    val frame = PageInertia.travelFrame(start, destination, value, limit)
                    position += scrollBy(frame.position - position)
                    arrival = frame.offset
                }
                with(pager) { updateCurrentPage(target, 0f) }
                arrival = 0f
            }
            completed = true
        } finally {
            if (navigationGeneration == generation) {
                navigationTarget = null
                if (!completed) {
                    handledTarget = null
                    arrival = 0f
                }
            }
        }
    }

    val connection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!dragging || source != NestedScrollSource.UserInput || available.x == 0f)
                    return Offset.Zero
                stop()
                arrival = 0f
                if (!canStretch(available.x) && pull != 0f) {
                    val next = pull + available.x * .28f
                    pull = if (pull * available.x < 0f && next * pull > 0f) next else 0f
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    !dragging ||
                        source != NestedScrollSource.UserInput ||
                        available.x == 0f ||
                        !canStretch(available.x)
                )
                    return Offset.Zero
                stop()
                pull = PageInertia.edgePull(pull, available.x, limit)
                return Offset(available.x, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                releaseVelocity = available.x
                release()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                release()
                return Velocity.Zero
            }
        }
}

@Composable
internal fun rememberPrimaryEdgeMotion(pager: PagerState): PrimaryEdgeMotion {
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 72.dp.toPx() }
    val edge =
        remember(pager, limit) {
            PrimaryEdgeMotion(scope, limit) { x ->
                if (x > 0f) !pager.canScrollBackward else !pager.canScrollForward
            }
        }
    DisposableEffect(edge) { onDispose { edge.stop() } }
    return edge
}
