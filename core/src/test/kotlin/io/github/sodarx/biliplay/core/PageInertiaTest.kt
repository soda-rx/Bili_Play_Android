package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PageInertiaTest {
    @Test
    fun favoritesToHomeRetainsRightwardArrivalInertia() {
        assertEquals(1, PageInertia.arrivalDirection(1, 0, 2))
    }

    @Test
    fun lastPageRetainsLeftwardArrivalInertia() {
        assertEquals(-1, PageInertia.arrivalDirection(1, 2, 2))
    }

    @Test
    fun idleDoesNotKickButMiddlePagesKeepTravelDirection() {
        assertEquals(0, PageInertia.arrivalDirection(0, 0, 2))
        assertEquals(-1, PageInertia.arrivalDirection(0, 1, 2))
        assertEquals(1, PageInertia.arrivalDirection(2, 1, 2))
    }

    @Test
    fun boundaryDecorationUsesOnlyResidualMovement() {
        assertEquals(28f, PageInertia.edgePull(0f, 100f, 72f), .001f)
        assertEquals(0f, PageInertia.edgePull(0f, 0f, 72f), 0f)
        assertEquals(-28f, PageInertia.edgePull(0f, -100f, 72f), .001f)
    }

    @Test
    fun invalidAndExcessiveOffsetsStayFiniteAndBounded() {
        assertEquals(0f, PageInertia.edgePull(Float.NaN, 0f, 72f), 0f)
        assertEquals(72f, PageInertia.edgePull(0f, 1000f, 72f), 0f)
        assertEquals(0f, PageInertia.edgePull(0f, 10f, -1f), 0f)
    }
}
