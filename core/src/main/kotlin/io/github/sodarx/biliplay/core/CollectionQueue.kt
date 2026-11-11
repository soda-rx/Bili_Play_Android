package io.github.sodarx.biliplay.core

object CollectionQueue {
    /** Collection order is retained while selecting the clicked song, not the first song. */
    fun tracks(clicked: Track, video: Video?, fallback: List<Track>): List<Track> {
        val selected = video?.parts?.firstOrNull { it.page == clicked.page } ?: clicked
        val related =
            video?.collection?.takeIf { it.isNotEmpty() } ?: video?.parts?.takeIf { it.size > 1 }
        val list = related ?: fallback
        return (if (list.any { it.bvid == selected.bvid && it.page == selected.page }) list
            else listOf(selected) + list)
            .map { if (it.bvid == selected.bvid && it.page == selected.page) selected else it }
            .distinctBy { it.key }
    }
}
