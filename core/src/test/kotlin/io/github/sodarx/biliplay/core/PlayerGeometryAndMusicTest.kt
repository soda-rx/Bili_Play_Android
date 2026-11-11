package io.github.sodarx.biliplay.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlayerGeometryAndMusicTest {
    @Test
    fun shrinkingOvershootCannotCreateNegativeDimensions() {
        val g = PlayerGeometry.resolve(320f, 700f, 24f, 500f, -100f, -30f, 30f, 52f, -4f)
        assertTrue(g.width > 0 && g.height > 0 && g.cover > 0 && g.padding >= 0)
        assertTrue(g.x >= 0 && g.x + g.width <= 320 && g.y >= 24 && g.y + g.height <= 700)
        assertTrue(g.cover + 2 * g.padding <= minOf(g.width, g.height))
    }

    @Test
    fun invalidValuesAndTinyWindowsStayFiniteAndInsideBounds() {
        val g =
            PlayerGeometry.resolve(
                20f,
                12f,
                40f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NaN,
                -100f,
                200f,
                100f,
            )
        assertTrue(listOf(g.x, g.y, g.width, g.height, g.cover, g.padding).all { it.isFinite() })
        assertTrue(g.x + g.width <= 20 && g.y + g.height <= 12)
    }

    @Test
    fun squareCoverFitsCompactCircle() {
        val g = PlayerGeometry.resolve(360f, 800f, 24f, 300f, 36f, 48f, 48f, 40f, 4f)
        assertEquals(40f, g.cover, 0f)
        assertEquals(4f, g.padding, 0f)
    }

    @Test
    fun paletteUsesTheActualDominantCoverColor() {
        val red = 0xffe04040.toInt()
        val blue = 0xff4060e0.toInt()
        assertEquals(red, CoverColors.dominant(intArrayOf(red, red, red, blue)))
    }

    @Test
    fun transparentWhiteAndEmptyPixelsDoNotInventAColor() {
        assertNull(CoverColors.dominant(intArrayOf(0x00000000, 0xffffffff.toInt())))
        assertNull(CoverColors.dominant(intArrayOf()))
    }

    @Test
    fun rotationStaysFiniteAndWrapsWithoutResetAnimation() {
        val result = DiscRotation.advance(359.9f, 20_000_000L, 1f)
        assertTrue(result >= 0 && result < 1)
        assertEquals(0f, DiscRotation.advance(Float.NaN, 0, 1f), 0f)
    }

    @Test
    fun rotationDoesNotJumpAfterBackgroundOrDisabledAnimations() {
        assertEquals(50f, DiscRotation.advance(50f, 20_000_000L, 0f), 0f)
        assertEquals(51.8f, DiscRotation.advance(50f, 1_000_000_000L, 1f), .001f)
    }

    private fun song(id: Int, plays: Long) =
        MusicCandidate(
            Track(
                id.toLong(),
                "BVsynthetic$id",
                "synthetic",
                "synthetic$id",
                "",
                30,
                uploaderId = id.toLong(),
            ),
            plays,
        )

    @Test
    fun popularPersonalRecommendationsAreNotReplacedByUnrelatedGlobalSongs() {
        val related = (1..14).map { song(it, it * 100L) }
        val feed = (15..20).map { song(it, it * 100L) }
        val sections = MusicRecommendations.sections(related, feed)
        assertEquals(3, sections.likes.size)
        assertEquals(8, sections.daily.size)
        assertEquals("BVsynthetic20:1", sections.daily.first().key)
        assertTrue(
            (sections.likes + sections.daily + sections.more).all { track ->
                (related + feed).any { it.track.key == track.key }
            }
        )
    }

    @Test
    fun morePreservesRelevantProviderOrderAndDoesNotRequirePopularity() {
        val related = (1..12).map { song(it, it * 10L) }
        val feed = listOf(song(30, 0), song(31, 0))
        val s = MusicRecommendations.sections(related, feed)
        assertEquals(listOf("BVsynthetic30:1", "BVsynthetic31:1"), s.more.take(2).map { it.key })
    }

    @Test
    fun emptyPersonalFeedNeverTurnsIntoAFakePersonalizedChart() {
        val s = MusicRecommendations.sections(emptyList(), emptyList())
        assertTrue(s.likes.isEmpty() && s.daily.isEmpty() && s.more.isEmpty())
    }

    @Test
    fun onlyMusicCategoriesAreAccepted() {
        assertTrue(MusicRecommendations.isMusic(JSONObject("""{"tid":31}""")))
        assertFalse(
            MusicRecommendations.isMusic(
                JSONObject("""{"tid":17,"title":"music mentioned in a game"}""")
            )
        )
    }

    @Test
    fun smallCandidatePoolDoesNotLeaveMoreEmptyAfterDailyConsumesEverything() {
        val sections =
            MusicRecommendations.sections((1..6).map { song(it, it * 100L) }, emptyList())
        assertEquals(3, sections.likes.size)
        assertEquals(2, sections.daily.size)
        assertEquals(1, sections.more.size)
        assertEquals(
            6,
            (sections.likes + sections.daily + sections.more).distinctBy { it.key }.size,
        )
    }

    @Test
    fun fullWindowPaletteUsesTwoActualCoverColors() {
        val red = 0xffe04040.toInt()
        val blue = 0xff4060e0.toInt()
        val palette = CoverColors.palette(intArrayOf(red, red, red, blue))!!
        assertEquals(red, palette.primary)
        assertEquals(blue, palette.secondary)
        assertNull(CoverColors.palette(intArrayOf()))
    }

    @Test
    fun springUndershootMustNeverReachLayoutPadding() {
        listOf(-64f, -4.5f, -.01f, 0f, 32f, 64f, 68f).forEach {
            val spacing = PlayerGeometry.spacing(it, 64f)
            assertTrue(spacing.isFinite() && spacing >= 0 && spacing <= 64)
        }
    }

    @Test
    fun nonFiniteSpacingAndInvalidBoundsAreSafe() {
        assertEquals(0f, PlayerGeometry.spacing(Float.NaN, 64f), 0f)
        assertEquals(0f, PlayerGeometry.spacing(Float.POSITIVE_INFINITY, 64f), 0f)
        assertEquals(0f, PlayerGeometry.spacing(8f, -2f), 0f)
    }

    @Test
    fun explicitNonMusicIdsOverrideLabelsAndParents() {
        listOf(17, 266, 267).forEach {
            assertFalse(
                MusicRecommendations.isMusic(
                    JSONObject().put("tid", it).put("tname", "音乐").put("parent_tid", 3)
                )
            )
        }
        assertTrue(MusicRecommendations.isMusic(JSONObject().put("tid", 265)))
    }

    @Test
    fun tinyMusicPoolStillHasTodayContent() {
        val sections = MusicRecommendations.sections(listOf(song(1, 100)), emptyList())
        assertEquals(1, sections.likes.size)
        assertEquals(1, sections.daily.size)
    }

    @Test
    fun accountRelevanceTakesPriorityBeforePlayCountInToday() {
        val candidates =
            (1..9).map { song(it, it * 100L).copy(relevance = if (it == 4) 300 else 0) }
        val sections = MusicRecommendations.sections(candidates, emptyList())
        assertTrue(sections.daily.any { it.bvid == "BVsynthetic4" })
    }

    @Test
    fun differentCoverFamiliesCannotProduceTheSameThemeBackdrop() {
        val pairs =
            listOf(
                0xff184484.toInt() to 0xff205838.toInt(),
                0xffdcb070.toInt() to 0xffaca88c.toInt(),
                0xffe89848.toInt() to 0xff784038.toInt(),
                0xff7870b0.toInt() to 0xff406898.toInt(),
            )
        for (dark in listOf(false, true)) {
            val tones = pairs.map { (a, b) ->
                CoverColors.backdrop(CoverPalette(a, b), .2, .5, dark)
            }
            assertEquals(4, tones.distinct().size)
            assertTrue(tones.all { it ushr 24 == 255 })
        }
    }

    @Test
    fun darkAndLightBackdropKeepReadableToneRanges() {
        val palette = CoverPalette(0xffff9020.toInt(), 0xff3060d0.toInt())
        for (x in listOf(0.0, .5, 1.0)) for (y in listOf(0.0, .5, 1.0)) {
            val dark = CoverColors.backdrop(palette, x, y, true)
            val light = CoverColors.backdrop(palette, x, y, false)
            for (shift in listOf(0, 8, 16)) {
                assertTrue((dark ushr shift and 255) in 16..120)
                assertTrue((light ushr shift and 255) in 192..250)
            }
        }
    }
}
