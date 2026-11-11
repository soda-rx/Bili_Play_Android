package io.github.sodarx.biliplay.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NetworkBoundaryTest {
    @Test
    fun trackIdentitySurvivesResolutionAndSeparatesParts() {
        val unresolved = Track(1, "BV17K5r6nEod", "合成曲目", "合成作者", "", 30)
        assertEquals(unresolved.key, unresolved.copy(cid = 321).key)
        assertNotEquals(unresolved.key, unresolved.copy(page = 2).key)
    }

    @Test
    fun cookieStoragePreservesDomainPathAndSessionScope() {
        val cookies =
            listOf(
                Cookie.Builder()
                    .name("SESSDATA")
                    .value("synthetic")
                    .hostOnlyDomain("passport.bilibili.com")
                    .path("/login")
                    .secure()
                    .httpOnly()
                    .build(),
                Cookie.Builder()
                    .name("bili_jct")
                    .value("synthetic")
                    .domain("bilibili.com")
                    .path("/")
                    .secure()
                    .expiresAt(2000000000000)
                    .build(),
            )
        val restored = CookieJson.decode(CookieJson.encode(cookies))
        assertEquals(cookies, restored)
        assertTrue(restored[0].hostOnly)
        assertFalse(restored[0].persistent)
        assertFalse(restored[0].matches("https://api.bilibili.com/login".toHttpUrl()))
    }

    @Test
    fun retiredSessionCannotBeRestored() {
        val jar = SessionCookies()
        jar.invalidate()
        jar.restore(
            listOf(
                Cookie.Builder().name("SESSDATA").value("synthetic").domain("bilibili.com").build()
            )
        )
        assertTrue(jar.snapshot().isEmpty())
    }

    private fun redirectClient(location: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Synthetic")
                    .header("Location", location)
                    .body(ByteArray(0).toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun sharedShortLinkResolvesVideoAndPart() = runBlocking {
        val api =
            BilibiliApi(client = redirectClient("https://www.bilibili.com/video/BV17K5r6nEod?p=3"))
        assertEquals(
            VideoInput("BV17K5r6nEod", 3),
            api.resolveVideoInput("https://b23.tv/synthetic"),
        )
        assertEquals(VideoInput("", 1, 123), api.resolveVideoInput("av123"))
    }

    @Test
    fun shortLinkRejectsForeignOriginAndLoops() = runBlocking {
        for (location in
            listOf(
                "https://evil.test/video/BV17K5r6nEod",
                "http://b23.tv/next",
                "https://b23.tv/loop",
            )) {
            assertTrue(
                runCatching {
                    BilibiliApi(client = redirectClient(location))
                        .resolveVideoInput("https://b23.tv/synthetic")
                }
                    .exceptionOrNull() != null
            )
        }
        assertNull(VideoLinks.parse("https://www.bilibili.com.evil.test/video/BV17K5r6nEod"))
        assertFalse(VideoLinks.allowedShortLink("https://b23.tv:8443/a"))
    }

    @Test
    fun notificationNumericCursorFlagsAndDeduplication() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val row =
                """{"id":7,"like_time":1700000000,"users":[{"nickname":"合成用户"}],"item":{"source_content":"合成内容"}}"""
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"code":0,"data":{"latest":{"items":[$row]},"total":{"items":[$row],"cursor":{"is_end":0,"id":7,"time":1700000000}}}}"""
                    )
            )
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"code":0,"data":{"total":{"items":[],"cursor":{"is_end":1,"id":7}}}}"""
                    )
            )
            val api = BilibiliApi(apiBase = server.url("/"))
            val first = api.notifications("like")
            assertEquals(1, first.items.size)
            assertTrue(first.hasMore)
            assertEquals("7", first.cursor["id"])
            val second = api.notifications("like", first.cursor)
            assertFalse(second.hasMore)
            server.takeRequest()
            assertTrue(server.takeRequest().path!!.contains("like_time=1700000000"))
        }
    }

    @Test
    fun audioTransferUsesBackupAndReportsExactBytes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val payload = "synthetic audio fixture".repeat(1000)
            server.enqueue(MockResponse().setBody(payload))
            val target = File.createTempFile("bili-synthetic-", ".source")
            try {
                val progress = mutableListOf<Long>()
                AudioDownloader(allowed = { it.startsWith(server.url("/").toString()) }).download(
                    listOf(server.url("/primary").toString(), server.url("/backup").toString()),
                    target,
                ) { count, _ ->
                    progress += count
                }
                assertEquals(payload, target.readText())
                assertEquals(target.length(), progress.last())
                assertEquals(2, server.requestCount)
            } finally {
                target.delete()
            }
        }
    }

    @Test
    fun audioRedirectCannotEscapeTrustedOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", "https://evil.test/audio")
            )
            val target = File.createTempFile("bili-synthetic-", ".source")
            try {
                val failure = runCatching {
                    AudioDownloader(allowed = { it.startsWith(server.url("/").toString()) })
                        .download(listOf(server.url("/redirect").toString()), target) { _, _ -> }
                }
                    .exceptionOrNull()
                assertTrue(failure is java.io.IOException)
                assertEquals(0L, target.length())
                assertEquals(1, server.requestCount)
            } finally {
                target.delete()
            }
        }
    }

    @Test
    fun cancellationClosesTransferBeforeReturning() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setBody("x".repeat(1000000))
                    .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
            )
            val target = File.createTempFile("bili-synthetic-", ".source")
            try {
                val began = CompletableDeferred<Unit>()
                val job =
                    launch(Dispatchers.Default) {
                        AudioDownloader(allowed = { it.startsWith(server.url("/").toString()) })
                            .download(listOf(server.url("/slow").toString()), target) { count, _ ->
                                if (count > 0) began.complete(Unit)
                            }
                    }
                withTimeout(5000) {
                    began.await()
                    job.cancelAndJoin()
                }
                assertTrue(job.isCancelled)
                assertTrue(target.delete())
                assertFalse(target.exists())
            } finally {
                target.delete()
            }
        }
    }
}
