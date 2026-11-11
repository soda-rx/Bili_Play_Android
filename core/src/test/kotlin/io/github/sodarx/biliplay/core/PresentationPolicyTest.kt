package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PresentationPolicyTest {
    @Test
    fun queueModesContainExactlyTheFourRequestedChoices() {
        assertEquals(listOf("顺序", "列表", "单曲", "随机"), QueueMode.entries.map { it.label })
    }

    @Test
    fun restoredProgressIncludesDurationWithoutStartingTheEngine() {
        val duration = PlaybackRestore.duration(0, 200)
        val position = PlaybackRestore.position(50000, duration)
        assertEquals(200000L, duration)
        assertEquals(.25, position.toDouble() / duration, 0.0)
    }

    @Test
    fun invalidRestoredPositionsStayInRange() {
        assertEquals(0L, PlaybackRestore.position(-1, 1000))
        assertEquals(1000L, PlaybackRestore.position(2000, 1000))
        assertEquals(1234L, PlaybackRestore.duration(1234, 99))
    }

    @Test
    fun cuesAdvanceThreeHundredMilliseconds() {
        val rows = listOf(SubtitleLine(0.0, 1.0, "one"), SubtitleLine(1.0, 2.0, "two"))
        assertEquals(0, LyricLayout.active(rows, 699))
        assertEquals(1, LyricLayout.active(rows, 700))
        assertEquals(0, SubtitlePolicy.active(rows, .82))
    }

    @Test
    fun lyricFocusIsHigherAndLeavesRoomForTheScaledLine() {
        val top = LyricLayout.targetTop(400, 160)
        assertTrue(top < 400 * .36)
        assertTrue(top >= 160 * .08)
        assertTrue(top + 160 * 1.08 < 400)
    }

    @Test
    fun loggedInAccountsCannotBeDeletedEvenIfInactive() {
        assertFalse(AccountRemoval.allowed(false, true))
        assertFalse(AccountRemoval.allowed(true, false))
        assertTrue(AccountRemoval.allowed(false, false))
    }

    @Test
    fun bottomActionToneIsDarkerInEveryChannel() {
        val base = 0xff608070.toInt()
        val deeper = CoverColors.deeper(base)
        assertEquals(255, deeper ushr 24)
        for (shift in listOf(0, 8, 16)) assertTrue(
            (deeper ushr shift and 255) < (base ushr shift and 255)
        )
    }

    @Test
    fun desktopRotationUsesThreeGroupsAndSixSongRounds() {
        assertEquals(setOf(0, 1, 2), (1..3).map { DesktopMusicRotation.group(it, 0) }.toSet())
        assertEquals(6, DesktopMusicRotation.offset(4, 0))
        assertEquals(2, DesktopMusicRotation.archivePage(16, 0))
    }
}
