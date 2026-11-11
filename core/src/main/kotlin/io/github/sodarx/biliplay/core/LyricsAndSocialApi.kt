package io.github.sodarx.biliplay.core

import org.json.JSONObject

suspend fun BilibiliApi.subtitleTracks(track: Track): List<SubtitleTrack> {
    val d = get("x/player/wbi/v2", mapOf("aid" to "${track.aid}", "cid" to "${track.cid}"), true)
    if (d.optBoolean("need_login_subtitle")) throw ApiFailure(-101, "字幕需要登录")
    return parseSubtitleTracks(d)
}

internal fun parseSubtitleTracks(d: JSONObject): List<SubtitleTrack> {
    val subtitle = d.optJSONObject("subtitle") ?: return emptyList()
    val source = subtitle.optString("original_language").ifBlank { d.optString("audio_language") }
    return items(subtitle.optJSONArray("subtitles")).mapNotNull { row ->
        val url = image(row.optString("subtitle_url"))
        if (url.isBlank()) null
        else
            SubtitleTrack(
                row.optString("lan"),
                row.optString("lan_doc"),
                url,
                row.optInt("type") == 1 ||
                    row.optString("lan").startsWith("ai-", ignoreCase = true),
                source,
                row.optBoolean("is_translate", false),
            )
    }
}

suspend fun BilibiliApi.subtitle(track: SubtitleTrack): List<SubtitleLine> =
    parseSubtitle(rawJson(track.url))

fun parseSubtitle(json: JSONObject): List<SubtitleLine> =
    items(json.optJSONArray("body"))
        .mapNotNull {
            val a = it.optDouble("from", Double.NaN)
            val b = it.optDouble("to", Double.NaN)
            val text = LyricText.clean(it.optString("content"))
            if (!a.isFinite() || !b.isFinite() || a < 0 || b <= a || text.isEmpty()) null
            else SubtitleLine(a, b, text)
        }
        .sortedWith(compareBy<SubtitleLine> { it.from }.thenBy { it.to })

private fun comment(o: JSONObject, pinned: Boolean = false): Comment? {
    if (o.optLong("rpid") <= 0) return null
    val m = o.optJSONObject("member") ?: JSONObject()
    return Comment(
        o.optLong("rpid"),
        m.optLong("mid"),
        clean(m.optString("uname")),
        image(m.optString("avatar")),
        clean(o.optJSONObject("content")?.optString("message").orEmpty()),
        o.optLong("like"),
        o.optLong("ctime"),
        o.optLong("rcount", o.optLong("count")),
        o.optInt("action"),
        pinned,
    )
}

suspend fun BilibiliApi.comments(aid: Long, latest: Boolean, page: Int = 1): Page<Comment> {
    val d =
        get(
            "x/v2/reply",
            mapOf(
                "type" to "1",
                "oid" to "$aid",
                "pn" to "$page",
                "ps" to "20",
                "sort" to if (latest) "0" else "2",
            ),
        )
    val pinned =
        if (page == 1)
            items(d.optJSONArray("top_replies")).mapNotNull { comment(it, true) } +
                listOf("upper", "admin", "vote").mapNotNull {
                    d.optJSONObject("top")?.optJSONObject(it)?.let { c -> comment(c, true) }
                }
        else emptyList()
    val rows = items(d.optJSONArray("replies")).mapNotNull { comment(it) }
    val count = d.optJSONObject("page")?.optLong("count") ?: 0
    return Page(
        (pinned + rows).distinctBy { it.id },
        rows.isNotEmpty() && if (count > 0) page * 20L < count else rows.size >= 20,
        page,
    )
}

suspend fun BilibiliApi.replies(aid: Long, root: Long, page: Int = 1): Page<Comment> {
    val d =
        get(
            "x/v2/reply/reply",
            mapOf("type" to "1", "oid" to "$aid", "root" to "$root", "pn" to "$page", "ps" to "20"),
        )
    val rows = items(d.optJSONArray("replies")).mapNotNull { comment(it) }
    return Page(
        rows,
        rows.isNotEmpty() && page * 20L < (d.optJSONObject("page")?.optLong("count") ?: 0),
        page,
    )
}

suspend fun BilibiliApi.sendComment(
    aid: Long,
    message: String,
    root: Long = 0,
    parent: Long = 0,
): Comment? {
    require(message.trim().isNotEmpty() && message.length <= 1000)
    require(root >= 0 && parent >= 0 && (root == 0L) == (parent == 0L))
    val args =
        mutableMapOf("type" to "1", "oid" to "$aid", "message" to message.trim(), "plat" to "1")
    if (root > 0) {
        args["root"] = "$root"
        args["parent"] = "$parent"
    }
    return post("x/v2/reply/add", args).optJSONObject("reply")?.let { comment(it) }
}

suspend fun BilibiliApi.react(aid: Long, id: Long, dislike: Boolean, enabled: Boolean) {
    post(
        if (dislike) "x/v2/reply/hate" else "x/v2/reply/action",
        mapOf(
            "type" to "1",
            "oid" to "$aid",
            "rpid" to "$id",
            "action" to if (enabled) "1" else "0",
        ),
    )
}

suspend fun BilibiliApi.emotes(): List<EmotePackage> {
    var d = get("x/emote/user/panel/web", mapOf("business" to "reply"))
    if (items(d.optJSONArray("packages")).isEmpty())
        d = get("x/emote/package", mapOf("ids" to "1", "business" to "reply"))
    return items(d.optJSONArray("packages")).map { p ->
        EmotePackage(
            clean(p.optString("text")),
            items(p.optJSONArray("emote"))
                .filter { it.optJSONObject("flags")?.optInt("no_access") != 1 }
                .map { Emote(it.optString("text"), image(it.optString("url"))) },
        )
    }
}
