package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class CoverPresentationTest {
    @Test
    fun ordinaryPagesAlwaysUseFixedDock() {
        for (route in listOf("home", "favorites", "mine", "search")) assertEquals(
            CoverDestination.Dock,
            CoverPresentation.destination(route, true, true),
        )
    }

    @Test
    fun secondaryPagesHidePlaybackChrome() {
        for (route in
            listOf(
                "folder",
                "settings",
                "uploader",
                "queue",
                "comments",
                "downloads",
                "about",
            )) assertEquals(
            CoverDestination.Hidden,
            CoverPresentation.destination(route, true, true),
        )
    }

    @Test
    fun detailsAndLyricsDoNotDuplicateTheDock() {
        assertEquals(CoverDestination.Player, CoverPresentation.destination("player", true, true))
        assertEquals(CoverDestination.Hidden, CoverPresentation.destination("lyrics", true, true))
    }

    @Test
    fun logoutAndNoTrackCannotLeaveAnArtworkOverlay() {
        assertEquals(
            CoverDestination.Hidden,
            CoverPresentation.destination("home", false, true),
        )
        assertEquals(
            CoverDestination.Hidden,
            CoverPresentation.destination("home", true, false),
        )
        assertEquals(
            CoverDestination.Hidden,
            CoverPresentation.destination("login", true, true),
        )
    }

    @Test
    fun zeroSizeAndStatusBarCoordinatesCannotBecomePlayerTargets() {
        assertFalse(CoverPresentation.validBounds(0f, 0f, 300f, 300f, 24f))
        assertFalse(CoverPresentation.validBounds(24f, 96f, 24f, 96f, 24f))
        assertFalse(CoverPresentation.validBounds(Float.NaN, 96f, 324f, 396f, 24f))
        assertTrue(CoverPresentation.validBounds(24f, 96f, 324f, 396f, 24f))
    }

    @Test
    fun likesContainDifferentCreatorsAndRotateCreatorsOnRefresh() {
        val pool = (1..9).map { track(it).copy(uploaderId = it.toLong()) }
        val previous = pool.take(3)
        val result =
            RecommendationPolicy.pick(
                pool,
                3,
                RecommendationSignals(recentBatches = listOf(previous.map { it.key }.toSet())),
                seed = 1,
            )
        assertEquals(3, result.map { it.uploaderId }.distinct().size)
        assertTrue(result.none { old -> previous.any { it.key == old.key } })
    }

    @Test
    fun repeatedCreatorCannotFillAllThreeSlots() {
        val pool = (1..6).map { track(it).copy(uploaderId = if (it < 5) 1L else it.toLong()) }
        assertEquals(3, RecommendationPolicy.pick(pool, 3).map { it.uploaderId }.distinct().size)
    }

    @Test
    fun shortCreatorPoolStillFillsOnlyFromThePool() {
        val pool = (1..5).map { track(it).copy(uploaderId = 1L) }
        val result = RecommendationPolicy.pick(pool, 3)
        assertEquals(3, result.size)
        assertTrue(result.all { row -> pool.any { it.key == row.key } })
    }

    private fun track(id: Int) =
        Track(
            id.toLong(),
            "BV" + id.toString().padStart(10, '0'),
            "synthetic",
            "synthetic$id",
            "",
            10,
        )

    @Test
    fun likedSectionContainsExactlyThreeUniqueSongsWhenAvailable() {
        val pool = (1..8).map(::track)
        assertEquals(3, RecommendationPolicy.pick(pool + pool, 3).size)
        assertEquals(3, RecommendationPolicy.pick(pool + pool, 3).map { it.key }.distinct().size)
    }

    @Test
    fun refreshingLikesAvoidsPreviousThreeWithoutChangingPool() {
        val pool = (1..8).map(::track)
        val previous = pool.take(3)
        val result =
            RecommendationPolicy.pick(
                pool,
                3,
                RecommendationSignals(recentBatches = listOf(previous.map { it.key }.toSet())),
            )
        assertEquals(3, result.size)
        assertTrue(result.none { row -> previous.any { it.key == row.key } })
        assertEquals(8, pool.size)
    }

    @Test
    fun shortAndEmptyRecommendationListsNeverInventSongs() {
        assertTrue(RecommendationPolicy.pick(emptyList(), 3).isEmpty())
        val pool = listOf(track(1), track(2))
        assertEquals(pool.map { it.key }.toSet(), RecommendationPolicy.pick(pool, 3).map { it.key }.toSet())
    }
}
