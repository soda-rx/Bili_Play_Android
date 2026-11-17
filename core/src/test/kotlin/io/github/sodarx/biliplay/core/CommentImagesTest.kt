package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommentImagesTest {
    private val bytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
    private fun payload() = CommentImageUpload(bytes, "image/png", 64, 32)
    private fun api(server: MockWebServer) = BilibiliApi(apiBase = server.url("/"),
        sessions = SessionCookies().apply { importHeader("bili_jct=synthetic_csrf") })
    private val picture = CommentPicture("https://i0.hdslb.com/bfs/new_dyn/synthetic.png", 64, 32, 1.25)

    @Test fun commentPicturesAcceptOnlyBfsImagesAndNormalizeLegacyHttp() {
        val content = JSONObject("""{"pictures":[{"img_src":"http://i0.hdslb.com/bfs/new_dyn/synthetic.png","img_width":64,"img_height":32},{"img_src":"https://evil.invalid/image.png"},{"img_src":"https://user@i0.hdslb.com/bfs/a.png"}]}""")
        val images = CommentImages.parse(content)
        assertEquals(1, images.size)
        assertEquals(picture.url, images.single().url)
        assertEquals(64, images.single().width)
        assertEquals(32, images.single().height)
        for (url in listOf("file:///private/picture.png", "content://private/picture", "https://i0.hdslb.com.evil.invalid/bfs/a.png",
            "https://i0.hdslb.com:8080/bfs/a.png", "https://i0.hdslb.com/other/a.png")) assertNull(CommentImages.url(url))
    }

    @Test fun malformedDimensionsAndCountsCannotCreateUnboundedLayouts() {
        val content = JSONObject().put("pictures", JSONArray().apply {
            repeat(30) { put(JSONObject().put("img_src", "https://i0.hdslb.com/bfs/synthetic/$it.png")
                .put("img_width", Int.MAX_VALUE).put("img_height", -1)) }
        })
        val images = CommentImages.parse(content)
        assertEquals(9, images.size)
        assertTrue(images.all { it.width == 32768 && it.height == 1 })
        assertTrue(CommentImages.parse(null).isEmpty())
    }

    @Test fun topLevelAndReplyResponsesRetainTheirPictures() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val row = """{"rpid":7,"member":{"mid":9,"uname":"Synthetic"},"content":{"message":"Synthetic","pictures":[{"img_src":"https://i0.hdslb.com/bfs/new_dyn/synthetic.png","img_width":64,"img_height":32}]}}"""
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"page":{"count":1},"replies":[$row]}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"page":{"count":1},"replies":[$row]}}"""))
            assertEquals(picture.url, api(server).comments(42, false).items.single().pictures.single().url)
            assertEquals(picture.url, api(server).replies(42, 7).items.single().pictures.single().url)
        }
    }

    @Test fun uploadingUsesMultipartCsrfAndAnAnonymousFilename() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"image_url":"http://i0.hdslb.com/bfs/new_dyn/synthetic.png","image_width":64,"image_height":32,"img_size":1.25}}"""))
            assertEquals(picture, api(server).uploadCommentImage(payload()))
            val request = server.takeRequest()
            assertEquals("/x/dynamic/feed/draw/upload_bfs", request.requestUrl!!.encodedPath)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"csrf\""))
            assertTrue(body.contains("synthetic_csrf"))
            assertTrue(body.contains("name=\"file_up\"; filename=\"comment.png\""))
            assertTrue(body.contains("image/png"))
            assertTrue(body.contains("new_dyn"))
            assertFalse(body.contains("content://"))
        }
    }

    @Test fun anImageOnlyReplyIncludesPictureMetadataAndPreservesItsTarget() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            api(server).sendComment(42, "", root = 7, parent = 8, pictures = listOf(picture))
            val body = server.takeRequest().body.readUtf8()
            val fields = body.split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals("7", fields["root"])
            assertEquals("8", fields["parent"])
            assertEquals("42", fields["oid"])
            assertEquals("[图片]", fields["message"])
            assertEquals("synthetic_csrf", fields["csrf"])
            val image = JSONArray(fields.getValue("pictures")).getJSONObject(0)
            assertEquals(picture.url, image.getString("img_src"))
            assertEquals(64, image.getInt("img_width"))
            assertEquals(1.25, image.getDouble("img_size"), 0.0)
        }
    }

    @Test fun textOnlyCommentsKeepTheirExistingRequestFormat() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            api(server).sendComment(42, "Synthetic")
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("message=Synthetic"))
            assertFalse(body.contains("pictures="))
            assertFalse(body.contains("root="))
        }
    }

    @Test fun missingLoginNeverUploadsAnything() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val failure = runCatching { BilibiliApi(apiBase = server.url("/")).uploadCommentImage(payload()) }.exceptionOrNull()
            assertTrue(failure is ApiFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun untrustedUploadResponsesCannotBeUsedForPosting() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"image_url":"https://evil.invalid/synthetic.png","image_width":64,"image_height":32}}"""))
            assertTrue(runCatching { api(server).uploadCommentImage(payload()) }.exceptionOrNull() is ApiFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun uploadingNeverFollowsARedirectWithImageDataOrCredentials() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/not-trusted")))
            assertTrue(runCatching { api(server).uploadCommentImage(payload()) }.exceptionOrNull() is ApiFailure)
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun invalidOrExcessAttachmentsFailBeforeAnyNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            assertTrue(runCatching { api(server).sendComment(42, "Synthetic", pictures = List(10) { picture }) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api(server).sendComment(42, "Synthetic", pictures = listOf(picture.copy(url = "https://evil.invalid/a"))) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        }
        assertTrue(runCatching { CommentImageUpload(bytes, "image/jpeg", 64, 32) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CommentImageUpload(bytes, "image/png", 100000, 32) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CommentImageUpload(ByteArray(CommentImages.MaxUploadBytes + 1), "image/png", 64, 32) }.exceptionOrNull() is IllegalArgumentException)
    }
}
