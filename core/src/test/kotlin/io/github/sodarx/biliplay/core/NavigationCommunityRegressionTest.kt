package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NavigationCommunityRegressionTest {
    private fun api(jar: SessionCookies = SessionCookies(), response: (Request) -> String) =
        BilibiliApi(
            sessions = jar,
            client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("Synthetic")
                            .body(
                                response(request).toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                    .build(),
        )

    @Test
    fun activeNavigationIsAbovePagerButHiddenChromeCannotInterceptSecondaryPages() {
        for (route in CoverPresentation.primaryRoutes) assertTrue(
            CoverPresentation.navigationLayer(route, true, false) >
                CoverPresentation.sceneLayer(route)
        )
        for (route in listOf("player", "queue", "settings", "conversation")) assertTrue(
            CoverPresentation.navigationLayer(route, true, false) <
                CoverPresentation.sceneLayer(route)
        )
        assertTrue(CoverPresentation.navigationLayer("home", true, true) < 0f)
        assertTrue(CoverPresentation.navigationLayer("home", false, false) < 0f)
        assertTrue(
            CoverPresentation.navigationLayer("home", true, false, playbackInFront = true) < 0f
        )
    }

    @Test
    fun homeIconScrollsBeforeRefreshingAndDoesNotRestartAnActiveRefresh() {
        assertEquals(HomeTabAction.ScrollToTop, HomeTabAction.choose(true, false))
        assertEquals(HomeTabAction.Refresh, HomeTabAction.choose(false, false))
        assertEquals(HomeTabAction.None, HomeTabAction.choose(false, true))
        assertEquals(HomeTabAction.ScrollToTop, HomeTabAction.choose(true, true))
    }

    @Test
    fun panelKeepsOneHeightRegardlessOfKeyboardSize() {
        assertEquals(280f, EmojiPanelLayout.targetHeight(800f), 0f)
        assertTrue(EmojiPanelLayout.targetHeight(800f) >= 200f)
        assertTrue(EmojiPanelLayout.targetHeight(Float.NaN).isFinite())
        assertTrue(EmojiPanelLayout.targetHeight(800f) <= 360f)
    }

    @Test
    fun panelTakesOverTheKeyboardSpaceWithoutDoubleInsetsOrNegativeConstraints() {
        assertEquals(0, EmojiPanelLayout.remaining(300, 300, 600))
        assertEquals(150, EmojiPanelLayout.remaining(300, 150, 600))
        assertEquals(300, EmojiPanelLayout.remaining(300, 0, 600))
        assertEquals(0, EmojiPanelLayout.remaining(300, 400, 600))
        assertEquals(180, EmojiPanelLayout.remaining(300, 0, 180))
        assertEquals(0, EmojiPanelLayout.remaining(-1, 20, -1))
    }

    @Test
    fun notificationLinksOpenOriginalContentWithQueryAndAnchorPreserved() {
        assertEquals(
            "https://www.bilibili.com/video/av99?comment_root_id=42#reply42",
            NotificationLinks.resolve("bilibili://video/99?comment_root_id=42#reply42"),
        )
        assertEquals(
            "https://space.bilibili.com/42",
            NotificationLinks.resolve("bilibili://space/42"),
        )
        assertEquals(
            "https://t.bilibili.com/42",
            NotificationLinks.resolve("bilibili://following/detail/42"),
        )
        assertEquals(
            "https://www.bilibili.com/video/BV0000000099?p=2",
            NotificationLinks.resolve("//www.bilibili.com/video/BV0000000099?p=2"),
        )
        assertEquals(
            "https://b23.tv/synthetic",
            NotificationLinks.resolve("https://b23.tv/synthetic"),
        )
    }

    @Test
    fun notificationLinksRejectForeignOriginsCredentialsAndUnsupportedSchemes() {
        listOf(
                "https://bilibili.com.evil.test/path",
                "https://user@www.bilibili.com/",
                "https://www.bilibili.com:8443/",
                "javascript:alert(1)",
                "intent://video/99",
                "bilibili://video/../99",
                "",
            )
            .forEach { assertNull(NotificationLinks.resolve(it)) }
    }

    @Test
    fun systemNotificationKeepsTheDirectContentLink() = runBlocking {
        val notice = api {
            """{"code":0,"data":{"system_notify_list":[{"id":42,"title":"Synthetic notice","content":"Synthetic message","url":"https://www.bilibili.com/video/av99"}]}}"""
        }
            .notifications("system")
            .items
            .single()
        assertEquals("https://www.bilibili.com/video/av99", notice.url)
    }

    @Test
    fun followAndSecondClickUnfollowUseOppositeActions() = runBlocking {
        val actions = mutableListOf<String>()
        val jar = SessionCookies().apply { importHeader("bili_jct=synthetic_csrf") }
        val client =
            api(jar) { req ->
                assertEquals("POST", req.method)
                assertTrue(req.url.encodedPath.endsWith("/x/relation/modify"))
                val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("fid=42"))
                actions += body.substringAfter("act=").substringBefore('&')
                """{"code":0,"data":{}}"""
            }
        client.follow(42, true)
        client.follow(42, false)
        assertEquals(listOf("1", "2"), actions)
    }
}
