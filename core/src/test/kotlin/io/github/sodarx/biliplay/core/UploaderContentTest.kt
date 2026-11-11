package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UploaderContentTest {
    private fun api(requests: MutableList<HttpUrl>) =
        BilibiliApi(
            client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        requests.add(request.url)
                        val data =
                            when {
                                request.url.encodedPath.endsWith("/nav") ->
                                    """{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/bfs/wbi/abcdef0123456789abcdef0123456789.png"}}"""
                                request.url.encodedPath.endsWith("/acc/info") ->
                                    """{"mid":42,"name":"synthetic","face":"https://example.invalid/avatar.png","sign":"synthetic profile"}"""
                                else ->
                                    """{"list":{"vlist":[{"aid":1,"bvid":"BV17K5r6nEod","mid":42,"author":"synthetic","title":"synthetic upload","length":"1:00","pic":"https://example.invalid/cover.png"}]},"page":{"count":1}}"""
                            }
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("Synthetic")
                            .body(
                                ("{\"code\":0,\"data\":" + data + "}").toResponseBody(
                                    "application/json".toMediaType()
                                )
                            )
                            .build()
                    }
                    .build()
        )

    @Test
    fun creatorPageRequestsAllUploadsWithoutChangingMusicRecommendationFilter() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        val api = api(requests)
        val all = api.uploaderVideos(42, musicOnly = false)
        assertEquals(1, all.items.size)
        assertEquals(42L, all.items.first().uploaderId)
        assertEquals("0", requests.last().queryParameter("tid"))
        api.uploaderVideos(42)
        assertEquals("3", requests.last().queryParameter("tid"))
    }

    @Test
    fun profileProvidesRealAvatarAndSignatureBeforeUploads() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        val profile = api(requests).uploaderProfile(42)
        assertEquals(42L, profile.id)
        assertEquals("https://example.invalid/avatar.png", profile.avatar)
        assertEquals("synthetic profile", profile.signature)
    }
}
