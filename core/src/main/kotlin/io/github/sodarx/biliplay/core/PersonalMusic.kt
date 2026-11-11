package io.github.sodarx.biliplay.core

import kotlinx.coroutines.*
import org.json.JSONObject

data class MusicCandidate(val track: Track, val plays: Long, val relevance: Int = 0)

data class PersonalMusicSections(
    val likes: List<Track>,
    val daily: List<Track>,
    val more: List<Track>,
)

object MusicRecommendations {
    // Legacy music descendants, plus the current AI-music category. Never accept arbitrary
    // neighboring IDs, and never let a category label override an explicit non-music ID.
    private val musicTids = setOf(3, 28, 29, 30, 31, 54, 59, 130, 193, 194, 243, 244, 265)
    private val musicNames =
        setOf(
            "音乐",
            "原创音乐",
            "翻唱",
            "演奏",
            "音乐现场",
            "三次元音乐",
            "音乐综合",
            "音乐选集",
            "音乐教学",
            "音乐盘点",
            "乐评盘点",
            "VOCALOID·UTAU",
            "MV",
            "AI音乐",
            "电音",
            "OP/ED/OST",
        )

    fun isMusic(row: JSONObject): Boolean {
        val tid = row.optInt("tid")
        if (tid != 0) return tid in musicTids
        return row.optInt("parent_tid") == 3 ||
            row.optInt("tid_v2") == 1003 ||
            row.optInt("parent_tid_v2") == 1003 ||
            row.optString("tname") in musicNames ||
            row.optString("tname_v2") in musicNames ||
            row.optString("typename") in musicNames
    }

    fun sections(
        related: List<MusicCandidate>,
        feed: List<MusicCandidate>,
        previous: List<Track> = emptyList(),
    ): PersonalMusicSections {
        val relevant = (related + feed).distinctBy { it.track.key }
        val likes =
            LikedSelection.select(
                related.map { it.track }.ifEmpty { feed.map { it.track } },
                previous,
            )
        val liked = likes.map { it.key }.toSet()
        // Small genuine pools may repeat between sections; they must not leave Today empty.
        val available = relevant.filterNot { it.track.key in liked }.ifEmpty { relevant }
        val daily =
            available
                .sortedWith(
                    compareByDescending<MusicCandidate> { it.relevance }
                        .thenByDescending { it.plays }
                )
                .take(minOf(8, (available.size + 1) / 2))
                .map { it.track }
        val dailyKeys = daily.map { it.key }.toSet()
        val more =
            (feed + related)
                .distinctBy { it.track.key }
                .filterNot { it.track.key in dailyKeys || it.track.key in liked }
                .map { it.track }
        return PersonalMusicSections(likes, daily, more)
    }
}

private fun BilibiliApi.verifiedMusic(rows: List<JSONObject>): List<MusicCandidate> =
    rows
        .take(60)
        .filter { it.optString("goto", "av") == "av" && it.optString("bvid").isNotBlank() }
        .mapNotNull { row ->
            // Every displayed candidate carries current platform music-category evidence.
            if (!MusicRecommendations.isMusic(row)) return@mapNotNull null
            track(row)?.let {
                MusicCandidate(
                    it,
                    row.optJSONObject("stat")?.optLong("view") ?: row.optLong("play"),
                )
            }
        }

/** Same sources, rotation and 2:1 blending as the Windows client. */
suspend fun BilibiliApi.personalMusic(page: Int = 1): List<MusicCandidate> =
    desktopMusic(page).items.map {
        MusicCandidate(it, 0, if (it.uploaderId in PreferredMusic.uploaders) 200 else 100)
    }

/** Independent three-day music popularity, never a personalized/general-video feed. */
suspend fun BilibiliApi.recentMusicLeaders(): List<Track> {
    fun candidates(d: JSONObject): List<Track> =
        verifiedMusic(items(d.optJSONArray("list") ?: d.optJSONArray("items")))
            .distinctBy { it.track.key }
            .sortedByDescending { it.plays }
            .map { it.track }
            .take(10)
    val regional =
        try {
            candidates(
                get(
                    "x/web-interface/ranking/region",
                    mapOf("rid" to "3", "day" to "3", "original" to "0"),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            if (e.requiresLogin) throw e else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    if (regional.isNotEmpty()) return regional
    return candidates(get("x/web-interface/ranking/v2", mapOf("rid" to "3", "type" to "all")))
        .ifEmpty { throw ApiFailure(-1, "音乐热榜暂不可用，请稍后重试") }
}
