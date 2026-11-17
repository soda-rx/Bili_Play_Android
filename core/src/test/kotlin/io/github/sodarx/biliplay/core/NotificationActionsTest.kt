package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class NotificationActionsTest {
    private fun note(
        id: String,
        category: String = "点赞",
        talkerId: Long = 0,
        sessionType: Int = 1,
        url: String = "",
    ) = Notification(
        id,
        category,
        "synthetic title",
        "synthetic content",
        1_700_000_000,
        url = url,
        talkerId = talkerId,
        sessionType = sessionType,
    )

    @Test
    fun likeReplyAndAtFeedsAreServerDeletedByTheirNumericId() {
        listOf("like" to "0", "reply" to "1", "at" to "2").forEach { (source, type) ->
            val item = note("$source:42")
            assertTrue(NotificationDeletion.serverSide(item))
            assertEquals(42L, NotificationDeletion.feedId(item))
            assertEquals(type, NotificationDeletion.feedType(item))
        }
    }

    @Test
    fun malformedFeedIdsAreNotDeletable() {
        for (id in listOf("like:", "like:0", "like:-2", "like:42:extra", "system:42", "")) {
            assertFalse(NotificationDeletion.serverSide(note(id)))
            assertFalse(NotificationDeletion.serverSide(note(id, category = "点赞")))
        }
    }

    @Test
    fun privateSessionsFollowTheVerifiedServerTypes() {
        val oneToOne = note("session:1", category = "私信", talkerId = 7, sessionType = 1)
        val group = note("session:2", category = "私信", talkerId = 8, sessionType = 2)
        val unsupported = note("session:3", category = "私信", talkerId = 9, sessionType = 5)
        assertTrue(NotificationDeletion.serverSide(oneToOne))
        assertTrue(NotificationDeletion.serverSide(group))
        assertFalse(NotificationDeletion.serverSide(unsupported))
        val missingTalker = note("session:4", category = "私信", talkerId = 0)
        assertFalse(NotificationDeletion.serverSide(missingTalker))
    }

    @Test
    fun systemNoticesAreHiddenLocallyInsteadOfDeletedRemotely() {
        val notice = note("system:abc", category = "系统通知")
        assertFalse(NotificationDeletion.serverSide(notice))
        assertNull(NotificationDeletion.feedId(notice))
    }

    @Test
    fun dismissedIdsAreDeduplicatedNewestFirstAndCapped() {
        val current = listOf("a", "b")
        assertEquals(listOf("b", "a"), NotificationDismissals.dismiss(current, "b"))
        assertEquals(listOf("c", "a", "b"), NotificationDismissals.dismiss(current, "c"))
        val many = (1..NotificationDismissals.MaxEntries).map { "id$it" }
        val capped = NotificationDismissals.dismiss(many, "newest")
        assertEquals(NotificationDismissals.MaxEntries, capped.size)
        assertEquals("newest", capped.first())
        assertEquals(NotificationDismissals.MaxEntries - 1, capped.distinct().size - 1)
    }

    @Test
    fun dismissalRecordRoundTripsAndRejectsGarbage() {
        val ids = listOf("like:1", "private:2")
        val encoded = NotificationDismissals.encode(ids)
        assertEquals(ids, NotificationDismissals.decode(encoded))
        assertEquals(emptyList<String>(), NotificationDismissals.decode("not json"))
        assertEquals(emptyList<String>(), NotificationDismissals.decode(""))
        assertEquals(listOf("a"), NotificationDismissals.decode("""["a","a",""]"""))
    }

    @Test
    fun filteringKeepsUnrelatedRowsAndNeverKeepsMessageText() {
        val rows = listOf(note("like:1", category = "系统通知"), note("like:2"))
        val filtered = NotificationDismissals.filter(rows, setOf("like:1"))
        assertEquals(listOf("like:2"), filtered.map { it.id })
        assertEquals(rows, NotificationDismissals.filter(rows, emptySet()))
        val encoded = NotificationDismissals.encode(listOf("like:1"))
        assertFalse(encoded.contains("synthetic"))
    }

    @Test
    fun relatedVideoParsingAcceptsVideoLinksAndPartNumbers() {
        assertEquals(VideoInput("BV1xx411c7mD"), NotificationLinks.video("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertEquals(
            VideoInput("BV1xx411c7mD", 3),
            NotificationLinks.video("//www.bilibili.com/video/BV1xx411c7mD?p=3&spm_id_from=333"),
        )
        assertEquals(
            VideoInput("", aid = 170001),
            NotificationLinks.video("https://www.bilibili.com/video/av170001"),
        )
    }

    @Test
    fun unrelatedOrUnsafeLinksFallBackToTheDetailScreen() {
        assertNull(NotificationLinks.video(""))
        assertNull(NotificationLinks.video("https://www.bilibili.com/read/cv123"))
        assertNull(NotificationLinks.video("https://example.com/video/BV1xx411c7mD"))
        assertNull(NotificationLinks.video("javascript:alert(1)"))
        assertNull(NotificationLinks.video("https://www.bilibili.com/video/BV1xx411c7mD.evil"))
        assertNull(NotificationLinks.shortVideoLink("https://example.com/x"))
        assertEquals(
            "https://b23.tv/abc",
            NotificationLinks.shortVideoLink("https://b23.tv/abc"),
        )
    }
}
