package io.github.sodarx.biliplay.core

import org.json.JSONObject

suspend fun BilibiliApi.account(): Account {
    val d = get("x/web-interface/nav")
    if (!d.optBoolean("isLogin")) throw ApiFailure(-101, "请先登录")
    return Account(
        d.optLong("mid"),
        d.optString("uname"),
        image(d.optString("face")),
        d.optJSONObject("vip")?.optInt("status") == 1 || d.optInt("vipStatus") == 1,
        (d.optJSONObject("level_info")?.optInt("current_level") ?: 0).coerceIn(0, 6),
    )
}

private fun folder(o: JSONObject, owned: Boolean) =
    Folder(
        o.optLong("id"),
        clean(o.optString("title")),
        o.optInt("media_count"),
        owned,
        o.optLong("attr") and 1L != 0L,
        image(o.optString("cover")),
        o.optString("intro"),
    )

suspend fun BilibiliApi.folders(uid: Long): List<Folder> {
    val own =
        items(
                get("x/v3/fav/folder/created/list-all", mapOf("up_mid" to "$uid", "type" to "2"))
                    .optJSONArray("list")
            )
            .map { folder(it, true) }
    val collected = mutableListOf<Folder>()
    var page = 1
    do {
        val d =
            get(
                "x/v3/fav/folder/collected/list",
                mapOf("up_mid" to "$uid", "pn" to "${page++}", "ps" to "20", "platform" to "web"),
            )
        val rows = items(d.optJSONArray("list"))
        collected += rows.map { folder(it, false) }
        if (rows.isEmpty() || !d.flag("has_more")) break
    } while (page <= 500)
    return (own + collected).distinctBy { it.id }
}

suspend fun BilibiliApi.folderTracks(id: Long, page: Int = 1): Page<Track> {
    val d =
        get(
            "x/v3/fav/resource/list",
            mapOf(
                "media_id" to "$id",
                "pn" to "$page",
                "ps" to "20",
                "order" to "mtime",
                "type" to "0",
                "platform" to "web",
            ),
        )
    val rows =
        items(d.optJSONArray("medias"))
            .filter { it.optInt("type") == 2 }
            .mapNotNull { r ->
                track(r)
                    ?.copy(
                        uploader = r.optJSONObject("upper")?.optString("name").orEmpty(),
                        uploaderId = r.optJSONObject("upper")?.optLong("mid") ?: 0,
                    )
            }
    return Page(rows, d.flag("has_more"), page)
}

suspend fun BilibiliApi.createFolder(title: String, isPrivate: Boolean) {
    require(title.trim().length in 1..80)
    post(
        "x/v3/fav/folder/add",
        mapOf("title" to title.trim(), "privacy" to if (isPrivate) "1" else "0"),
    )
}

suspend fun BilibiliApi.editFolder(f: Folder, title: String, isPrivate: Boolean) {
    require(f.owned) { "订阅收藏夹只读" }
    require(title.trim().length in 1..80)
    val info = get("x/v3/fav/folder/info", mapOf("media_id" to "${f.id}"))
    post(
        "x/v3/fav/folder/edit",
        mapOf(
            "media_id" to "${f.id}",
            "title" to title.trim(),
            "privacy" to if (isPrivate) "1" else "0",
            "intro" to info.optString("intro"),
            "cover" to info.optString("cover"),
        ),
    )
}

suspend fun BilibiliApi.deleteFolder(f: Folder) {
    require(f.owned) { "订阅收藏夹只读" }
    post("x/v3/fav/folder/del", mapOf("media_ids" to "${f.id}"))
}

suspend fun BilibiliApi.favorite(track: Track, f: Folder, add: Boolean) {
    require(f.owned) { "订阅收藏夹只读" }
    post(
        "x/v3/fav/resource/deal",
        mapOf(
            "rid" to "${track.aid}",
            "type" to "2",
            (if (add) "add_media_ids" else "del_media_ids") to "${f.id}",
        ),
    )
}

suspend fun BilibiliApi.favorited(track: Track) =
    get("x/web-interface/archive/relation", mapOf("aid" to "${track.aid}")).optBoolean("favorite")

