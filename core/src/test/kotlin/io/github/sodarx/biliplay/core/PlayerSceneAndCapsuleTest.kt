package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PlayerSceneAndCapsuleTest {
    @Test
    fun playbackKeepsItsEnteredLayerAboveEveryReturnDestination() {
        for (playback in listOf("player", "queue")) {
            val retainedLayer = CoverPresentation.sceneLayer(playback)
            for (route in listOf("home", "favorites", "mine", "search", "settings")) assertTrue(
                retainedLayer > CoverPresentation.sceneLayer(route)
            )
        }
    }

    @Test
    fun nativeCapsuleFrameIsAbsentDuringPendingAndMovingReturn() {
        assertFalse(
            CoverPresentation.capsuleSurfaceVisible(
                CoverDestination.Player,
                CoverDestination.Dock,
                false,
            )
        )
        assertFalse(
            CoverPresentation.capsuleSurfaceVisible(
                CoverDestination.Dock,
                CoverDestination.Player,
                false,
            )
        )
        assertFalse(
            CoverPresentation.capsuleSurfaceVisible(
                CoverDestination.Dock,
                CoverDestination.Player,
                true,
            )
        )
        assertFalse(
            CoverPresentation.capsuleSurfaceVisible(
                CoverDestination.Dock,
                CoverDestination.Dock,
                true,
            )
        )
        assertTrue(
            CoverPresentation.capsuleSurfaceVisible(
                CoverDestination.Dock,
                CoverDestination.Dock,
                false,
            )
        )
    }

    @Test
    fun playbackEntrancesDoNotRunHorizontalSceneTransitions() {
        // Opening the player from search, folders or any other page keeps only the vertical sheet.
        for (previous in listOf("home", "favorites", "mine", "search", "folder", "settings")) {
            val group = if (previous in CoverPresentation.primaryRoutes) "primary" else previous
            assertTrue(
                "forward $previous -> player",
                CoverPresentation.suppressSceneTransition(group, "playback", back = false),
            )
        }
        // Closing the player back to any page also relies on the cover returning to its dock.
        for (destination in listOf("primary", "search", "folder", "settings")) {
            assertTrue(
                "back player -> $destination",
                CoverPresentation.suppressSceneTransition("playback", destination, back = true),
            )
        }
        // Pushing search over the queue animates in, and popping it back animates out.
        assertFalse(
            CoverPresentation.suppressSceneTransition("playback", "librarySearch", back = false)
        )
        assertFalse(
            CoverPresentation.suppressSceneTransition("librarySearch", "playback", back = true)
        )
        // Ordinary page navigation is unchanged.
        assertFalse(CoverPresentation.suppressSceneTransition("primary", "search", back = false))
        assertFalse(CoverPresentation.suppressSceneTransition("search", "primary", back = true))
        assertFalse(
            CoverPresentation.suppressSceneTransition("folder", "librarySearch", back = false)
        )
    }

    @Test
    fun travellingRimEmergesMonotonicallyAndMatchesTheDockAtLanding() {
        val values =
            listOf(320f, 250f, 180f, 120f, 80f, 40f).map {
                CoverPresentation.capsuleRimProgress(320f, it, 40f)
            }
        assertEquals(0f, values.first(), 0f)
        assertEquals(1f, values.last(), .0001f)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(values[3] > 0f && values[3] < 1f)
        assertEquals(0f, CoverPresentation.capsuleRimProgress(Float.NaN, 80f, 40f), 0f)
        assertEquals(0f, CoverPresentation.capsuleRimProgress(40f, 40f, 40f), 0f)
        assertEquals(0f, CoverPresentation.capsuleRimProgress(320f, 80f, 0f), 0f)
    }
}
