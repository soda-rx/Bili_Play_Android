package io.github.sodarx.biliplay.core

import org.json.JSONObject

private fun text(o: JSONObject?, vararg keys: String): String {
    if (o == null) return ""
    for (k in keys) {
        val v = o.opt(k)
        if (v is String || v is Number) {
            val raw = v.toString()
            if (raw.isBlank()) continue
            if (raw.startsWith("{")) {
                val nested = runCatching { JSONObject(raw) }.getOrNull()
                if (nested != null) return text(nested, "content", "text", "title")
            }
            return clean(raw)
        }
    }
    return ""
}

suspend fun BilibiliApi.notifications(
    source: String,
    cursor: Map<String, String> = emptyMap(),
): NotificationPage {
    require(source in listOf("session", "like", "reply", "at", "system", "user-system"))
    val path =
        when (source) {
            "session" -> "https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions"
            "system" ->
                if (cursor.isEmpty()) "https://message.bilibili.com/x/sys-msg/query_unified_notify"
                else "https://message.bilibili.com/x/sys-msg/query_notify_list"
            "user-system" ->
                if (cursor.isEmpty()) "https://message.bilibili.com/x/sys-msg/query_user_notify"
                else "https://message.bilibili.com/x/sys-msg/query_notify_list"
            else -> "x/msgfeed/$source"
        }
    val args =
        mutableMapOf("build" to "0", "mobi_app" to "web", "platform" to "web").apply {
            putAll(cursor)
        }
    if (source == "session")
        args.putAll(mapOf("session_type" to "1", "sort_rule" to "2", "size" to "20"))
    if (source in listOf("system", "user-system")) args["page_size"] = "20"
    val d = get(path, args)
    val rows =
        when (source) {
            "session" -> items(d.optJSONArray("session_list"))
            "like" ->
                items(d.optJSONObject("latest")?.optJSONArray("items")) +
                    items(d.optJSONObject("total")?.optJSONArray("items"))
            "system",
            "user-system" -> items(d.optJSONArray("system_notify_list") ?: d.optJSONArray("items"))
            else -> items(d.optJSONArray("items"))
        }
    val result = rows.mapNotNull { r ->
        val target = r.optJSONObject("item")
        val users =
            items(r.optJSONArray("users")).ifEmpty {
                listOfNotNull(r.optJSONObject("user"), r.optJSONObject("talker_info"))
            }
        val user = users.firstOrNull()
        val title =
            text(user, "nickname", "uname", "name").ifEmpty {
                if (source == "session") "UID ${r.optLong("talker_id")}"
                else text(r, "title", "name")
            }
        val content =
            when (source) {
                "session" -> text(r.optJSONObject("last_msg"), "content")
                "system",
                "user-system" -> text(r, "content", "content_text", "text")
                else -> text(target, "source_content")
            }
        val rawId = if (source == "session") r.optString("talker_id") else r.optString("id")
        if (rawId.isEmpty() && content.isEmpty()) null
        else {
            val rawTime =
                text(r, source + "_time", "session_ts", "time_at", "notify_time", "ctime", "time")
            var stamp = rawTime.toLongOrNull() ?: 0
            while (stamp > 100_000_000_000L) stamp /= 1000
            Notification(
                "$source:${rawId.ifEmpty{title+rawTime}}",
                when (source) {
                    "session" -> "私信"
                    "like" -> "点赞"
                    "system",
                    "user-system" -> "系统通知"
                    else -> "回复与 @"
                },
                title,
                content,
                stamp,
                image(text(user, "avatar", "face", "pic_url")),
                text(target, "title", "target_reply_content", "target_content"),
                text(target, "uri", "url", "jump_url").ifBlank {
                    text(r, "uri", "url", "jump_url")
                },
                if (source == "session") r.optLong("talker_id") else 0,
                r.optInt("session_type", 1),
            )
        }
    }
    val c =
        if (source == "like") d.optJSONObject("total")?.optJSONObject("cursor")
        else d.optJSONObject("cursor")
    val next =
        when (source) {
            "session" ->
                if (d.flag("has_more"))
                    mapOf("end_ts" to rows.lastOrNull()?.optString("session_ts").orEmpty())
                else emptyMap()
            "system",
            "user-system" ->
                rows
                    .lastOrNull()
                    ?.optString("cursor")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { mapOf("cursor" to it, "data_type" to "1") } ?: emptyMap()
            else ->
                if (c != null && !c.flag("is_end") && c.optString("id").isNotBlank())
                    mapOf("id" to c.optString("id"), "${source}_time" to c.optString("time"))
                else emptyMap()
        }
    return NotificationPage(
        result.distinctBy { it.id }.sortedByDescending { it.timestamp },
        next,
        next.isNotEmpty() && next != cursor && rows.isNotEmpty(),
    )
}
