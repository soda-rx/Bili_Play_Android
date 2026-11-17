package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class RecommendationPolicyTest {
    @Test
    fun emptyPoolsAndNonPositiveCountsSelectNothing() {
        assertEquals(emptyList<Track>(), RecommendationPolicy.pick(emptyList(), 5))
        assertEquals(emptyList<Track>(), RecommendationPolicy.section(emptyList(), 5))
        val pool = listOf(track(1), track(2))
        assertTrue(RecommendationPolicy.pick(pool, 0).isEmpty())
        assertTrue(RecommendationPolicy.pick(pool, -3).isEmpty())
        assertTrue(RecommendationPolicy.section(pool, -1, exclude = setOf(track(1).key)).isEmpty())
    }

    @Test
    fun duplicateKeysCollapseBeforeSelection() {
        val pool = listOf(track(1), track(1), track(2), track(2), track(3))
        val picked = RecommendationPolicy.pick(pool, 10)
        assertEquals(3, picked.size)
        assertEquals(
            setOf(track(1).key, track(2).key, track(3).key),
            picked.map { it.key }.toSet(),
        )
    }

    @Test
    fun countLargerThanPoolReturnsEveryUniqueCandidate() {
        val pool = (1..4).map { track(it) }
        val picked = RecommendationPolicy.pick(pool, 9)
        assertEquals(4, picked.size)
        assertEquals(pool.map { it.key }.toSet(), picked.map { it.key }.toSet())
    }

    @Test
    fun singleCandidateNeverGetsDuplicated() {
        val only = track(1)
        assertEquals(listOf(only), RecommendationPolicy.pick(listOf(only), 3))
        assertEquals(listOf(only), RecommendationPolicy.section(listOf(only), 3))
    }

    @Test
    fun batchKeepsCreatorsDistinctWhileEnoughCreatorsExist() {
        val repeatedCreator = (1..3).map { track(it, uploaderId = 1L) }
        val distinctCreators = (4..9).map { track(it) }
        val picked = RecommendationPolicy.pick(repeatedCreator + distinctCreators, 3)
        assertEquals(3, picked.size)
        assertEquals(3, picked.map { RecommendationPolicy.creator(it) }.distinct().size)
    }

    @Test
    fun repeatedCreatorsAlternateInsteadOfClustering() {
        val first = (1..3).map { track(it, uploaderId = 7L) }
        val second = (4..6).map { track(it, uploaderId = 8L) }
        val picked = RecommendationPolicy.pick(first + second, 4)
        assertEquals(4, picked.size)
        assertEquals(4, picked.map { it.key }.distinct().size)
        assertEquals(2, picked.map { RecommendationPolicy.creator(it) }.distinct().size)
        picked.zipWithNext().forEach { (before, after) ->
            assertNotEquals(
                RecommendationPolicy.creator(before),
                RecommendationPolicy.creator(after),
            )
        }
    }

    @Test
    fun repeatedCreatorFillsOnlyAfterOtherCreatorsRunOut() {
        val sameCreator = (1..3).map { track(it, uploaderId = 1L) }
        val picked = RecommendationPolicy.pick(sameCreator, 3)
        assertEquals(3, picked.size)
        assertEquals(3, picked.map { it.key }.distinct().size)
    }

    @Test
    fun recentlySeenKeysAreSkippedWhenFreshAlternativesExist() {
        val pool = (1..10).map { track(it) }
        val stale = setOf(pool[0].key, pool[1].key, pool[2].key, pool[3].key, pool[4].key)
        val signals =
            RecommendationSignals(
                playedKeys = setOf(pool[0].key, pool[1].key),
                recentBatches = listOf(setOf(pool[2].key, pool[3].key), setOf(pool[4].key)),
            )
        val picked = RecommendationPolicy.pick(pool, 3, signals)
        assertEquals(3, picked.size)
        assertTrue(picked.none { it.key in stale })
    }

    @Test
    fun unseenKeysOutrankSeenKeysEvenForLikedCreators() {
        val seenLiked = track(1, uploaderId = 5L)
        val freshNeutral = track(2, uploaderId = 6L)
        val signals =
            RecommendationSignals(
                playedKeys = setOf(seenLiked.key),
                likedCreators = setOf(RecommendationPolicy.creator(seenLiked)),
            )
        assertEquals(
            freshNeutral,
            RecommendationPolicy.pick(listOf(seenLiked, freshNeutral), 1, signals, seed = 0).single(),
        )
    }

    @Test
    fun smallFreshPoolStillFillsFromRecentBatches() {
        val pool = (1..6).map { track(it) }
        val fresh = pool.take(2).map { it.key }.toSet()
        val recent = pool.drop(2).map { it.key }.toSet()
        val signals = RecommendationSignals(recentBatches = listOf(recent))
        val picked = RecommendationPolicy.pick(pool, 5, signals)
        assertEquals(5, picked.size)
        assertEquals(5, picked.map { it.key }.distinct().size)
        assertEquals(fresh, picked.take(2).map { it.key }.toSet())
    }

    @Test
    fun playedKeysAreFallbackOnly() {
        val pool = (1..4).map { track(it) }
        val signals = RecommendationSignals(playedKeys = setOf(pool[0].key, pool[1].key))
        val picked = RecommendationPolicy.pick(pool, 4, signals)
        assertEquals(4, picked.size)
        assertEquals(4, picked.map { it.key }.distinct().size)
        assertEquals(setOf(pool[2].key, pool[3].key), picked.take(2).map { it.key }.toSet())
    }

    @Test
    fun onlyThreeNewestBatchesCountAsRecent() {
        assertEquals(3, RecommendationPolicy.RecentBatches)
        val pool = (1..5).map { track(it) }
        val signals =
            RecommendationSignals(
                recentBatches =
                    listOf(
                        setOf(pool[0].key),
                        setOf(pool[1].key),
                        setOf(pool[2].key),
                        setOf(pool[3].key), // fourth batch is ignored
                    ),
            )
        val picked = RecommendationPolicy.pick(pool, 1, signals, seed = 0)
        assertEquals(1, picked.size)
        assertNotEquals(pool[0].key, picked.single().key)
        assertTrue(picked.single().key == pool[3].key || picked.single().key == pool[4].key)
    }

    @Test
    fun likedCreatorsArePreferredAtEqualKeyFreshness() {
        val liked = track(1, uploaderId = 11L)
        val neutral = track(2, uploaderId = 22L)
        val playedOnly = track(3, uploaderId = 33L)
        val signals =
            RecommendationSignals(
                playedCreators = setOf(RecommendationPolicy.creator(playedOnly)),
                likedCreators = setOf(RecommendationPolicy.creator(liked)),
            )
        val picked = RecommendationPolicy.pick(listOf(liked, neutral, playedOnly), 3, signals)
        assertEquals(listOf(liked, neutral, playedOnly), picked)
    }

    @Test
    fun recentlyPlayedCreatorsAreLightlyDownweighted() {
        val neutral = track(1, uploaderId = 21L)
        val played = track(2, uploaderId = 22L)
        val signals =
            RecommendationSignals(playedCreators = setOf(RecommendationPolicy.creator(played)))
        assertEquals(
            neutral,
            RecommendationPolicy.pick(listOf(played, neutral), 1, signals, seed = 0).single(),
        )
    }

    @Test
    fun sameSeedIsDeterministicAndDifferentSeedsRotate() {
        val pool = (1..12).map { track(it) }
        val first = RecommendationPolicy.pick(pool, 6, seed = 7)
        val second = RecommendationPolicy.pick(pool, 6, seed = 7)
        assertEquals(first, second)
        assertEquals(6, first.size)
        assertEquals(6, first.map { it.key }.distinct().size)
        assertEquals(6, first.map { RecommendationPolicy.creator(it) }.distinct().size)
        val rotated =
            (0..15)
                .map { seed -> RecommendationPolicy.pick(pool, 6, seed = seed).map { it.key } }
                .toSet()
        assertTrue(rotated.size > 1)
    }

    @Test
    fun creatorPrefersUidThenNormalizedName() {
        assertEquals(
            "uid:42",
            RecommendationPolicy.creator(track(1, uploaderId = 42L, uploader = "Whatever")),
        )
        assertEquals(
            "name:sameup",
            RecommendationPolicy.creator(track(2, uploaderId = 0L, uploader = "  Same   UP ")),
        )
        assertEquals(
            RecommendationPolicy.creator(track(2, uploaderId = 0L, uploader = "  Same   UP ")),
            RecommendationPolicy.creator(track(3, uploaderId = 0L, uploader = "same up")),
        )
        assertEquals(
            "name:up",
            RecommendationPolicy.creator(track(4, uploaderId = -1L, uploader = "UP")),
        )
    }

    @Test
    fun zeroUploaderIdGroupsTracksByNormalizedName() {
        val first = track(1, uploaderId = 0L, uploader = "Same UP")
        val sameCreator = track(2, uploaderId = 0L, uploader = " same up ")
        val other = track(3, uploaderId = 9L, uploader = "Other")
        val two = RecommendationPolicy.pick(listOf(first, sameCreator, other), 2)
        assertEquals(2, two.size)
        assertEquals(2, two.map { RecommendationPolicy.creator(it) }.distinct().size)
        val three = RecommendationPolicy.pick(listOf(first, sameCreator, other), 3)
        assertEquals(3, three.size)
        assertEquals(3, three.map { it.key }.distinct().size)
    }

    @Test
    fun sectionNeverReturnsExcludedKeys() {
        val pool = (1..6).map { track(it) }
        val exclude = setOf(pool[0].key, pool[1].key)
        val picked = RecommendationPolicy.section(pool, 3, exclude = exclude)
        assertEquals(3, picked.size)
        assertTrue(picked.none { it.key in exclude })
        assertEquals(
            RecommendationPolicy.pick(pool, 4, seed = 3),
            RecommendationPolicy.section(pool, 4, seed = 3),
        )
        assertTrue(
            RecommendationPolicy.section(pool, 9, exclude = pool.map { it.key }.toSet()).isEmpty(),
        )
    }

    @Test
    fun sectionFallsBackToRecentBatchesOnlyWhenNeeded() {
        val fresh = (1..2).map { track(it) }
        val recent = (3..5).map { track(it) }
        val signals = RecommendationSignals(recentBatches = listOf(recent.map { it.key }.toSet()))
        val picked = RecommendationPolicy.section(fresh + recent, 4, signals)
        assertEquals(4, picked.size)
        assertEquals(4, picked.map { it.key }.distinct().size)
        assertEquals(fresh.map { it.key }.toSet(), picked.take(2).map { it.key }.toSet())
    }

    @Test
    fun hostileInputsStayDeterministic() {
        val pool =
            listOf(
                track(1, uploaderId = 0L, uploader = "   "),
                track(2, uploaderId = 0L, uploader = ""),
                track(2, uploaderId = 0L, uploader = ""),
                track(3, uploaderId = -5L, uploader = "???"),
            )
        val signals =
            RecommendationSignals(
                playedKeys = setOf(track(1).key),
                playedCreators = setOf("name:", "uid:5"),
                likedCreators = setOf("name:???"),
                recentBatches = (0..8).map { setOf("unrelated-$it:1") },
            )
        val first = RecommendationPolicy.pick(pool, Int.MAX_VALUE, signals, seed = Int.MIN_VALUE)
        val second = RecommendationPolicy.pick(pool, Int.MAX_VALUE, signals, seed = Int.MIN_VALUE)
        assertEquals(first, second)
        assertEquals(3, first.size)
        assertEquals(3, first.map { it.key }.distinct().size)
        assertEquals("name:", RecommendationPolicy.creator(pool[0]))
    }

    @Test
    fun signalsAreNotMutated() {
        val playedKeys = mutableSetOf(track(1).key)
        val playedCreators = mutableSetOf("uid:1")
        val likedCreators = mutableSetOf("uid:2")
        val batch = mutableSetOf(track(3).key)
        val batches = mutableListOf<Set<String>>(batch)
        val signals = RecommendationSignals(playedKeys, playedCreators, likedCreators, batches)
        val playedKeysBefore = playedKeys.toSet()
        val playedCreatorsBefore = playedCreators.toSet()
        val likedCreatorsBefore = likedCreators.toSet()
        val batchBefore = batch.toSet()
        val pool = (1..6).map { track(it) }
        RecommendationPolicy.pick(pool, 3, signals, seed = 5)
        RecommendationPolicy.section(pool, 3, signals, seed = 5, exclude = setOf(pool[0].key))
        assertEquals(playedKeysBefore, playedKeys)
        assertEquals(playedCreatorsBefore, playedCreators)
        assertEquals(likedCreatorsBefore, likedCreators)
        assertEquals(batchBefore, batch)
        assertEquals(1, batches.size)
        assertSame(batch, batches[0])
    }

    @Test
    fun candidateOrderDoesNotChangeSelection() {
        val pool = (1..9).map { track(it) }
        val expected = RecommendationPolicy.pick(pool, 5, seed = 4)
        assertEquals(expected, RecommendationPolicy.pick(pool.reversed(), 5, seed = 4))
        assertEquals(
            expected,
            RecommendationPolicy.pick(pool.sortedByDescending { it.key }, 5, seed = 4),
        )
    }

    private fun track(
        id: Int,
        uploaderId: Long = id.toLong(),
        uploader: String = "UP$id",
    ): Track =
        Track(
            aid = id.toLong(),
            bvid = "BV" + id.toString().padStart(10, '0'),
            title = "synthetic-$id",
            uploader = uploader,
            cover = "",
            duration = 180,
            uploaderId = uploaderId,
        )
}
