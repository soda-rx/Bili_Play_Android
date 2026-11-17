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
        signals: RecommendationSignals = RecommendationSignals(),
        seed: Int = 0,
    ): PersonalMusicSections {
        val relevant = (related + feed).distinctBy { it.track.key }
        if (relevant.isEmpty()) return PersonalMusicSections(emptyList(), emptyList(), emptyList())
        val previousKeys = previous.map { it.key }.toSet()
        val base =
            signals.copy(
                recentBatches =
                    (listOf(previousKeys) + signals.recentBatches)
                        .filter { it.isNotEmpty() }
                        .take(RecommendationPolicy.RecentBatches)
            )
        // 猜你喜欢：个性化 + 换组优先换创作者，不重复上一组；保留候选来源顺序并轮换起点。
        val likedCandidates =
            related.map { it.track }.ifEmpty { feed.map { it.track } }.distinctBy { it.key }
        val shift =
            if (likedCandidates.isEmpty()) 0
            else
                ((seed % likedCandidates.size) + likedCandidates.size) % likedCandidates.size
        val rotated = likedCandidates.drop(shift) + likedCandidates.take(shift)
        val likes = RecommendationPolicy.ranked(rotated, 3, base, seed)
        val likedKeys = likes.map { it.key }.toSet()
        val available = relevant.filterNot { it.track.key in likedKeys }
        // A tiny genuine pool may repeat between sections; it must not leave Today empty.
        val dailyPool = available.ifEmpty { relevant }
        val dailySignals =
            base.copy(
                recentBatches =
                    (listOf(likedKeys) + base.recentBatches)
                        .filter { it.isNotEmpty() }
                        .take(RecommendationPolicy.RecentBatches)
            )
        // 今日推荐：保留平台相关度/播放量排序，但排除最近几批并保持创作者多元。
        val daily =
            RecommendationPolicy.ranked(
                dailyPool
                    .sortedWith(
                        compareByDescending<MusicCandidate> { it.relevance }
                            .thenByDescending { it.plays }
                    )
                    .map { it.track },
                minOf(8, (dailyPool.size + 1) / 2),
                dailySignals,
                seed + 1,
            )
        val dailyKeys = daily.map { it.key }.toSet()
        // 更多推荐：按平台 feed 顺序，硬排除本批已展示的歌，同一批内不重复。
        val more =
            RecommendationPolicy.ranked(
                (feed + related).map { it.track },
                relevant.size,
                base,
                seed + 2,
                exclude = likedKeys + dailyKeys,
            )
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
