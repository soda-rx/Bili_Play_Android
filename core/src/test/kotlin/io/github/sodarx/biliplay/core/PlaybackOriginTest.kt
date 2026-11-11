package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackOriginTest {
    private val first = Track(1, "BV0000000001", "synthetic multi-P", "synthetic", "", 60, page = 1)

    @Test
    fun sourceCheckpointRoundTripsWithoutChangingItsIdentity() {
        val source = PlaybackOrigin("folder", "123", "synthetic collection")
        assertEquals(source, PlaybackOrigin.decode(source.encode()))
        assertNull(PlaybackOrigin.decode(""))
        assertNull(PlaybackOrigin.decode("""{"kind":"unknown","id":"1","title":"synthetic"}"""))
    }

    @Test
    fun legacyQueuesOnlyInferGenuineMultiPNotArbitrarySongs() {
        assertEquals(
            "parts",
            PlaybackOrigin.infer(first, listOf(first, first.copy(page = 2)))?.kind,
        )
        assertNull(PlaybackOrigin.infer(first, listOf(first, first.copy(bvid = "BV0000000002"))))
        assertNull(PlaybackOrigin.infer(first, listOf(first)))
    }

    @Test
    fun collectionAndMultiPAreDistinguishedAtPlaybackSelection() {
        val video = Video(first, listOf(first, first.copy(page = 2)))
        assertEquals("parts", PlaybackOrigin.fromVideo(video)?.kind)
        assertEquals(
            "synthetic series",
            PlaybackOrigin.fromVideo(
                    video.copy(collection = listOf(first), collectionTitle = "synthetic series")
                )
                ?.title,
        )
        assertNull(PlaybackOrigin.fromVideo(Video(first, listOf(first))))
    }
}
