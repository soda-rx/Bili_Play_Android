package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class ContinuousSceneMotionTest {
    @Test
    fun leftAndRightArrivalsStayContinuousAcrossTheTargetWithoutASecondKick() {
        for ((start, target) in listOf(0f to 360f, 720f to 0f)) {
            val direction = if (target > start) 1f else -1f
            val values =
                listOf(
                    target - direction,
                    target,
                    target + direction,
                    target + direction * 12f,
                    target,
                )
            for (value in values) {
                val frame = PageInertia.travelFrame(start, target, value, 72f)
                assertEquals(value, frame.position - frame.offset, .001f)
            }
            assertEquals(0f, PageInertia.travelFrame(start, target, target, 72f).offset, 0f)
        }
    }

    @Test
    fun crossingTheMiddlePageDoesNotStartAnArrivalAnimation() {
        val middle = PageInertia.travelFrame(0f, 720f, 360f, 72f)
        assertEquals(360f, middle.position, 0f)
        assertEquals(0f, middle.offset, 0f)
        val excessive = PageInertia.travelFrame(0f, 720f, 10000f, 72f)
        assertTrue(kotlin.math.abs(excessive.offset) <= 72f * .4f)
        assertTrue(
            PageInertia.travelFrame(Float.NaN, Float.NaN, Float.NaN, 72f).position.isFinite()
        )
    }

    @Test
    fun everySecondaryVisitGetsANewTransitionEvenWhenQuicklyReopened() {
        assertNotEquals(
            SceneEntry.of("synthetic", "downloads", 1),
            SceneEntry.of("synthetic", "downloads", 3),
        )
        assertNotEquals(
            SceneEntry.of("synthetic", "settings", 1),
            SceneEntry.of("synthetic", "settings", 3),
        )
        assertEquals(
            SceneEntry.of("synthetic", "downloads", 3),
            SceneEntry.of("synthetic", "downloads", 3),
        )
    }

    @Test
    fun primaryAndPlaybackGroupsKeepTheirPagerAndMediaModeAcrossInternalNavigation() {
        assertEquals(SceneEntry.of("synthetic", "home", 1), SceneEntry.of("synthetic", "mine", 2))
        assertEquals(
            SceneEntry.of("synthetic", "player", 3),
            SceneEntry.of("synthetic", "queue", 4),
        )
        assertNotEquals(
            SceneEntry.of("synthetic", "player", 3),
            SceneEntry.of("synthetic", "downloads", 4),
        )
    }

    @Test
    fun aDifferentProfileCannotReuseTheOldSceneState() {
        for (route in listOf("home", "player", "downloads")) assertNotEquals(
            SceneEntry.of("synthetic_a", route, 1),
            SceneEntry.of("synthetic_b", route, 1),
        )
    }

    @Test
    fun returningRestoresTheParentButReopeningTheChildCreatesAFreshVisit() {
        val history = RouteHistory()
        val mine = RouteVisit("mine", 0)
        val first = history.navigate(mine, "downloads")
        assertEquals(mine, history.back("mine"))
        val second = history.navigate(mine, "downloads")
        assertNotEquals(first.id, second.id)
        assertEquals(second, history.navigate(second, "downloads"))
    }

    @Test
    fun modalRepliesReturnToExactlyTheSameUnderlyingScene() {
        val history = RouteHistory()
        val search = history.navigate(RouteVisit("home", 0), "search")
        val comments = history.navigate(search, "comments")
        history.navigate(comments, "replies")
        assertEquals(comments, history.back("home"))
        assertEquals(search, history.back("home"))
    }

    @Test
    fun changingPrimaryTabClearsOldSecondaryHistory() {
        val history = RouteHistory()
        val settings = history.navigate(RouteVisit("mine", 0), "settings")
        history.navigate(settings, "home")
        assertEquals("home", history.back("home").route)
        history.clear()
        assertEquals("mine", history.back("mine").route)
    }
}
