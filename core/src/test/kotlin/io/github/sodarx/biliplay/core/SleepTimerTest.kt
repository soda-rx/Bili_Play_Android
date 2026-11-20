package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test
    fun deadlineAndRemainingAreBootRelative() {
        val now = 1_000_000L
        val ends = SleepTimer.endsAt(now, 15)!!
        assertEquals(now + 15 * 60_000L, ends)
        assertEquals(15 * 60_000L, SleepTimer.remaining(ends, now))
        assertEquals(0L, SleepTimer.remaining(ends, ends + 5_000L))
        assertNull(SleepTimer.endsAt(now, 0))
        assertNull(SleepTimer.endsAt(now, -1))
        assertNull(SleepTimer.endsAt(now, SleepTimer.MAX_MINUTES + 1))
    }

    @Test
    fun labelsGroupLongTimersAndKeepTwoDigitMinutes() {
        assertEquals("15:00", SleepTimer.label(15 * 60_000L))
        assertEquals("09:05", SleepTimer.label(9 * 60_000L + 5_000L))
        assertEquals("1:00:01", SleepTimer.label(60 * 60_000L + 1_000L))
        assertEquals("00:00", SleepTimer.label(-50L))
    }

    @Test
    fun customMinutesAreBounded() {
        assertEquals(45, SleepTimer.parseMinutes("45"))
        assertEquals(720, SleepTimer.parseMinutes(" 720 "))
        assertNull(SleepTimer.parseMinutes("0"))
        assertNull(SleepTimer.parseMinutes("721"))
        assertNull(SleepTimer.parseMinutes("abc"))
        assertNull(SleepTimer.parseMinutes(""))
    }
}
