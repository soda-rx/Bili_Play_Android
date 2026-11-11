package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class CoverMorphRegressionTest {
    @Test
    fun hiddenLyricsCannotShrinkFromStaleLargeArtwork() {
        assertFalse(
            CoverPresentation.shouldMorph(
                CoverDestination.Hidden,
                CoverDestination.Dock,
                true,
                false,
            )
        )
        assertFalse(
            CoverPresentation.shouldMorph(
                CoverDestination.Hidden,
                CoverDestination.Player,
                true,
                false,
            )
        )
    }

    @Test
    fun visiblePlayerAndDockStillShareTheArtworkTransition() {
        assertTrue(
            CoverPresentation.shouldMorph(
                CoverDestination.Player,
                CoverDestination.Dock,
                true,
                false,
            )
        )
        assertTrue(
            CoverPresentation.shouldMorph(
                CoverDestination.Dock,
                CoverDestination.Player,
                true,
                false,
            )
        )
        assertFalse(
            CoverPresentation.shouldMorph(
                CoverDestination.Player,
                CoverDestination.Dock,
                false,
                false,
            )
        )
    }

    @Test
    fun interruptedTransitionCanReverseButIdleGeometryDoesNotReplay() {
        assertTrue(
            CoverPresentation.shouldMorph(CoverDestination.Dock, CoverDestination.Dock, true, true)
        )
        assertFalse(
            CoverPresentation.shouldMorph(CoverDestination.Dock, CoverDestination.Dock, true, false)
        )
        assertFalse(
            CoverPresentation.shouldMorph(
                CoverDestination.Player,
                CoverDestination.Hidden,
                true,
                true,
            )
        )
    }
}
