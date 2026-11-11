package io.github.sodarx.biliplay.core

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PageArrivalAndChromeTest {
    @Test
    fun buttonArrivalExistsAtBothEdgesAndReselectingAnEdge() {
        assertTrue(PageInertia.arrivalVelocity(1, 0, 2, 0f, 72f, true) > 0f)
        assertTrue(PageInertia.arrivalVelocity(1, 2, 2, 0f, 72f, true) < 0f)
        assertTrue(PageInertia.arrivalVelocity(0, 0, 2, 0f, 72f, true) > 0f)
        assertTrue(PageInertia.arrivalVelocity(2, 2, 2, 0f, 72f, true) < 0f)
        assertEquals(0f, PageInertia.arrivalVelocity(1, 1, 2, 0f, 72f, true), 0f)
    }

    @Test
    fun fingerArrivalTracksSpeedAndActualDirectionIncludingMiddlePage() {
        val slow = PageInertia.arrivalVelocity(0, 1, 2, -100f, 72f, false)
        val fast = PageInertia.arrivalVelocity(0, 1, 2, -4000f, 72f, false)
        assertTrue(slow < 0f && fast < 0f && abs(fast) > abs(slow))
        assertTrue(PageInertia.arrivalVelocity(2, 1, 2, -1000f, 72f, false) > 0f)
        assertEquals(0f, PageInertia.arrivalVelocity(0, 0, 2, 4000f, 72f, false), 0f)
    }

    @Test
    fun arrivalIsFiniteBoundedAndRejectsInvalidPages() {
        assertEquals(0f, PageInertia.arrivalVelocity(-1, 0, 2, 0f, 72f, true), 0f)
        assertEquals(0f, PageInertia.arrivalVelocity(0, 3, 2, 0f, 72f, true), 0f)
        assertEquals(0f, PageInertia.arrivalVelocity(0, 1, 2, 0f, Float.NaN, true), 0f)
        assertTrue(PageInertia.arrivalVelocity(0, 1, 2, Float.NaN, 72f, false).isFinite())
        assertTrue(
            abs(PageInertia.arrivalVelocity(0, 1, 2, Float.MAX_VALUE, 72f, false)) <= 72f * 8f
        )
    }

    @Test
    fun preloadedNavigationReservesSpaceOnlyInsidePrimaryPages() {
        for (route in CoverPresentation.primaryRoutes) assertEquals(
            96f,
            CoverPresentation.bottomNavigationSpace(route, true, 96f, 24f),
            0f,
        )
        for (route in listOf("player", "queue", "settings", "search")) assertEquals(
            0f,
            CoverPresentation.bottomNavigationSpace(route, true, 96f, 24f),
            0f,
        )
        assertEquals(0f, CoverPresentation.bottomNavigationSpace("home", false, 96f, 24f), 0f)
    }

    @Test
    fun navigationFallbackCannotProduceNegativeOrInvalidPadding() {
        assertEquals(104f, CoverPresentation.bottomNavigationSpace("home", true, null, 24f), 0f)
        assertEquals(80f, CoverPresentation.bottomNavigationSpace("home", true, Float.NaN, -1f), 0f)
        assertEquals(80f, CoverPresentation.bottomNavigationSpace("home", true, -2f, Float.NaN), 0f)
    }

    @Test
    fun interruptedNavigationSettlesEvenWhenTheNearestPageAlreadyMatches() {
        assertTrue(PageInertia.shouldNavigate(1, 1, .25f, false, false))
        assertFalse(PageInertia.shouldNavigate(1, 1, 0f, false, false))
        assertFalse(PageInertia.shouldNavigate(1, 1, .25f, false, true))
        assertTrue(PageInertia.shouldNavigate(0, 0, 0f, true, false))
        assertFalse(PageInertia.shouldNavigate(1, 1, Float.NaN, false, false))
    }

    @Test
    fun interruptedButtonTravelStartsAtTheActualFractionalPosition() {
        val frame = PageInertia.travelFrame(450f, 360f, 450f, 72f)
        assertEquals(450f, frame.position, 0f)
        assertEquals(0f, frame.offset, 0f)
        val next = PageInertia.travelFrame(450f, 360f, 400f, 72f)
        assertEquals(400f, next.position, 0f)
        assertEquals(0f, next.offset, 0f)
    }
}
