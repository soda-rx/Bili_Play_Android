package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class CoverOwnershipTest {
    @Test
    fun openingFromTheVisibleDockUsesItsFrozenSourceImmediately() {
        assertTrue(
            CoverPresentation.playerOverlayVisible(
                CoverDestination.Player,
                CoverDestination.Dock,
                false,
                true,
                false,
            )
        )
    }

    @Test
    fun restingPlayerNeverLeavesAGlobalImageOverThePager() {
        assertFalse(
            CoverPresentation.playerOverlayVisible(
                CoverDestination.Player,
                CoverDestination.Player,
                false,
                false,
                false,
            )
        )
    }

    @Test
    fun mediaSwipeHidesTheGlobalCoverEvenDuringAnInterruptedTransfer() {
        assertFalse(
            CoverPresentation.playerOverlayVisible(
                CoverDestination.Player,
                CoverDestination.Dock,
                true,
                true,
                true,
            )
        )
    }

    @Test
    fun hiddenLyricsAndMissingSourcesDoNotInventAnExpansion() {
        assertFalse(
            CoverPresentation.playerOverlayVisible(
                CoverDestination.Hidden,
                CoverDestination.Player,
                false,
                true,
                false,
            )
        )
        assertFalse(
            CoverPresentation.playerOverlayVisible(
                CoverDestination.Player,
                CoverDestination.Dock,
                false,
                false,
                false,
            )
        )
    }
}
