package io.github.sodarx.biliplay.core

/** Queue positions identify occurrences, including intentionally repeated songs. */
object QueueMembership {
    fun current(queue: List<Track>, candidate: Track?): Track? = candidate?.let { selected ->
        queue.firstOrNull { it.key == selected.key }
    }

    fun restoredIndex(queue: List<Track>, candidate: Track?, saved: Int): Int {
        val selected = current(queue, candidate) ?: return -1
        return if (saved in queue.indices && queue[saved].key == selected.key) saved
        else queue.indexOfFirst { it.key == selected.key }
    }

    fun afterRemoval(size: Int, current: Int, removed: Int): Int {
        if (current !in 0 until size) return -1
        if (removed !in 0 until size) return current
        if (size == 1) return -1
        return when {
            current == removed -> removed.coerceAtMost(size - 2)
            current > removed -> current - 1
            else -> current
        }
    }

    fun afterMove(current: Int, from: Int, to: Int): Int =
        when {
            current < 0 -> -1
            current == from -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }

    fun displayKeys(queue: List<Track>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return queue.map { track ->
            val occurrence = counts.getOrDefault(track.key, 0)
            counts[track.key] = occurrence + 1
            "${track.key}#$occurrence"
        }
    }
}
