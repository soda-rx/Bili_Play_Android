package io.github.sodarx.biliplay.core

object PlaybackRestore {
    fun duration(saved: Long, trackSeconds: Long) =
        saved.takeIf { it > 0 } ?: (trackSeconds.coerceAtLeast(0) * 1000)

    fun position(saved: Long, duration: Long) =
        saved.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
}

object LyricLayout {
    const val LeadMs = 300L

    fun active(lines: List<SubtitleLine>, positionMs: Long) =
        SubtitlePolicy.active(lines, (positionMs + LeadMs).coerceAtLeast(0) / 1000.0)

    fun targetTop(viewport: Int, itemHeight: Int): Int {
        val edge = (viewport * .06f).toInt()
        val extra = (itemHeight * .08f).toInt()
        return (viewport * .27f - itemHeight / 2f)
            .toInt()
            .coerceIn(edge + extra, maxOf(edge + extra, viewport - itemHeight - edge - extra))
    }
}

object AccountRemoval {
    fun allowed(guest: Boolean, loggedIn: Boolean) = !guest && !loggedIn
}

object DesktopMusicRotation {
    fun group(page: Int, rotation: Int) = Math.floorMod(page - 1 + rotation, 3)

    fun archivePage(page: Int, rotation: Int) = (page - 1 + rotation) / 15 + 1

    fun offset(page: Int, rotation: Int) = ((page - 1 + rotation) / 3 % 5) * 6

    fun interleave(batches: List<List<Track>>, offset: Int): List<Track> =
        (0..5).flatMap { i -> batches.mapNotNull { it.getOrNull(offset + i) } }
}
