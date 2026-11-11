package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class QueueMembershipTest {
    private val a = Track(1, "BV0000000001", "synthetic a", "synthetic", "", 60)
    private val b = a.copy(bvid = "BV0000000002")

    @Test
    fun clearedQueueCannotRestoreAnOldCurrentSong() {
        assertNull(QueueMembership.current(emptyList(), a))
        assertEquals(-1, QueueMembership.restoredIndex(emptyList(), a, 0))
    }

    @Test
    fun unrelatedCheckpointCannotInventAQueueMember() {
        assertNull(QueueMembership.current(listOf(b), a))
        assertEquals(-1, QueueMembership.restoredIndex(listOf(b), a, 0))
    }

    @Test
    fun validRestorationUsesCanonicalQueueMetadata() {
        val updated = a.copy(cid = 123)
        assertSame(updated, QueueMembership.current(listOf(updated), a))
    }

    @Test
    fun deletingCurrentAdvancesToNextMemberOrPreviousAtEnd() {
        assertEquals(0, QueueMembership.afterRemoval(3, 0, 0))
        assertEquals(1, QueueMembership.afterRemoval(3, 2, 2))
    }

    @Test
    fun deletingLastMemberLeavesNoPlayableSelection() {
        assertEquals(-1, QueueMembership.afterRemoval(1, 0, 0))
    }

    @Test
    fun deletingAnotherSongKeepsCurrentAndInvalidIndicesAreSafe() {
        assertEquals(1, QueueMembership.afterRemoval(3, 2, 0))
        assertEquals(0, QueueMembership.afterRemoval(2, 0, -1))
    }
}
