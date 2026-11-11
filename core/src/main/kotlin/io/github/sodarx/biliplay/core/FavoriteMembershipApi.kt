package io.github.sodarx.biliplay.core

suspend fun BilibiliApi.favoriteMembership(track: Track, uid: Long): Map<Long, Boolean> {
    require(track.aid > 0 && uid > 0)
    val data =
        get(
            "x/v3/fav/folder/created/list-all",
            mapOf("up_mid" to "$uid", "type" to "2", "rid" to "${track.aid}"),
        )
    return items(data.optJSONArray("list"))
        .filter { it.optLong("id") > 0 }
        .associate { it.optLong("id") to it.flag("fav_state") }
}
