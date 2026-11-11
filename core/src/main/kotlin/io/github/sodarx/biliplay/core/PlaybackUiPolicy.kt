package io.github.sodarx.biliplay.core

object PlaybackUiPolicy {
    fun capturePosition(
        previousKey: String?,
        nextKey: String?,
        wasPlaying: Boolean,
        playing: Boolean,
    ): Boolean = previousKey != nextKey || wasPlaying != playing || !playing
}

data class DisplayRateOption(val width: Int, val height: Int, val rate: Float)

object DisplayRatePolicy {
    fun best(width: Int, height: Int, modes: List<DisplayRateOption>): Int? =
        modes.indices
            .filter {
                modes[it].width == width &&
                    modes[it].height == height &&
                    modes[it].rate.isFinite() &&
                    modes[it].rate > 0f
            }
            .maxByOrNull { modes[it].rate }
}
