package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class CommentImageLayoutTest {
    @Test fun twoAndFourPicturesUseTwoColumns() {
        assertEquals(1, CommentImageLayout.columns(1))
        for (count in listOf(2, 4)) assertEquals(2, CommentImageLayout.columns(count))
        for (count in listOf(3, 5, 6, 7, 8, 9)) assertEquals(3, CommentImageLayout.columns(count))
    }

    @Test fun portraitStaysCompactAndKeepsItsAspectRatio() {
        val size = CommentImageLayout.single(750, 1000, 300f)
        assertEquals(180f, size.width, .01f)
        assertEquals(240f, size.height, .01f)
    }

    @Test fun landscapeUsesMoreWidthAndNeverExceedsTheCommentColumn() {
        for (available in listOf(160f, 280f, 700f)) {
            val size = CommentImageLayout.single(1600, 900, available)
            assertTrue(size.width <= available)
            assertTrue(size.width <= 320f)
            assertEquals(1600f / 900f, size.width / size.height, .01f)
        }
    }

    @Test fun squareIsCompactAndExtremeMetadataCannotCreateUnboundedGeometry() {
        val square = CommentImageLayout.single(800, 800, 400f)
        assertEquals(180f, square.width, .01f)
        assertEquals(square.width, square.height, .01f)
        for ((width, height) in listOf(1 to 32768, 32768 to 1, 0 to 0, -1 to Int.MAX_VALUE)) {
            val size = CommentImageLayout.single(width, height, 280f)
            assertTrue(size.width.isFinite() && size.height.isFinite())
            assertTrue(size.width in 0f..280f)
            assertTrue(size.height in 0f..260f)
        }
        for (available in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val size = CommentImageLayout.single(800, 600, available)
            assertEquals(0f, size.width, 0f)
            assertEquals(0f, size.height, 0f)
        }
    }

    @Test fun scopedSearchBackRestoresTheOriginalListVisit() {
        for (route in listOf("folder", "uploader", "queue")) {
            val history = RouteHistory()
            val source = RouteVisit(route, 100)
            val search = history.navigate(source, "librarySearch")
            history.navigate(search, "player")
            assertEquals(search, history.back("home"))
            assertEquals(source, history.back("home"))
        }
    }
}
