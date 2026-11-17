package io.github.sodarx.biliplay.core

import java.net.URI
import kotlin.math.ceil
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

data class DirectMessage(
    val id: String,
    val sender: Long,
    val text: String,
    val image: String,
    val sequence: Long,
    val timestamp: Long,
    val own: Boolean,
)

data class ConversationPage(val items: List<DirectMessage>, val hasMore: Boolean, val before: Long)

/** Read-only conversation, matching the Windows participant and cursor checks. */
suspend fun BilibiliApi.conversation(self: Long, talker: Long, before: Long = 0): ConversationPage {
    require(self > 0 && talker > 0 && self != talker && before >= 0)
    val args =
        mutableMapOf(
            "talker_id" to "$talker",
            "session_type" to "1",
            "size" to "20",
            "build" to "0",
            "mobi_app" to "web",
        )
    if (before > 0) args["begin_seqno"] = "$before"
    val d = get("https://api.vc.bilibili.com/svr_sync/v1/svr_sync/fetch_session_msgs", args)
    val messages =
        items(d.optJSONArray("messages"))
            .mapNotNull { row ->
                val sender = row.optLong("sender_uid")
                val receiver = row.optLong("receiver_id")
                val seq = row.optLong("msg_seqno")
                if (
                    !((sender == self && receiver == talker) ||
                        (sender == talker && receiver == self)) || (before > 0 && seq >= before)
                )
                    return@mapNotNull null
                val raw = row.optString("content")
                val content = runCatching { JSONObject(raw) }.getOrNull()
                val type = row.optInt("msg_type")
                var text =
                    content?.optString("content")?.ifBlank {
                        content.optString("title").ifBlank { content.optString("text") }
                    } ?: if (type == 1) raw else ""
                val picture =
                    if (type == 2) conversationImage(content?.optString("url").orEmpty()) else ""
                if (type == 5) text = "[消息已撤回]"
                if (text.isBlank() && picture.isBlank()) text = "[暂不支持的消息类型 $type]"
                var stamp = row.optLong("timestamp")
                while (stamp > 100_000_000_000L) stamp /= 1000
                DirectMessage(
                    row.optString("msg_key").ifBlank { "$sender:$seq" },
                    sender,
                    text,
                    picture,
                    seq,
                    stamp,
                    sender == self,
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.sequence }
    val cursor =
        d.optLong("min_seqno").takeIf { it > 0 }
            ?: messages.map { it.sequence }.filter { it > 0 }.minOrNull()
            ?: 0
    return ConversationPage(
        messages,
        d.flag("has_more") &&
            cursor > 0 &&
            (before == 0L || cursor < before) &&
            messages.isNotEmpty(),
        cursor,
    )
}

private fun conversationImage(raw: String): String {
    val url = image(raw)
    val uri = runCatching { URI(url) }.getOrNull() ?: return ""
    val host = uri.host.orEmpty().lowercase()
    return url.takeIf {
            uri.scheme == "https" &&
                uri.port in listOf(-1, 443) &&
                uri.userInfo == null &&
                (host == "hdslb.com" ||
                    host.endsWith(".hdslb.com") ||
                    host == "bilibili.com" ||
                    host.endsWith(".bilibili.com"))
        }
        .orEmpty()
}

/** Payload rules for the verified private-message write endpoint. Pure so they stay testable. */
object ConversationSend {
    const val MaxTextLength = 1000

    fun imageType(picture: CommentPicture): String =
        when (picture.url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "png" -> "png"
            "gif" -> "gif"
            else -> "jpeg"
        }

    fun textContent(text: String): String = JSONObject().put("content", text).toString()

    fun pictureContent(picture: CommentPicture): String =
        JSONObject()
            .put("url", picture.url)
            .put("width", picture.width)
            .put("height", picture.height)
            .put("imageType", imageType(picture))
            // Older clients read "type"; newer ones read "imageType". Send both, same value.
            .put("type", imageType(picture))
            .put("original", 1)
            .put("size", ceil(picture.sizeKb.coerceAtLeast(1.0)))
            .toString()

    fun form(
        self: Long,
        talker: Long,
        text: String,
        picture: CommentPicture?,
        deviceId: String,
        csrf: String,
        timestamp: Long,
    ): Map<String, String> {
        require(self > 0 && talker > 0 && self != talker) { "会话对象无效" }
        require(deviceId.isNotBlank() && csrf.isNotBlank() && timestamp > 0) { "发送参数无效" }
        if (picture == null) {
            require(text.isNotBlank() && text.length <= MaxTextLength) { "消息不能为空或超过 1000 字" }
        }
        return buildMap {
            put("msg[sender_uid]", "$self")
            put("msg[receiver_id]", "$talker")
            put("msg[receiver_type]", "1")
            put("msg[msg_type]", if (picture == null) "1" else "2")
            put("msg[msg_status]", "0")
            put(
                "msg[content]",
                if (picture == null) textContent(text) else pictureContent(picture),
            )
            put("msg[dev_id]", deviceId)
            put("msg[timestamp]", "$timestamp")
            put("msg[new_face_version]", "0")
            put("from_firework", "0")
            put("build", "0")
            put("mobi_app", "web")
            put("csrf", csrf)
            put("csrf_token", csrf)
        }
    }
}

/**
 * Sends one private message. A write may still have reached the server when the acknowledgement is
 * lost, so the caller must never retry automatically; the conversation is reloaded instead.
 */
suspend fun BilibiliApi.sendConversationMessage(
    self: Long,
    talker: Long,
    text: String = "",
    picture: CommentPicture? = null,
): Long {
    val csrf = sessions.value("bili_jct") ?: throw ApiFailure(-101, "登录凭据不完整，请重新登录")
    val device = messageDeviceId()
    val timestamp = System.currentTimeMillis() / 1000
    val url =
        signedUrl(
            "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg",
            mapOf(
                "w_sender_uid" to "$self",
                "w_receiver_id" to "$talker",
                "w_dev_id" to device,
            ),
        )
    val fields = ConversationSend.form(self, talker, text, picture, device, csrf, timestamp)
    val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    val data =
        envelope(
            exchange(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", BilibiliApi.USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .post(form)
                    .build()
            )
        )
    return data.optLong("msg_key").takeIf { it > 0 } ?: 0L
}
