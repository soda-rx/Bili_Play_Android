package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class QueueOccurrenceTest {
    private val a = Track(1, "BV0000000001", "synthetic", "synthetic", "", 60)
    private val b = a.copy(bvid = "BV0000000002")

    @Test
    fun repeatedSongsHaveUniqueStablePagerKeys() {
        val q = listOf(a, b, a)
        assertEquals(3, QueueMembership.displayKeys(q).toSet().size)
        assertEquals(QueueMembership.displayKeys(q), QueueMembership.displayKeys(q))
    }

    @Test
    fun savedSelectionRestoresTheSecondOccurrenceRatherThanTheFirst() {
        assertEquals(2, QueueMembership.restoredIndex(listOf(a, b, a), a, 2))
        assertEquals(0, QueueMembership.restoredIndex(listOf(a, b, a), a, 1))
    }

    @Test
    fun removingOneOccurrenceKeepsTheOtherAsCurrent() {
        assertEquals(1, QueueMembership.afterRemoval(3, 2, 0))
        assertEquals(0, QueueMembership.afterRemoval(3, 0, 2))
    }

    @Test
    fun draggingCurrentAndDraggingAcrossItRetainTheSameOccurrence() {
        assertEquals(2, QueueMembership.afterMove(0, 0, 2))
        assertEquals(0, QueueMembership.afterMove(1, 0, 2))
        assertEquals(2, QueueMembership.afterMove(1, 2, 0))
    }

    @Test
    fun manualNextAdvancesPastRepeatedIdentities() {
        val q = listOf(a, a, b)
        val i = QueuePolicy.nextIndex(q.size, 1, QueueMode.RepeatAll, false)!!
        assertEquals(b, q[i])
        assertEquals(-1, QueueMembership.restoredIndex(q, null, 1))
    }
}
