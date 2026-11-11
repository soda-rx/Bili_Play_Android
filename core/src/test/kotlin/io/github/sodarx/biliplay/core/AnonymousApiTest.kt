package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AnonymousApiTest {
    private val nav =
        """{"code":-101,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""

    @Test
    fun anonymousPublicWbiKeysAllowSearch() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(nav))
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"code":0,"data":{"result":[{"aid":1,"bvid":"BV17K5r6nEod","title":"合成测试","author":"测试作者","duration":"03:10"}]}}"""
                    )
            )
            val tracks = BilibiliApi(apiBase = server.url("/")).search("合成测试")
            assertEquals(1, tracks.size)
            assertEquals(190L, tracks.single().duration)
            assertEquals("/x/web-interface/nav", server.takeRequest().path)
            val request = server.takeRequest()
            assertTrue(request.path!!.contains("w_rid="))
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test
    fun protectedAudioDoesNotFallBackAfterLoginRequired() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(nav))
            server.enqueue(MockResponse().setBody("""{"code":-101,"message":"login required"}"""))
            val failure = runCatching {
                BilibiliApi(apiBase = server.url("/"))
                    .audio(Track(1, "BV17K5r6nEod", "合成测试", "测试作者", "", 190, 1))
            }
                .exceptionOrNull()
            assertTrue(failure is ApiFailure)
            assertEquals(-101, (failure as ApiFailure).code)
            assertEquals(2, server.requestCount)
        }
    }
}
