package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class InteractionPolicyTest {
    private val track = Track(123, "BV17K5r6nEod", "synthetic", "synthetic", "", 10)

    @Test
    fun reorderDownKeepsAllFolders() {
        assertEquals(listOf(2L, 3L, 1L, 4L), FolderOrder.move(listOf(1L, 2L, 3L, 4L), 1L, 3L))
    }

    @Test
    fun reorderUpKeepsAllFolders() {
        assertEquals(listOf(3L, 1L, 2L, 4L), FolderOrder.move(listOf(1L, 2L, 3L, 4L), 3L, 1L))
    }

    @Test
    fun missingAndSameTargetsDoNotCorruptOrder() {
        val ids = listOf(1L, 2L)
        assertEquals(ids, FolderOrder.move(ids, 1L, 1L))
        assertEquals(ids, FolderOrder.move(ids, 1L, 9L))
    }

    @Test
    fun reorderingCanBeReversedExactly() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(ids, FolderOrder.move(FolderOrder.move(ids, 1L, 3L), 1L, 2L))
    }

    @Test
    fun explicitCoinPostContainsCountCsrfAndNoImplicitLike() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"multiply":0}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            val jar = SessionCookies().apply { importHeader("bili_jct=synthetic_csrf") }
            BilibiliApi(apiBase = server.url("/"), sessions = jar).giveCoins(track, 2)
            assertTrue(server.takeRequest().path!!.contains("archive/coins"))
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/x/web-interface/coin/add", post.path)
            val body = post.body.readUtf8()
            assertTrue(body.contains("multiply=2"))
            assertTrue(body.contains("select_like=0"))
            assertTrue(body.contains("csrf=synthetic_csrf"))
        }
    }

    @Test
    fun coinLimitPreventsPostingAgain() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"multiply":2}}"""))
            assertTrue(
                runCatching { BilibiliApi(apiBase = server.url("/")).giveCoins(track, 1) }
                    .exceptionOrNull() is IllegalArgumentException
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun invalidCoinAmountIsRejectedBeforeAnyNetwork() = runBlocking {
        assertTrue(
            runCatching { BilibiliApi().giveCoins(track, 3) }.exceptionOrNull()
                is IllegalArgumentException
        )
    }
}
