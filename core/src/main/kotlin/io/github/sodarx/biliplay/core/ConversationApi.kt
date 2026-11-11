package io.github.sodarx.biliplay.core

import java.net.URI
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
