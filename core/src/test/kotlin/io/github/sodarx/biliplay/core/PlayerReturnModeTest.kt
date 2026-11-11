package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PlayerReturnModeTest {
    @Test
    fun queueAndCommentsReturnToExactlyThePreviousMediaPage() {
        for (from in listOf("queue", "comments", "replies")) {
            assertTrue(CoverPresentation.lyricsAfterNavigation(from, "player", true))
            assertFalse(CoverPresentation.lyricsAfterNavigation(from, "player", false))
        }
    }

    @Test
    fun freshPlayerVisitsStartOnArtworkButLeavingDoesNotInventAnArtworkSource() {
        assertFalse(CoverPresentation.lyricsAfterNavigation("home", "player", true))
        assertFalse(CoverPresentation.lyricsAfterNavigation("favorites", "player", true))
        assertTrue(CoverPresentation.lyricsAfterNavigation("player", "home", true))
    }

    @Test
    fun capsuleReturnKeepsTheForegroundUntilNativeArtworkHandoff() {
        fun visible(destination: CoverDestination, moving: Boolean, transfer: Boolean = false) =
            CoverPresentation.foregroundArtworkVisible(destination, transfer, moving, true, false)
        assertFalse(visible(CoverDestination.Dock, false))
        assertTrue(visible(CoverDestination.Player, true, true))
        assertFalse(visible(CoverDestination.Player, false))
        assertTrue(visible(CoverDestination.Dock, true))
        assertFalse(visible(CoverDestination.Dock, false))
    }

    @Test
    fun leavingLyricsRestoresTheDockEvenThoughTheMediaSelectionIsRemembered() {
        assertTrue(CoverPresentation.lyricsAfterNavigation("player", "home", true))
        assertFalse(
            CoverPresentation.foregroundArtworkVisible(
                CoverDestination.Hidden,
                false,
                false,
                false,
                false,
            )
        )
        val dock = CoverPresentation.destination("home", true, true)
        assertTrue(CoverPresentation.foregroundArtworkVisible(dock, false, false, false, false))
        // The exiting lyrics pager remains composed while its sheet moves down.
        assertTrue(CoverPresentation.foregroundArtworkVisible(dock, false, false, false, true))
        assertFalse(
            CoverPresentation.foregroundArtworkVisible(
                CoverDestination.Player,
                true,
                true,
                false,
                true,
            )
        )
    }
}
