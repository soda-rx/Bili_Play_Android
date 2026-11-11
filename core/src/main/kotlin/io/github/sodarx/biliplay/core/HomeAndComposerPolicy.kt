package io.github.sodarx.biliplay.core

enum class HomeTabAction {
    ScrollToTop,
    Refresh,
    None;

    companion object {
        fun isReselection(current: String, target: String): Boolean =
            current == "home" && target == "home"

        fun choose(
            canScrollBackward: Boolean,
            refreshing: Boolean,
        ): HomeTabAction = if (canScrollBackward) ScrollToTop else if (refreshing) None else Refresh
    }
}

object EmojiPanelLayout {
    fun targetHeight(viewport: Float): Float {
        val screen = viewport.takeIf { it.isFinite() && it > 0f } ?: 640f
        val maximum = (screen * .45f).coerceAtLeast(160f)
        return 280f.coerceIn(minOf(200f, maximum), maximum)
    }

    fun remaining(reserved: Int, ime: Int, available: Int): Int =
        (reserved.coerceAtLeast(0).toLong() - ime.coerceAtLeast(0))
            .coerceIn(0L, available.coerceAtLeast(0).toLong())
            .toInt()
}
