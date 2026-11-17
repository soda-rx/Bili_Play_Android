package io.github.sodarx.biliplay.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationSendTest {
    private val picture =
        CommentPicture("https://i0.hdslb.com/bfs/new_dyn/synthetic.jpg", 640, 480, 55.4)

    @Test
    fun textMessageCarriesAJsonContentObject() {
        val text = ConversationSend.textContent("你好 \"world\"")
        assertEquals("你好 \"world\"", JSONObject(text).getString("content"))
    }

    @Test
    fun pictureMessageUsesTheBfsUrlAndDimensions() {
        val content = JSONObject(ConversationSend.pictureContent(picture))
        assertEquals(picture.url, content.getString("url"))
        assertEquals(640, content.getInt("width"))
        assertEquals(480, content.getInt("height"))
        assertEquals("jpeg", content.getString("imageType"))
        assertEquals("jpeg", content.getString("type"))
        assertEquals(1, content.getInt("original"))
        assertEquals(56.0, content.getDouble("size"), 0.001)
    }

    @Test
    fun pngAndGifImageTypesAreDerivedFromTheBfsPath() {
        assertEquals("png", ConversationSend.imageType(picture.copy(url = "https://i0.hdslb.com/bfs/a/synthetic.png")))
        assertEquals("gif", ConversationSend.imageType(picture.copy(url = "https://i0.hdslb.com/bfs/a/synthetic.gif")))
        assertEquals("jpeg", ConversationSend.imageType(picture.copy(url = "https://i0.hdslb.com/bfs/a/synthetic")))
        assertEquals("jpeg", ConversationSend.imageType(picture.copy(url = "https://i0.hdslb.com/bfs/a/synthetic.webp")))
    }

    @Test
    fun textFormMatchesTheVerifiedWebMessageFields() {
        val form =
            ConversationSend.form(
                123L,
                456L,
                "hello",
                null,
                "372778FD-E359-461D-86A3-EA2BCC6FF52A",
                "csrf-token",
                1_700_000_000,
            )
        assertEquals("123", form["msg[sender_uid]"])
        assertEquals("456", form["msg[receiver_id]"])
        assertEquals("1", form["msg[receiver_type]"])
        assertEquals("1", form["msg[msg_type]"])
        assertEquals("0", form["msg[msg_status]"])
        assertEquals("hello", JSONObject(form.getValue("msg[content]")).getString("content"))
        assertEquals("372778FD-E359-461D-86A3-EA2BCC6FF52A", form["msg[dev_id]"])
        assertEquals("1700000000", form["msg[timestamp]"])
        assertEquals("0", form["msg[new_face_version]"])
        assertEquals("web", form["mobi_app"])
        assertEquals("csrf-token", form["csrf"])
        assertEquals("csrf-token", form["csrf_token"])
    }

    @Test
    fun pictureFormUsesMessageTypeTwo() {
        val form =
            ConversationSend.form(123L, 456L, "", picture, "DEV", "csrf", 1_700_000_000)
        assertEquals("2", form["msg[msg_type]"])
        val content = JSONObject(form.getValue("msg[content]"))
        assertEquals(picture.url, content.getString("url"))
    }

    @Test
    fun invalidConversationsAndMessagesAreRejectedBeforeSending() {
        val device = "DEV"
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSend.form(0L, 456L, "hello", null, device, "csrf", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSend.form(123L, 123L, "hello", null, device, "csrf", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSend.form(123L, 456L, "   ", null, device, "csrf", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSend.form(
                123L,
                456L,
                "x".repeat(ConversationSend.MaxTextLength + 1),
                null,
                device,
                "csrf",
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSend.form(123L, 456L, "hello", null, "", "csrf", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSend.form(123L, 456L, "hello", null, device, "", 1)
        }
    }

    @Test
    fun blankTextIsAllowedWhenAPictureIsAttached() {
        val form = ConversationSend.form(123L, 456L, "", picture, "DEV", "csrf", 1_700_000_000)
        assertEquals("2", form["msg[msg_type]"])
    }

    @Test
    fun maximumTextLengthIsAccepted() {
        val text = "字".repeat(ConversationSend.MaxTextLength)
        val form = ConversationSend.form(1L, 2L, text, null, "DEV", "csrf", 1)
        assertEquals(text, JSONObject(form.getValue("msg[content]")).getString("content"))
    }
}
