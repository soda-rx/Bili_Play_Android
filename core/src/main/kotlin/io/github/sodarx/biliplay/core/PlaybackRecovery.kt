package io.github.sodarx.biliplay.core

object PlaybackRecovery {
    fun canSkipApi(code: Int): Boolean = code in setOf(-1, -404, 404, 410, 62002, 62004, 62012)

    /** One recovery chain tries each distinct song at most once, including in repeat modes. */
    fun next(queue: List<Track>, index: Int, failed: Set<String>, mode: QueueMode, random: Int = 0): Int? {
        if (queue.isEmpty()) return null
        val later = ((index + 1).coerceAtLeast(0) until queue.size).toList()
        val earlier = if (mode == QueueMode.Sequential) emptyList()
            else (0..index.coerceAtMost(queue.lastIndex)).toList()
        val eligible = (later + earlier).filter { queue[it].key !in failed }
        if (eligible.isEmpty()) return null
        return if (mode == QueueMode.Shuffle) eligible[Math.floorMod(random, eligible.size)] else eligible.first()
    }
}
