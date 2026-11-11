package io.github.sodarx.biliplay.core

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MaintenanceRegressionTest {
    @Test
    fun aNewBrowserWaitsForThePreviousCleanup() = runBlocking {
        val gate = ExclusiveLeaseGate()
        val first = gate.acquire()
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }
        assertFalse(second.isCompleted)
        first.close()
        withTimeout(1000) { second.await() }.close()
    }

    @Test
    fun cancellingAWaitingBrowserDoesNotReleaseTheActiveBrowser() = runBlocking {
        val gate = ExclusiveLeaseGate()
        val first = gate.acquire()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }
        cancelled.cancelAndJoin()
        val next = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }
        assertFalse(next.isCompleted)
        first.close()
        withTimeout(1000) { next.await() }.close()
    }

    @Test
    fun aDuplicateCloseCannotUnlockANewerBrowser() = runBlocking {
        val gate = ExclusiveLeaseGate()
        val old = gate.acquire()
        old.close()
        val current = gate.acquire()
        old.close()
        val next = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }
        assertFalse(next.isCompleted)
        current.close()
        withTimeout(1000) { next.await() }.close()
    }

    @Test
    fun failingAnOwnedOperationStillReleasesItsLease() = runBlocking {
        val gate = ExclusiveLeaseGate()
        try {
            gate.acquire().use { error("synthetic failure") }
        } catch (_: IllegalStateException) {}
        withTimeout(1000) { gate.acquire() }.close()
    }

    private val track = Track(42, "BV0000000042", "Synthetic", "Synthetic", "", 10, page = 2)
    private val first = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val second = UUID.fromString("00000000-0000-4000-8000-000000000002")

    @Test
    fun concurrentDownloadsOfOneSongCannotOverwriteEachOther() {
        assertNotEquals(
            DownloadNames.privateFile(track, first),
            DownloadNames.privateFile(track, second),
        )
    }

    @Test
    fun retryingTheSameJobReusesOnlyItsOwnDestination() {
        assertEquals(
            DownloadNames.privateFile(track, first),
            DownloadNames.privateFile(track, first),
        )
        assertTrue(DownloadNames.privateFile(track, first).endsWith("_$first.mp3"))
    }

    @Test
    fun remoteIdentifiersCannotEscapeTheDownloadDirectory() {
        val name = DownloadNames.privateFile(track.copy(bvid = "../../private/path"), first)
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertTrue(name.startsWith("av42_p2_"))
    }

    @Test
    fun invalidSavedPagesCannotCreateAnInvalidFileName() {
        assertTrue(DownloadNames.privateFile(track.copy(page = -1), first).contains("_p1_"))
    }
}
