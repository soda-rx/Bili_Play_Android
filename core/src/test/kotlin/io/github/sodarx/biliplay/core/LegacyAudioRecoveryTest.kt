package io.github.sodarx.biliplay.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyAudioRecoveryTest {
    @Test fun oldMp4WithoutDashStillProvidesAnAudioPlaybackSource() {
        val source = AudioParser.parse(JSONObject("""{"format":"mp4","durl":[{"order":1,"length":10000,"url":"http://upos.bilivideo.com/synthetic.mp4","backup_url":["https://backup.bilivideo.com/synthetic.mp4"]}]}""")).single()
        assertEquals("https://upos.bilivideo.com/synthetic.mp4", source.url)
        assertEquals("video/mp4", source.mime)
        assertEquals(10000L, source.segments.single().durationMs)
        assertEquals(1, source.backups.size)
    }

    @Test fun flvSegmentsAreOrderedAndNeverConfusedWithBackups() {
        val source = AudioParser.parse(JSONObject("""{"format":"flv","durl":[{"order":2,"length":2000,"url":"https://upos.bilivideo.com/second.flv"},{"order":1,"length":1000,"url":"https://upos.bilivideo.com/first.flv"}]}""")).single()
        assertEquals(2, source.segments.size)
        assertTrue(source.segments.first().url.endsWith("first.flv"))
        assertEquals("video/x-flv", source.mime)
        assertEquals(3000L, source.segments.sumOf { it.durationMs })
        assertTrue(source.backups.isEmpty())
    }

    @Test(expected = ApiFailure::class)
    fun invalidLegacySegmentsCannotBeSilentlyDroppedOrSentToAnArbitraryHost() {
        AudioParser.parse(JSONObject("""{"durl":[{"url":"https://upos.bilivideo.com/one.mp4"},{"url":"https://evil.invalid/two.mp4"}]}"""))
    }

    @Test fun anInvalidPrimarySegmentMayUseAnAllowedBackup() {
        val source = AudioParser.parse(JSONObject("""{"durl":[{"url":"https://evil.invalid/one.mp4","backup_url":["https://upos.bilivideo.com/one.mp4"]}]}""")).single()
        assertEquals("https://upos.bilivideo.com/one.mp4", source.url)
    }

    @Test fun aResourceWithOnlyFlacIsNotRejectedForLackingStandardAudio() {
        val source = AudioParser.parse(JSONObject("""{"dash":{"flac":{"audio":{"id":30251,"baseUrl":"https://upos.bilivideo.com/synthetic.m4s"}}}}""")).single()
        assertEquals("Hi-Res", source.quality)
    }

    private val queue = (1..3).map { Track(it.toLong(), "BV000000000$it", "Synthetic $it", "Synthetic", "", 10) }

    @Test fun aFailedSongAdvancesToTheNextQueueOccurrence() {
        assertEquals(1, PlaybackRecovery.next(queue, 0, setOf(queue[0].key), QueueMode.Sequential))
        assertEquals(2, PlaybackRecovery.next(queue, 1, setOf(queue[0].key, queue[1].key), QueueMode.RepeatOne))
    }

    @Test fun allFailedSongsStopInEveryPlaybackMode() {
        for (mode in QueueMode.entries) assertNull(PlaybackRecovery.next(queue, 2, queue.map { it.key }.toSet(), mode))
        assertNull(PlaybackRecovery.next(emptyList(), -1, emptySet(), QueueMode.RepeatAll))
    }

    @Test fun sequentialModeStopsAtTheEndButRepeatAllCanTryEarlierSongs() {
        assertNull(PlaybackRecovery.next(queue, 2, setOf(queue[2].key), QueueMode.Sequential))
        assertEquals(0, PlaybackRecovery.next(queue, 2, setOf(queue[2].key), QueueMode.RepeatAll))
    }

    @Test fun shuffleAndDuplicateOccurrencesCannotReenterFailedSongs() {
        val failed = setOf(queue[0].key, queue[1].key)
        for (seed in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE))
            assertEquals(2, PlaybackRecovery.next(queue, 0, failed, QueueMode.Shuffle, seed))
        assertNull(PlaybackRecovery.next(listOf(queue[0], queue[0]), 0, setOf(queue[0].key), QueueMode.RepeatAll))
    }

    @Test fun loginRateLimitingAndNetworkFailuresNeverStartAQueueWideRetryStorm() {
        for (code in listOf(-101, -111, -352, -403, 401, 403, 412, 429, 500, 503))
            assertFalse(PlaybackRecovery.canSkipApi(code))
        for (code in listOf(-1, -404, 404, 410, 62002)) assertTrue(PlaybackRecovery.canSkipApi(code))
    }
}