suspend fun BilibiliApi.searchUploaders(query: String, page: Int = 1): Page<Uploader> {
    val d =
        get(
            "x/web-interface/wbi/search/type",
            mapOf(
                "search_type" to "bili_user",
                "user_type" to "1",
                "keyword" to query,
                "page" to "$page",
                "page_size" to "20",
            ),
            true,
        )
    return Page(
        items(d.optJSONArray("result")).map {
            Uploader(
                it.optLong("mid"),
                clean(it.optString("uname")),
                image(it.optString("upic")),
                clean(it.optString("usign")),
                it.optLong("fans"),
                it.optLong("videos"),
            )
        },
        page < d.optInt("numPages"),
        page,
    )
}

suspend fun BilibiliApi.uploaderVideos(
    uid: Long,
    page: Int = 1,
    keyword: String = "",
    musicOnly: Boolean = true,
): Page<Track> {
    val d =
        get(
            "x/space/wbi/arc/search",
            mapOf(
                "mid" to "$uid",
                "pn" to "$page",
                "ps" to "30",
                "tid" to if (musicOnly) "3" else "0",
                "keyword" to keyword,
                "order" to "pubdate",
                "order_avoided" to "true",
                "platform" to "web",
            ),
            true,
        )
    val rows =
        items(d.optJSONObject("list")?.optJSONArray("vlist"))
            .filter { it.optLong("mid", uid) == uid }
            .mapNotNull { r ->
                track(r)
                    ?.copy(
                        uploaderId = uid,
                        duration =
                            r.optString("length").split(':').fold(0L) { a, v ->
                                a * 60 + (v.toLongOrNull() ?: 0)
                            },
                    )
            }
            .filter { keyword.isEmpty() || PreferredMusic.matches(it, keyword) }
    return Page(rows, page * 30L < (d.optJSONObject("page")?.optLong("count") ?: 0), page)
}

suspend fun BilibiliApi.socialCounts(uid: Long): Pair<Long, Long> {
    val d = get("x/relation/stat", mapOf("vmid" to "$uid"))
    return d.optLong("following") to d.optLong("follower")
}

suspend fun BilibiliApi.socialUsers(uid: Long, followers: Boolean, page: Int = 1): Page<Uploader> {
    val d =
        get(
            if (followers) "x/relation/followers" else "x/relation/followings",
            mapOf("vmid" to "$uid", "pn" to "$page", "ps" to "50", "order" to "desc"),
        )
    val rows =
        items(d.optJSONArray("list")).map {
            Uploader(
                it.optLong("mid"),
                clean(it.optString("uname")),
                image(it.optString("face")),
                clean(it.optString("sign")),
            )
        }
    return Page(
        rows,
        rows.isNotEmpty() &&
            if (d.optLong("total") > 0) page * 50L < d.optLong("total") else rows.size >= 50,
        page,
    )
}

suspend fun BilibiliApi.following(uid: Long) =
    get("x/relation", mapOf("fid" to "$uid")).optInt("attribute") in listOf(2, 6)

suspend fun BilibiliApi.follow(uid: Long, enabled: Boolean) {
    require(uid > 0)
    post(
        "x/relation/modify",
        mapOf("fid" to "$uid", "act" to if (enabled) "1" else "2", "re_src" to "11"),
    )
}

suspend fun BilibiliApi.suggestions(query: String): List<String> {
    if (query.isBlank()) return emptyList()
    return items(
            get(
                    "https://s.search.bilibili.com/main/suggest",
                    mapOf("main_ver" to "v1", "term" to query),
                )
                .optJSONArray("tag")
        )
        .map { clean(it.optString("value")) }
        .filter { it.isNotBlank() }
        .distinct()
}

suspend fun BilibiliApi.searchPage(keyword: String, page: Int = 1): Page<Track> {
    val d =
        get(
            "x/web-interface/wbi/search/type",
            mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to "$page",
                "page_size" to "20",
            ),
            true,
        )
    return Page(
        PreferredMusic.rank(items(d.optJSONArray("result")).mapNotNull(::track), keyword),
        page < d.optInt("numPages"),
        page,
    )
}

suspend fun BilibiliApi.uploaderProfile(uid: Long): Uploader {
    val d = get("x/space/wbi/acc/info", mapOf("mid" to "$uid"), true)
    return Uploader(uid, d.optString("name"), d.optString("face"), d.optString("sign"))
}
