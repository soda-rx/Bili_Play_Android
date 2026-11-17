package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ConversationAndMembershipTest {
    private fun api(block: (Request) -> String) =
        BilibiliApi(
            client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        assertEquals("GET", req.method)
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("Synthetic")
                            .body(block(req).toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    .build()
        )

    @Test
    fun conversationIsOrderedAndLimitedToBothParticipants() = runBlocking {
        val client = api { req ->
            assertEquals("api.vc.bilibili.com", req.url.host)
            assertTrue(req.url.encodedPath.endsWith("/fetch_session_msgs"))
            assertEquals("42", req.url.queryParameter("talker_id"))
            assertEquals("1", req.url.queryParameter("session_type"))
            """{"code":0,"data":{"has_more":1,"min_seqno":10,"messages":[{"msg_key":"own","msg_seqno":11,"sender_uid":123,"receiver_id":42,"msg_type":1,"timestamp":1730000000000,"content":"{\"content\":\"<hello>\"}"},{"msg_key":"peer","msg_seqno":10,"sender_uid":42,"receiver_id":123,"msg_type":1,"content":"{\"content\":\"你好\"}"},{"msg_seqno":12,"sender_uid":88,"receiver_id":123,"msg_type":1,"content":"foreign synthetic message"}]}}"""
        }
        val page = client.conversation(123, 42)
        assertEquals(listOf("peer", "own"), page.items.map { it.id })
        assertTrue(page.items.last().own)
        assertFalse(page.items.first().own)
        assertEquals("<hello>", page.items.last().text)
        assertEquals(1730000000, page.items.last().timestamp)
        assertTrue(page.hasMore)
        assertEquals(10, page.before)
    }

    @Test
    fun olderPageRejectsOverlappingSequencesAndUnsafeImages() = runBlocking {
        val client = api { req ->
            assertEquals("10", req.url.queryParameter("begin_seqno"))
            """{"code":0,"data":{"has_more":1,"min_seqno":9,"messages":[{"msg_key":"overlap","msg_seqno":10,"sender_uid":42,"receiver_id":123,"msg_type":1,"content":"old boundary"},{"msg_key":"image","msg_seqno":9,"sender_uid":42,"receiver_id":123,"msg_type":2,"content":"{\"url\":\"https://i0.hdslb.com/synthetic.png\"}"},{"msg_key":"unsafe","msg_seqno":8,"sender_uid":42,"receiver_id":123,"msg_type":2,"content":"{\"url\":\"https://hdslb.com.evil.test/image.png\"}"}]}}"""
        }
        val page = client.conversation(123, 42, 10)
        assertEquals(2, page.items.size)
        assertEquals("", page.items.first().image)
        assertEquals("https://i0.hdslb.com/synthetic.png", page.items.last().image)
        assertTrue(page.hasMore)
    }

    @Test
    fun nonAdvancingCursorCannotLoop() = runBlocking {
        val page = api {
            """{"code":0,"data":{"has_more":1,"min_seqno":10,"messages":[{"msg_key":"older","msg_seqno":9,"sender_uid":42,"receiver_id":123,"msg_type":1,"content":"plain"}]}}"""
        }.conversation(123, 42, 10)
        assertFalse(page.hasMore)
    }

    @Test
    fun invalidConversationNeverMakesARequest() {
        val client = api { error("Invalid request reached transport") }
        listOf(Triple(0L, 42L, 0L), Triple(123L, 123L, 0L), Triple(123L, 42L, -1L)).forEach {
            (self, peer, before) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.conversation(self, peer, before) }
            }
        }
    }

    @Test
    fun recalledDuplicateAndPlainMessagesRemainReadable() = runBlocking {
        val page = api {
            """{"code":0,"data":{"has_more":0,"messages":[{"msg_key":"plain","msg_seqno":1,"sender_uid":42,"receiver_id":123,"msg_type":1,"content":"plain synthetic"},{"msg_key":"plain","msg_seqno":1,"sender_uid":42,"receiver_id":123,"msg_type":1,"content":"duplicate"},{"msg_key":"recall","msg_seqno":2,"sender_uid":123,"receiver_id":42,"msg_type":5,"content":"{}"}]}}"""
        }.conversation(123, 42)
        assertEquals(listOf("plain synthetic", "[消息已撤回]"), page.items.map { it.text })
        assertFalse(page.hasMore)
    }

    @Test
    fun perFolderMembershipIsReadForTheRequestedVideo() = runBlocking {
        val membership = api { req ->
            assertEquals("42", req.url.queryParameter("up_mid"))
            assertEquals("99", req.url.queryParameter("rid"))
            assertEquals("2", req.url.queryParameter("type"))
            """{"code":0,"data":{"list":[{"id":1,"fav_state":1},{"id":2,"fav_state":0},{"id":3,"fav_state":true},{"id":0,"fav_state":1}]}}"""
        }
            .favoriteMembership(Track(99, "BV0000000099", "Synthetic", "UP", "", 1), 42)
        assertEquals(mapOf(1L to true, 2L to false, 3L to true), membership)
    }

    @Test
    fun accountIdentityAndSessionTargetUsePlatformFields() = runBlocking {
        val client = api { req ->
            if (req.url.encodedPath.endsWith("/nav"))
                """{"code":0,"data":{"isLogin":true,"mid":123,"uname":"Synthetic","vip":{"status":1},"level_info":{"current_level":6}}}"""
            else
                """{"code":0,"data":{"session_list":[{"talker_id":42,"session_type":1,"last_msg":{"content":"{\"content\":\"Synthetic\"}"}}]}}"""
        }
        val account = client.account()
        assertTrue(account.vip)
        assertEquals(6, account.level)
        val notice = client.notifications("session").items.single()
        assertEquals(42, notice.talkerId)
        assertEquals(1, notice.sessionType)
    }

    @Test
    fun sessionIdentityPrefersTalkerInfoAndBatchResolvesMissingCards() = runBlocking {
        val paths = mutableListOf<String>()
        val client =
            BilibiliApi(
                client =
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val request = chain.request()
                            paths += request.url.encodedPath
                            val body =
                                when {
                                    request.url.encodedPath.endsWith("/get_sessions") ->
                                        """{"code":0,"data":{"session_list":[""" +
                                            """{"talker_id":42,"session_type":1,"account_info":{"face":"https://i0.hdslb.com/bfs/face/account42.jpg"},"talker_info":{"name":"合成昵称","face":"https://i0.hdslb.com/bfs/face/face42.jpg"},"last_msg":{"content":"你好"}},""" +
                                            """{"talker_id":7,"session_type":1,"account_info":{},"talker_info":{},"last_msg":{"content":"在吗"}}]}}"""
                                    request.url.encodedPath.contains("user/cards") ->
                                        """{"code":0,"data":{"7":{"mid":7,"name":"卡片昵称","face":"https://i0.hdslb.com/bfs/face/face7.jpg"},"8":{"mid":8,"name":"未请求","face":"https://i0.hdslb.com/bfs/face/face8.jpg"}}}"""
                                    else -> """{"code":0,"data":{}}"""
                                }
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("Synthetic")
                                .body(body.toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                        .build()
            )
        val items = client.notifications("session").items
        assertEquals(2, items.size)
        val named = items.first { it.talkerId == 42L }
        assertEquals("合成昵称", named.title)
        assertTrue(named.avatar.endsWith("face42.jpg"))
        val resolved = items.first { it.talkerId == 7L }
        assertEquals("卡片昵称", resolved.title)
        assertTrue(resolved.avatar.endsWith("face7.jpg"))
        assertTrue(paths.any { it.contains("user/cards") })
    }
}
