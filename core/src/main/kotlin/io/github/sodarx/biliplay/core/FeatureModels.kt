package io.github.sodarx.biliplay.core

data class Page<T>(val items: List<T>, val hasMore: Boolean = false, val page: Int = 1)

data class Folder(
    val id: Long,
    val title: String,
    val count: Int,
    val owned: Boolean,
    val isPrivate: Boolean = false,
    val cover: String = "",
    val intro: String = "",
)

data class Uploader(
    val id: Long,
    val name: String,
    val avatar: String = "",
    val signature: String = "",
    val followers: Long = 0,
    val videos: Long = 0,
)

data class SubtitleTrack(
    val language: String,
    val label: String,
    val url: String,
    val automatic: Boolean,
    val originalLanguage: String = "",
    val translated: Boolean = false,
)

data class SubtitleLine(val from: Double, val to: Double, val content: String)

data class Comment(
    val id: Long,
    val authorId: Long,
    val author: String,
    val avatar: String,
    val message: String,
    val likes: Long,
    val timestamp: Long,
    val replies: Long = 0,
    val reaction: Int = 0,
    val pinned: Boolean = false,
)

data class Emote(val text: String, val url: String)

data class EmotePackage(val name: String, val emotes: List<Emote>)

data class Notification(
    val id: String,
    val category: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val avatar: String = "",
    val target: String = "",
    val url: String = "",
    val talkerId: Long = 0,
    val sessionType: Int = 1,
)

data class NotificationPage(
    val items: List<Notification>,
    val cursor: Map<String, String> = emptyMap(),
    val hasMore: Boolean = false,
)

enum class QueueMode(val label: String) {
    Sequential("顺序"),
    RepeatAll("列表"),
    RepeatOne("单曲"),
    Shuffle("随机"),
}

object QueuePolicy {
    fun nextIndex(
        size: Int,
        current: Int,
        mode: QueueMode,
        ended: Boolean,
        direction: Int = 1,
        random: Int = 0,
    ): Int? {
        if (size <= 0) return null
        if (ended && mode == QueueMode.RepeatOne && current in 0 until size) return current
        if (mode == QueueMode.Shuffle && size > 1) {
            val base = current.coerceIn(0, size - 1)
            return (base + 1 + Math.floorMod(random, size - 1)) % size
        }
        val next = current + direction
        if (next in 0 until size) return next
        return if (mode == QueueMode.Sequential && ended) null else Math.floorMod(next, size)
    }
}

object SubtitlePolicy {
    private fun language(value: String) =
        value.lowercase().removePrefix("ai-").split('-', '_').first().let {
            when (it) {
                "jp" -> "ja"
                "kr" -> "ko"
                else -> it
            }
        }

    private fun lang(s: SubtitleTrack) = language(s.language)

    private fun translated(s: SubtitleTrack) =
        s.translated ||
            Regex("翻译|译文|translation|translated", RegexOption.IGNORE_CASE).containsMatchIn(s.label)

    private fun original(s: SubtitleTrack) =
        !translated(s) && Regex("原文|原声|original", RegexOption.IGNORE_CASE).containsMatchIn(s.label)

    private fun guess(title: String): String? {
        val hints =
            listOf(
                "ja" to "日语|日文|Japanese",
                "ko" to "韩语|韩文|Korean",
                "yue" to "粤语|Cantonese",
                "fr" to "法语|法文|French",
                "de" to "德语|德文|German",
                "es" to "西班牙语|Spanish",
                "ru" to "俄语|俄文|Russian",
                "en" to "英语|英文|English",
                "zh" to "国语|中文歌|华语|Mandarin",
            )
        hints
            .firstOrNull { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(title) }
            ?.let {
                return it.first
            }
        val text = SongTitles.quotedNames(title).lastOrNull() ?: title
        return when {
            text.any { it in '\u3040'..'\u30ff' } -> "ja"
            text.any { it in '\uac00'..'\ud7af' } -> "ko"
            text.any { it in '\u0400'..'\u04ff' } -> "ru"
            text.any { it in '\u0e00'..'\u0e7f' } -> "th"
            text.any { it in '\u0600'..'\u06ff' } -> "ar"
            text.count { it in 'a'..'z' || it in 'A'..'Z' } >= 2 &&
                text.count { it in 'a'..'z' || it in 'A'..'Z' } >
                    text.count { it in '\u4e00'..'\u9fff' } * 2 -> "en"
            text.any { it in '\u4e00'..'\u9fff' } -> "zh"
            else -> null
        }
    }

    fun choose(tracks: List<SubtitleTrack>, title: String): SubtitleTrack? {
        val usable = tracks.filterNot(::translated).ifEmpty { tracks }
        if (usable.isEmpty()) return null
        val explicit = usable.firstOrNull(::original)
        val metadata =
            usable
                .map { language(it.originalLanguage) }
                .filter { it.matches(Regex("[a-z]{2,3}")) && it != "und" }
                .distinct()
                .singleOrNull()
        val auto = usable.filter { it.automatic }.map(::lang).distinct().singleOrNull()
        val preferred = metadata ?: explicit?.let(::lang) ?: auto ?: guess(title)
        val matching =
            usable
                .filter { lang(it) == preferred }
                .ifEmpty {
                    if (preferred == "yue") usable.filter { lang(it) == "zh" } else emptyList()
                }
        return matching
            .sortedWith(compareByDescending<SubtitleTrack> { original(it) }.thenBy { it.automatic })
            .firstOrNull() ?: explicit ?: usable.firstOrNull { it.automatic } ?: usable.first()
    }

    fun active(lines: List<SubtitleLine>, position: Double): Int = lines.indexOfLast {
        position >= it.from && position < it.to
    }
}

object PreferredMusic {
    val uploaders =
        listOf(
            383152785L,
            1873801216L,
            88082801L,
            3546707482643138L,
            490518248L,
            9666167L,
            3493093607213343L,
            440121192L,
            3493082817366845L,
        )

    fun matches(track: Track, query: String): Boolean {
        val tokens =
            Regex("[\\p{L}\\p{N}]+")
                .findAll(
                    java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC)
                        .lowercase()
                )
                .map { it.value }
                .toList()
        val text =
            java.text.Normalizer.normalize(
                    track.title + " " + track.part + " " + track.uploader,
                    java.text.Normalizer.Form.NFKC,
                )
                .lowercase()
        return tokens.isNotEmpty() && tokens.all { text.contains(it) }
    }

    fun rank(tracks: List<Track>, query: String) =
        tracks
            .distinctBy { it.bvid to it.page }
            .sortedByDescending { it.uploaderId in uploaders && matches(it, query) }

    fun blend(preferred: List<Track>, general: List<Track>): List<Track> {
        val p = ArrayDeque(preferred.filter { it.uploaderId in uploaders })
        val g = ArrayDeque(general)
        return buildList {
            while (p.isNotEmpty() || g.isNotEmpty()) {
                repeat(2) { if (p.isNotEmpty()) add(p.removeFirst()) }
                if (g.isNotEmpty()) add(g.removeFirst())
            }
        }
            .distinctBy { it.bvid }
    }
}
