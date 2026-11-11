package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackRegressionTest {
    private val nav =
        """{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
    private val audio =
        """{"code":0,"data":{"dash":{"audio":[{"codecs":"mp4a.40.2","baseUrl":"http://upos.bilivideo.com/audio.m4s?synthetic=1","backupUrl":["//mirror.bilivideo.com/backup.m4s"]}]}}}"""
    private val track = Track(1, "BV17K5r6nEod", "synthetic", "synthetic", "", 60, 100)

    private fun client(handler: (Request) -> String) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("Synthetic")
                    .body(handler(chain.request()).toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun startupCannotSkipAppearanceOrAuthentication() {
        assertEquals(StartupStep.Agreement, StartupPolicy.step(false, false, false))
        assertEquals(StartupStep.Appearance, StartupPolicy.step(true, false, false))
        assertEquals(StartupStep.Appearance, StartupPolicy.step(true, false, true))
        assertEquals(StartupStep.Login, StartupPolicy.step(true, true, false))
        assertEquals(StartupStep.Ready, StartupPolicy.step(true, true, true))
    }

    @Test
    fun upgradesOnlyTrustedMediaToHttps() {
        assertEquals(
            "https://upos.bilivideo.com/a?token=synthetic",
            MediaUrls.normalize("http://upos.bilivideo.com/a?token=synthetic"),
        )
        assertEquals("https://upos.bilivideo.com/a", MediaUrls.normalize("//upos.bilivideo.com/a"))
        for (url in
            listOf(
                "http://evil.test/a",
                "http://user@upos.bilivideo.com/a",
                "http://upos.bilivideo.com:8443/a",
                "file:///local/a",
                "http:/invalid",
                "http:opaque",
            )) assertNull(MediaUrls.normalize(url))
        assertFalse(MediaUrls.allowed("http://upos.bilivideo.com/a"))
    }

    @Test
    fun audioParserKeepsUpgradedPrimaryAndBackup() {
        val source = AudioParser.parse(JSONObject(audio).getJSONObject("data")).single()
        assertEquals("https://upos.bilivideo.com/audio.m4s?synthetic=1", source.url)
        assertEquals(listOf("https://mirror.bilivideo.com/backup.m4s"), source.backups)
    }

    @Test
    fun nonVipAsksForDashAndUsesPageWhenApiHasNoAudio() = runBlocking {
        val paths = mutableListOf<String>()
        val api =
            BilibiliApi(
                client =
                    client { request ->
                        paths += request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/x/web-interface/nav" -> nav
                            "/x/player/wbi/playurl" -> {
                                assertEquals("16", request.url.queryParameter("fnval"))
                                """{"code":0,"data":{"durl":[{}]}}"""
                            }
                            else -> "<html><script>window.__playinfo__=$audio;</script></html>"
                        }
                    }
            )
        assertEquals(1, api.audio(track).size)
        assertEquals(
            listOf("/x/web-interface/nav", "/x/player/wbi/playurl", "/video/BV17K5r6nEod/"),
            paths,
        )
    }

    @Test
    fun legacyApiIsTriedWhenPageHasNoPlaybackJson() = runBlocking {
        val api =
            BilibiliApi(
                client =
                    client { request ->
                        when (request.url.encodedPath) {
                            "/x/web-interface/nav" -> nav
                            "/x/player/wbi/playurl" -> """{"code":0,"data":{}}"""
                            "/x/player/playurl" -> audio
                            else -> "<html>synthetic empty page</html>"
                        }
                    }
            )
        assertTrue(api.audio(track).isNotEmpty())
    }

    @Test
    fun vipRequestsExpandedDashCapability() = runBlocking {
        val api =
            BilibiliApi(
                client =
                    client { request ->
                        if (request.url.encodedPath.endsWith("/nav")) nav
                        else {
                            assertEquals("4048", request.url.queryParameter("fnval"))
                            audio
                        }
                    }
            )
        assertEquals(1, api.audio(track, hiRes = true).size)
    }

    @Test
    fun deniedAccountsNeverFallBackToPublicPages() = runBlocking {
        var requests = 0
        val api =
            BilibiliApi(
                client =
                    client { request ->
                        requests++
                        if (request.url.encodedPath.endsWith("/nav")) nav else """{"code":-101}"""
                    }
            )
        val error = runCatching { api.audio(track) }.exceptionOrNull()
        assertEquals(-101, (error as ApiFailure).code)
        assertEquals(2, requests)
    }

    @Test
    fun embeddedJsonHandlesBracesAndEscapesWithoutEvaluatingScripts() {
        val json = """{"code":0,"data":{"text":"brace } and \"quoted\"","nested":{}}}"""
        assertEquals(
            json,
            EmbeddedPlayback.read("<script>window.__playinfo__=$json;malicious();</script>"),
        )
        assertTrue(
            runCatching { EmbeddedPlayback.read("window.__playinfo__={") }.exceptionOrNull()
                is ApiFailure
        )
    }
}
