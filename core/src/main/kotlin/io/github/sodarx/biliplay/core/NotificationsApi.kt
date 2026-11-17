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

private fun sessionHasName(o: JSONObject?): Boolean =
    o != null && text(o, "nickname", "uname", "name").isNotEmpty()

/**
 * get_sessions carries a stranger's identity in account_info, while talker_info is the legacy
 * shape. An account_info object can exist yet be nameless for deactivated accounts, so identity
 * sources are probed by usable name first and only then fall back to their raw shapes.
 */
private fun sessionUsers(row: JSONObject): List<JSONObject> {
    val listed = items(row.optJSONArray("users"))
    if (listed.isNotEmpty()) return listed
    row.optJSONObject("user")?.let {
        return listOf(it)
    }
    val live = row.optJSONObject("account_info")
    val legacy = row.optJSONObject("talker_info")
    if (sessionHasName(live)) return listOfNotNull(live)
    if (sessionHasName(legacy)) return listOfNotNull(legacy)
    if (live != null) return listOf(live)
    if (legacy != null) return listOf(legacy)
    return emptyList()
}

private fun parseNotification(source: String, row: JSONObject): Notification? {
    val system = source == "system" || source == "user-system"
    val target = row.optJSONObject("item")
    val users = if (source == "session") sessionUsers(row) else {
        items(row.optJSONArray("users")).ifEmpty {
            listOfNotNull(row.optJSONObject("user"), row.optJSONObject("talker_info"))
        }
    }
    val user = users.firstOrNull()
    val names = users.map { text(it, "nickname", "uname", "name") }.filter { it.isNotEmpty() }.take(2)
    val title =
        names.joinToString("、").ifEmpty {
            when {
                source == "session" -> "UID ${row.optLong("talker_id")}"
                else -> text(row, "title", "name")
            }
        }
    val content =
        when (source) {
            "session" -> text(row.optJSONObject("last_msg"), "content")
            "system",
            "user-system" -> text(row, "content", "content_text", "text")
            else -> text(target, "source_content")
        }
    val rawId = if (source == "session") row.optString("talker_id") else row.optString("id")
    if (rawId.isEmpty() && content.isEmpty()) return null
    val rawTime =
        text(row, source + "_time", "session_ts", "time_at", "notify_time", "ctime", "time")
    var stamp = rawTime.toLongOrNull() ?: 0
    while (stamp > 100_000_000_000L) stamp /= 1000
    val avatar =
        users.map { image(text(it, "avatar", "face", "pic_url")) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return Notification(
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
        avatar,
        text(target, "title", "target_reply_content", "target_content"),
        text(target, "uri", "url", "jump_url").ifBlank {
            text(row, "uri", "url", "jump_url")
        },
        if (source == "session") row.optLong("talker_id") else 0,
        row.optInt("session_type", 1),
        targetImage =
            if (source == "session") ""
            else image(text(target, "image", "cover", "pic", "target_image")),
    )
}

/** Batch-resolve identities omitted from get_sessions, mirroring the official web client. */
private suspend fun BilibiliApi.enrichSessionIdentities(
    items: List<Notification>
): List<Notification> {
    val missing =
        items
            .filter {
                it.talkerId > 0 && (it.title == "UID ${it.talkerId}" || it.avatar.isBlank())
            }
            .map { it.talkerId }
            .distinct()
    if (missing.isEmpty()) return items
    val cards =
        try {
            userCards(missing)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return items
        }
    if (cards.isEmpty()) return items
    return items.map { item ->
        val card = cards[item.talkerId] ?: return@map item
        item.copy(
            title =
                if (item.title == "UID ${item.talkerId}" && card.first.isNotBlank()) card.first
                else item.title,
            avatar = item.avatar.ifBlank { card.second },
        )
    }
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
    val parsed = rows.mapNotNull { row -> parseNotification(source, row) }
    val enriched = if (source == "session") enrichSessionIdentities(parsed) else parsed
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
        enriched.distinctBy { it.id }.sortedByDescending { it.timestamp },
        next,
        next.isNotEmpty() && next != cursor && rows.isNotEmpty(),
    )
}

/**
 * Verified deletion paths: remove a private session, or delete a like/reply/@ feed entry. Drafts and
 * system notices have no verified cloud endpoint and are hidden locally by the caller instead.
 */
suspend fun BilibiliApi.deleteNotification(notification: Notification) {
    if (!NotificationDeletion.serverSide(notification)) throw ApiFailure(-1, "这类通知暂不支持云端删除")
    if (notification.category == "私信") {
        post(
            "https://api.vc.bilibili.com/session_svr/v1/session_svr/remove_session",
            mapOf(
                "talker_id" to "${notification.talkerId}",
                "session_type" to "${notification.sessionType}",
                "build" to "0",
                "mobi_app" to "web",
            ),
        )
    } else {
        val id = NotificationDeletion.feedId(notification) ?: throw ApiFailure(-1, "通知标识无效")
        val type = NotificationDeletion.feedType(notification) ?: throw ApiFailure(-1, "通知类型无效")
        post(
            "x/msgfeed/del",
            mapOf("id" to "$id", "tp" to type, "build" to "0", "mobi_app" to "web"),
        )
    }
}
