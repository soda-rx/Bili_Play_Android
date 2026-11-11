package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class CollectionQueueTest {
    private val first = Track(1, "BV17K5r6nEod", "synthetic first", "synthetic", "", 10, 101)
    private val second = first.copy(aid = 2, bvid = "BV1o14y1R7Ad", cid = 202)
    private val third = first.copy(aid = 3, bvid = "BV1234567890", cid = 303)

    @Test
    fun clickedMiddleSongKeepsFullCollectionInServerOrder() {
        val video = Video(second, listOf(second), listOf(first, second, third))
        val result = CollectionQueue.tracks(second.copy(cid = 0), video, listOf(second))
        assertEquals(listOf(first, second, third), result)
        assertEquals(1, result.indexOfFirst { it.key == second.key })
    }

    @Test
    fun collectionTakesPrecedenceOverHomeRecommendations() {
        assertEquals(
            listOf(first, second),
            CollectionQueue.tracks(
                first,
                Video(first, listOf(first), listOf(first, second)),
                listOf(first, third),
            ),
        )
    }

    @Test
    fun multiPartVideoEnqueuesAllPartsAndPreservesPage() {
        val part2 = first.copy(page = 2, cid = 102)
        assertEquals(
            listOf(first, part2),
            CollectionQueue.tracks(
                part2.copy(cid = 0),
                Video(first, listOf(first, part2)),
                listOf(part2),
            ),
        )
    }

    @Test
    fun missingClickedEpisodeIsAddedWithoutLosingCollection() {
        assertEquals(
            listOf(first, second, third),
            CollectionQueue.tracks(
                first,
                Video(first, listOf(first), listOf(second, third)),
                emptyList(),
            ),
        )
    }

    @Test
    fun duplicateEpisodesAreRemovedWithoutCollapsingDistinctParts() {
        val part2 = first.copy(page = 2, cid = 102)
        assertEquals(
            listOf(first, part2),
            CollectionQueue.tracks(
                first,
                Video(first, listOf(first), listOf(first, first, part2)),
                emptyList(),
            ),
        )
    }

    @Test
    fun offlineMissingMetadataKeepsCallerQueue() {
        assertEquals(
            listOf(first, second),
            CollectionQueue.tracks(first, null, listOf(first, second)),
        )
    }

    @Test
    fun emptyFallbackStillContainsClickedSong() {
        assertEquals(listOf(first), CollectionQueue.tracks(first, null, emptyList()))
    }
}
