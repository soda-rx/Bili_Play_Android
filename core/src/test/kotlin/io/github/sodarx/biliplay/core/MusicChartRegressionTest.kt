package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MusicChartRegressionTest {
    private fun api(body: (String) -> String): BilibiliApi =
        BilibiliApi(
            client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                body(chain.request().url.encodedPath)
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                    .build()
        )

    @Test
    fun regionArrayWithTypenameAndNoTidIsMusicAndSortedByPlays() = runBlocking {
        val client = api { path ->
            assertTrue(path.endsWith("/region"))
            """{"code":0,"data":[
            {"aid":1,"bvid":"BV0000000001","title":"synthetic one","typename":"翻唱","play":40},
            {"aid":2,"bvid":"BV0000000002","title":"synthetic two","typename":"MV","play":900},
            {"aid":3,"bvid":"BV0000000003","title":"synthetic unrelated","typename":"日常","play":90000},
            {"aid":4,"bvid":"BV0000000004","title":"synthetic misleading","tid":21,"typename":"MV","play":80000}]}"""
        }
        assertEquals(
            listOf("BV0000000002", "BV0000000001"),
            client.recentMusicLeaders().map { it.bvid },
        )
    }

    @Test
    fun emptyParsedRegionalResultFallsBackToModernMusicChart() = runBlocking {
        val paths = mutableListOf<String>()
        val client = api { path ->
            paths += path
            if (path.endsWith("/region"))
                """{"code":0,"data":[{"bvid":"BV0000000001","title":"synthetic unrelated","typename":"日常"}]}"""
            else
                """{"code":0,"data":{"list":[{"aid":2,"bvid":"BV0000000002","title":"synthetic song","tid":31,"stat":{"view":1000}}]}}"""
        }
        assertEquals("BV0000000002", client.recentMusicLeaders().single().bvid)
        assertEquals(
            listOf("/x/web-interface/ranking/region", "/x/web-interface/ranking/v2"),
            paths,
        )
    }

    @Test
    fun loginFailuresAreNotSilentlyRetriedThroughAnotherSource() = runBlocking {
        var calls = 0
        val client = api {
            calls++
            """{"code":-101}"""
        }
        try {
            client.recentMusicLeaders()
            fail("login failure must propagate")
        } catch (e: ApiFailure) {
            assertTrue(e.requiresLogin)
        }
        assertEquals(1, calls)
    }
}
