package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PersonalMusicApiTest {
    private fun calls() = java.util.Collections.synchronizedList(mutableListOf<Request>())

    private fun card() =
        """<div class="feed-card"><a href="https://www.bilibili.com/video/BV0000000900"><picture class="bili-video-card__cover"><img src="//i0.hdslb.com/bfs/archive/cover.jpg"></picture></a><h3 class="bili-video-card__title" title="Synthetic channel"></h3><a class="bili-video-card__author" href="//space.bilibili.com/900"><span title="Synthetic UP">Synthetic UP</span></a><span>03:00</span></div>"""

    private fun api(
        calls: MutableList<Request>,
        limited: Boolean = false,
        brokenHtml: Boolean = false,
    ) =
        BilibiliApi(
            client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        calls.add(req)
                        val path = req.url.encodedPath
                        val body =
                            when {
                                path.endsWith("/nav") ->
                                    """{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/bfs/wbi/abcdef0123456789abcdef0123456789.png"}}}"""
                                path.endsWith("/arc/search") -> {
                                    if (limited) """{"code":-352}"""
                                    else {
                                        val uid = req.url.queryParameter("mid")!!.toLong()
                                        val rows =
                                            (0..29).joinToString(",") { i ->
                                                val bv =
                                                    "BV" +
                                                        (uid % 1000000 * 100 + i)
                                                            .toString()
                                                            .padStart(10, '0')
                                                """{"aid":$i,"bvid":"$bv","title":"Synthetic $i","author":"Synthetic UP","mid":$uid,"pic":"//i0.hdslb.com/a.jpg","length":"03:00"}"""
                                            }
                                        """{"code":0,"data":{"list":{"vlist":[$rows]},"page":{"count":90}}}"""
                                    }
                                }
                                path == "/c/music/" ->
                                    if (brokenHtml) "<html>No cards</html>" else card()
                                path.endsWith("/region/feed/rcmd") ->
                                    """{"code":0,"data":{"archives":[{"aid":900,"bvid":"BV0000000900","title":"Synthetic channel","cover":"//i0.hdslb.com/a.jpg","author":{"mid":900,"name":"Synthetic UP"},"duration":180},{"aid":901,"bvid":"BV0000000901","title":"Non music","tid":17}]}}"""
                                else -> """{"code":-404}"""
                            }
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("Synthetic")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    .build()
        )

    @Test
    fun homeUsesRenderedMusicChannelAndEighteenRotatingCreatorSongs() = runBlocking {
        val calls = calls()
        val api = api(calls)
        val page = api.desktopMusic()
        assertEquals(18, page.items.count { it.uploaderId in PreferredMusic.uploaders })
        assertTrue(page.items.any { it.bvid == "BV0000000900" })
        assertTrue(calls.any { it.url.encodedPath == "/c/music/" })
        assertFalse(calls.any { it.url.encodedPath.endsWith("/region/feed/rcmd") })
        assertTrue(
            calls
                .filter { it.url.encodedPath.endsWith("/arc/search") }
                .all { it.url.queryParameter("tid") == "3" }
        )
    }

    @Test
    fun pagingUsesTheSameMusicChannelBundleParametersAsDesktop() = runBlocking {
        val calls = calls()
        val api = api(calls)
        val page = api.desktopMusic(2)
        val request = calls.single { it.url.encodedPath.endsWith("/region/feed/rcmd") }
        assertEquals("1003", request.url.queryParameter("from_region"))
        assertEquals("2", request.url.queryParameter("display_id"))
        assertEquals("15", request.url.queryParameter("request_cnt"))
        assertEquals("333.40138", request.url.queryParameter("web_location"))
        assertEquals("https://www.bilibili.com/c/music/", request.header("Referer"))
        assertFalse(page.items.any { it.bvid == "BV0000000901" })
        assertEquals("Synthetic UP", page.items.single { it.bvid == "BV0000000900" }.uploader)
    }

    @Test
    fun threeConsecutivePagesRotateAllNineCreators() = runBlocking {
        val api = api(calls())
        val owners =
            (2..4)
                .flatMap { api.desktopMusic(it).items }
                .map { it.uploaderId }
                .filter { it in PreferredMusic.uploaders }
                .toSet()
        assertEquals(PreferredMusic.uploaders.toSet(), owners)
    }

    @Test
    fun creatorArchiveCacheAvoidsRepeatedThirtyItemRequests() = runBlocking {
        // Use the same client and cursor twice; cached artist pages supply the second slice.
        val logged = calls()
        val cached = api(logged)
        cached.desktopMusic(2)
        val before = logged.count { it.url.encodedPath.endsWith("/arc/search") }
        cached.desktopMusic(2)
        assertEquals(3, before)
        assertEquals(before, logged.count { it.url.encodedPath.endsWith("/arc/search") })
    }

    @Test
    fun artistRateLimitLeavesChannelAndStopsFollowingArchiveRequests() = runBlocking {
        val calls = calls()
        val api = api(calls, limited = true)
        assertEquals(listOf("BV0000000900"), api.desktopMusic(2).items.map { it.bvid })
        val before = calls.count { it.url.encodedPath.endsWith("/arc/search") }
        assertTrue(before in 1..2)
        api.desktopMusic(3)
        assertEquals(before, calls.count { it.url.encodedPath.endsWith("/arc/search") })
    }

    @Test
    fun unavailableRenderedCardsFallBackToScopedChannelFeed() = runBlocking {
        val calls = calls()
        val api = api(calls, brokenHtml = true)
        assertTrue(api.desktopMusic().items.any { it.bvid == "BV0000000900" })
        assertTrue(calls.any { it.url.encodedPath.endsWith("/region/feed/rcmd") })
    }

    @Test
    fun semanticCoverBeatsAnAvatarAndEntitiesAreDecoded() {
        val html =
            card()
                .replace("<a href=", "<img src=\"//i0.hdslb.com/bfs/face/avatar.jpg\"><a href=")
                .replace("Synthetic channel", "Synthetic &quot;Song&quot;")
        val song = MusicChannelPage.parse(html).single()
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", song.cover)
        assertEquals("Synthetic \"Song\"", song.title)
        assertEquals(180L, song.duration)
        assertEquals(900L, song.uploaderId)
    }

    @Test
    fun nonCardBannersAndAmbiguousBareImagesAreNotTracks() {
        assertTrue(
            MusicChannelPage.parse(
                    "<a href=\"https://www.bilibili.com/video/BV0000000900\">banner</a>"
                )
                .isEmpty()
        )
        val ambiguous =
            """<div class="feed-card"><a href="https://www.bilibili.com/video/BV0000000900"></a><h3 class="bili-video-card__title" title="synthetic"></h3><img src="//i0.hdslb.com/a.jpg"><img src="//i0.hdslb.com/b.jpg"></div>"""
        assertTrue(MusicChannelPage.parse(ambiguous).isEmpty())
    }
}
