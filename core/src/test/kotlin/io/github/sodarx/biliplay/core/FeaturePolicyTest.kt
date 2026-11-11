package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FeaturePolicyTest {
    @Test
    fun sequentialStopsAtEnd() {
        assertNull(QueuePolicy.nextIndex(3, 2, QueueMode.Sequential, true))
        assertEquals(0, QueuePolicy.nextIndex(3, 2, QueueMode.RepeatAll, true))
    }

    @Test
    fun repeatOneOnlyRepeatsForNaturalEnd() {
        assertEquals(1, QueuePolicy.nextIndex(3, 1, QueueMode.RepeatOne, true))
        assertEquals(2, QueuePolicy.nextIndex(3, 1, QueueMode.RepeatOne, false))
    }

    @Test
    fun shuffleNeverSelectsCurrentWhenAlternativesExist() {
        for (seed in -20..20) assertNotEquals(
            1,
            QueuePolicy.nextIndex(4, 1, QueueMode.Shuffle, true, random = seed),
        )
    }

    @Test
    fun emptyQueueCannotAdvance() {
        QueueMode.entries.forEach { assertNull(QueuePolicy.nextIndex(0, 0, it, true)) }
    }

    @Test
    fun originalLanguageBeatsTranslatedSubtitle() {
        val english = SubtitleTrack("en", "English", "https://i0.hdslb.com/a", true)
        assertEquals(
            english,
            SubtitlePolicy.choose(
                listOf(SubtitleTrack("zh", "中文翻译", "https://i0.hdslb.com/b", false), english),
                "English Song",
            ),
        )
    }

    @Test
    fun onlyActiveLyricIsHighlighted() {
        val lines = listOf(SubtitleLine(1.0, 2.0, "a"), SubtitleLine(3.0, 4.0, "b"))
        assertEquals(-1, SubtitlePolicy.active(lines, 2.5))
        assertEquals(1, SubtitlePolicy.active(lines, 3.1))
    }

    @Test
    fun subtitleParserRejectsBadIntervals() {
        val lines =
            parseSubtitle(
                JSONObject(
                    """{"body":[{"from":-1,"to":2,"content":"bad"},{"from":3,"to":2,"content":"bad"},{"from":1,"to":2,"content":"good"}]}"""
                )
            )
        assertEquals(listOf(SubtitleLine(1.0, 2.0, "good")), lines)
    }

    @Test
    fun uploaderPreferenceNeverPromotesUnmatchedMusic() {
        val a =
            Track(
                1,
                "BV17K5r6nEod",
                "Other song",
                "x",
                "",
                1,
                uploaderId = PreferredMusic.uploaders[0],
            )
        val b = a.copy(aid = 2, bvid = "BV1o14y1R7Ad", title = "Target song", uploaderId = 1)
        assertEquals(listOf(b, a), PreferredMusic.rank(listOf(b, a), "Target"))
        assertEquals(
            b,
            PreferredMusic.rank(
                    listOf(a, b.copy(uploaderId = PreferredMusic.uploaders[0])),
                    "Target",
                )
                .first()
                .let { it.copy(uploaderId = 1) },
        )
    }

    @Test
    fun sessionNeverLeavesBilibiliHttps() {
        val jar = SessionCookies()
        jar.importHeader("SESSDATA=synthetic; bili_jct=synthetic_csrf")
        assertEquals(2, jar.loadForRequest("https://api.bilibili.com/x".toHttpUrl()).size)
        listOf(
                "http://api.bilibili.com/x",
                "https://api.bilibili.com.evil.test/x",
                "https://i0.hdslb.com/a",
            )
            .forEach { assertTrue(jar.loadForRequest(it.toHttpUrl()).isEmpty()) }
    }

    @Test
    fun logoutCannotBeUndoneByLateResponses() {
        var writes = 0
        val jar = SessionCookies { writes++ }
        jar.importHeader("SESSDATA=synthetic")
        jar.clear()
        val count = writes
        jar.saveFromResponse(
            "https://api.bilibili.com/".toHttpUrl(),
            listOf(
                Cookie.Builder()
                    .name("SESSDATA")
                    .value("late_synthetic")
                    .domain("bilibili.com")
                    .secure()
                    .build()
            ),
        )
        jar.importHeader("SESSDATA=late_synthetic")
        assertNull(jar.value("SESSDATA"))
        assertEquals(count, writes)
    }

    @Test
    fun trackStorageRoundTripsLocalUriWithoutSessionData() {
        val t =
            Track(
                1,
                "BV17K5r6nEod",
                "测试",
                "合成作者",
                "",
                3,
                5,
                1,
                localUri = "content://synthetic/music/1",
            )
        assertEquals(t, TrackJson.decodeAll(TrackJson.encodeAll(listOf(t))).single())
    }

    @Test
    fun renamePreservesFolderMetadataAndCsrf() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(
                MockResponse()
                    .setBody(
                        """{"code":0,"data":{"intro":"preserve_intro","cover":"https://i0.hdslb.com/cover"}}"""
                    )
            )
            s.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            val jar = SessionCookies().apply { importHeader("bili_jct=synthetic_csrf") }
            val api = BilibiliApi(apiBase = s.url("/"), sessions = jar)
            api.editFolder(Folder(7, "old", 1, true), "new", true)
            s.takeRequest()
            val body = s.takeRequest().body.readUtf8()
            assertTrue(body.contains("intro=preserve_intro"))
            assertTrue(body.contains("csrf=synthetic_csrf"))
            assertTrue(body.contains("privacy=1"))
        }
    }

    @Test
    fun subscribedFolderCannotBeWritten() = runBlocking {
        val failure = runCatching {
            BilibiliApi().deleteFolder(Folder(1, "read only", 1, false))
        }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
