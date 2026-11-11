package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackUiPolicyTest {
    @Test
    fun playingClockDoesNotInvalidatePageStateButTransportAndTrackChangesDo() {
        repeat(100) { assertFalse(PlaybackUiPolicy.capturePosition("A", "A", true, true)) }
        assertTrue(PlaybackUiPolicy.capturePosition("A", "B", true, true))
        assertTrue(PlaybackUiPolicy.capturePosition("A", "A", true, false))
        assertTrue(PlaybackUiPolicy.capturePosition("A", "A", false, true))
        assertTrue(PlaybackUiPolicy.capturePosition("A", null, true, false))
    }

    @Test
    fun pausedAndRestoredProgressRemainsAvailableToContinueListening() {
        assertTrue(PlaybackUiPolicy.capturePosition(null, "A", false, false))
        assertTrue(PlaybackUiPolicy.capturePosition("A", "A", false, false))
    }

    @Test
    fun highestRefreshRequestPreservesTheCurrentResolution() {
        val modes =
            listOf(
                DisplayRateOption(1080, 2400, 60f),
                DisplayRateOption(1080, 2400, 120f),
                DisplayRateOption(2160, 4800, 144f),
            )
        assertEquals(1, DisplayRatePolicy.best(1080, 2400, modes))
        assertEquals(0, DisplayRatePolicy.best(1080, 2400, modes.take(1)))
    }

    @Test
    fun unsupportedOrInvalidModesDoNotProduceARefreshRequest() {
        assertNull(DisplayRatePolicy.best(1080, 2400, emptyList()))
        assertNull(
            DisplayRatePolicy.best(
                1080,
                2400,
                listOf(DisplayRateOption(1, 1, 120f), DisplayRateOption(1080, 2400, Float.NaN)),
            )
        )
    }
}
