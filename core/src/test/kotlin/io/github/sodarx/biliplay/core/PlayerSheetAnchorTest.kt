package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PlayerSheetAnchorTest {
    @Test
    fun movingAndReboundingSheetNeverChangesTheFinalCoverLandingPoint() {
        val landing = 184f
        for (sheet in listOf(900f, 470f, 110f, 0f, -12f, -2f, 0f)) assertEquals(
            landing,
            CoverPresentation.playerAnchorTop(landing + sheet, sheet),
            .001f,
        )
        assertEquals(landing, CoverPresentation.playerAnchorTop(landing, Float.NaN), 0f)
    }
}
