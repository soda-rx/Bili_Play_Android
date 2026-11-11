package io.github.sodarx.biliplay.core

suspend fun BilibiliApi.coinCount(track: Track): Int =
    get("x/web-interface/archive/coins", mapOf("aid" to "${track.aid}"))
        .optInt("multiply")
        .coerceIn(0, 2)

suspend fun BilibiliApi.giveCoins(track: Track, count: Int) {
    require(track.aid > 0 && count in 1..2)
    val existing = coinCount(track)
    require(existing + count <= 2) { "该视频已达到投币上限" }
    post(
        "x/web-interface/coin/add",
        mapOf(
            "aid" to "${track.aid}",
            "multiply" to "$count",
            "select_like" to "0",
            "cross_domain" to "true",
        ),
    )
}
