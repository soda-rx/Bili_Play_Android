package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackReentryRegressionTest {
    @Test
    fun leavingLyricsAndReopeningCreatesAFreshCoverSession() {
        assertTrue(CoverPresentation.isNewPlayerEntry("home", "player"))
        assertFalse(CoverPresentation.lyricsAfterNavigation("home", "player", true))
        assertFalse(CoverPresentation.isNewPlayerEntry("player", "home"))
        assertTrue(CoverPresentation.isNewPlayerEntry("home", "player"))
        assertFalse(CoverPresentation.lyricsAfterNavigation("home", "player", true))
        assertTrue(
            CoverPresentation.shouldMorph(
                CoverDestination.Player,
                CoverDestination.Dock,
                true,
                false,
            )
        )
    }

    @Test
    fun queueAndCommentReturnsKeepTheExistingMediaMode() {
        for (route in listOf("player", "queue", "comments", "replies")) {
            assertFalse(CoverPresentation.isNewPlayerEntry(route, "player"))
            assertTrue(CoverPresentation.lyricsAfterNavigation(route, "player", true))
        }
    }

    @Test
    fun anOldPagerCannotChangeTheNewSessionsCoverMode() {
        assertFalse(CoverPresentation.acceptsMediaEvent("player", 2, "synthetic", 1, "synthetic"))
        assertTrue(CoverPresentation.acceptsMediaEvent("player", 2, "synthetic", 2, "synthetic"))
    }

    @Test
    fun hiddenOrDifferentSongPagersCannotChangeCurrentMode() {
        assertFalse(CoverPresentation.acceptsMediaEvent("queue", 2, "synthetic", 2, "synthetic"))
        assertFalse(CoverPresentation.acceptsMediaEvent("home", 2, "synthetic", 2, "synthetic"))
        assertFalse(
            CoverPresentation.acceptsMediaEvent("player", 2, "synthetic_b", 2, "synthetic_a")
        )
        assertFalse(CoverPresentation.acceptsMediaEvent("player", 2, null, 2, "synthetic_a"))
    }

    @Test
    fun anExitingLyricsOwnerCannotHideTheReopenedCover() {
        val old = Any()
        val fresh = Any()
        val lyrics = OwnedPagingFlag().claim(old).update(old, true)
        assertTrue(lyrics.paging)
        val cover = lyrics.claim(fresh).update(old, true)
        assertFalse(cover.paging)
        assertTrue(cover.ownedBy(fresh))
    }

    @Test
    fun oldDisposalCannotClearTheNewFingerGesture() {
        val old = Any()
        val fresh = Any()
        val gesture = OwnedPagingFlag().claim(old).claim(fresh).update(fresh, true)
        assertTrue(gesture.release(old).paging)
        assertFalse(gesture.release(fresh).paging)
    }

    @Test
    fun theSameOwnerDoesNotResetItsOwnGesture() {
        val owner = Any()
        assertTrue(OwnedPagingFlag().claim(owner).update(owner, true).claim(owner).paging)
    }

    @Test
    fun releasedOwnersCannotBringBackAStalePagingFlag() {
        val owner = Any()
        val released = OwnedPagingFlag().claim(owner).update(owner, true).release(owner)
        assertFalse(released.update(owner, true).paging)
        assertFalse(released.ownedBy(owner))
    }

    @Test
    fun restoredAndProgrammaticPagesNeverStartPlaybackOrChangeLyrics() {
        assertFalse(CoverPresentation.shouldCommitPage(false, false, "player"))
        assertFalse(CoverPresentation.shouldCommitPage(false, true, "player"))
    }

    @Test
    fun aFingerChoiceCommitsOnlyWhenTheNativeFlingSettles() {
        assertFalse(CoverPresentation.shouldCommitPage(true, true, "player"))
        assertTrue(CoverPresentation.shouldCommitPage(true, false, "player"))
    }

    @Test
    fun queueAndBackgroundPagesCannotCommitAnOldFingerChoice() {
        for (route in listOf("queue", "comments", "home")) assertFalse(
            CoverPresentation.shouldCommitPage(true, false, route)
        )
    }

    @Test
    fun anExitingLyricsPageCannotBlockCaptureOfTheVisibleDockCover() {
        assertTrue(CoverPresentation.canCaptureSource(CoverDestination.Dock, true))
        assertTrue(CoverPresentation.canCaptureSource(CoverDestination.Player, false))
        assertFalse(CoverPresentation.canCaptureSource(CoverDestination.Player, true))
        assertFalse(CoverPresentation.canCaptureSource(CoverDestination.Hidden, false))
    }
}
