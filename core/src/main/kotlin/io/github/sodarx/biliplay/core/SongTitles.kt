package io.github.sodarx.biliplay.core

import java.text.Normalizer
import java.util.Locale

/** Display-only, balanced extraction. Source titles and video identities remain intact. */
object SongTitles {
    private enum class Kind {
        BOOK,
        QUOTE,
        BRACKET,
    }

    private data class Node(
        val text: String,
        val kind: Kind,
        val children: List<Node>,
        val start: Int,
        val end: Int,
    )

    private data class Frame(
        val start: Int,
        val open: Char,
        val children: MutableList<Node> = mutableListOf(),
    )

    private data class Name(val text: String, val kind: Kind, val bracketAllowed: Boolean)

    /**
     * Balanced wrappers accepted while scanning a title. Book-title marks and quotes are explicit
     * names; the remaining bracket pairs are weak candidates used only for a leading wrapper.
     */
    private val pairs =
        mapOf(
            '《' to '》',
            '〈' to '〉',
            '「' to '」',
            '『' to '』',
            '“' to '”',
            '‘' to '’',
            '"' to '"',
            '\'' to '\'',
            '〖' to '〗',
            '〔' to '〕',
            '【' to '】',
            '（' to '）',
            '(' to ')',
            '［' to '］',
            '[' to ']',
            '｛' to '｝',
            '{' to '}',
        )

    private val spaces = Regex("""[\s\p{Z}]+""")
    private val keyNoise = Regex("""[\s\p{Z}\p{P}\p{S}]+""")
    private val cjkChars = Regex("[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af\uf900-\ufaff]")
    private val clockDuration = Regex("""\d{1,3}:\d{1,2}(?::\d{1,2})?""")
    private val spokenDuration = Regex("""\d+\s*分\s*\d+\s*秒""")
    private val numericEntity = Regex("""&#(x[0-9a-fA-F]+|[0-9]+);""")
    private val creditWord = Regex("""(?i)\b(prod|feat|ft)\b\.?""")
    private val edgeSeparators = " -–—|｜/／·・"
    private val creditPrefixes =
        listOf("作词", "作曲", "词曲", "编曲", "混音", "母带", "原曲", "原唱", "翻自", "制作", "监制", "出品", "策划", "演唱")

    private val metadataLabels =
        listOf(
                "高音质",
                "音质",
                "无损音质",
                "无损音乐",
                "无损",
                "4k",
                "8k",
                "1080p",
                "720p",
                "2160p",
                "60帧",
                "120帧",
                "60fps",
                "hi-res",
                "hires",
                "flac",
                "wav",
                "ape",
                "dsd",
                "hq",
                "hd",
                "instrumental",
                "acoustic",
                "piano",
                "guitar",
                "karaoke",
                "remix",
                "version",
                "demo",
                "nightcore",
                "slowed",
                "reverb",
                "8d",
                "official",
                "男声",
                "女声",
                "人声",
                "和声",
                "纯人声",
                "男声版",
                "女声版",
                "完整版",
                "完整",
                "纯享版",
                "纯享",
                "官方mv",
                "official mv",
                "official video",
                "official audio",
                "music video",
                "mv",
                "cover",
                "live",
                "现场版",
                "演唱会",
                "直拍",
                "动态歌词",
                "歌词版",
                "lyrics",
                "lyric video",
                "字幕",
                "中文字幕",
                "中英字幕",
                "翻唱",
                "翻唱版",
                "演奏",
                "演奏版",
                "伴奏",
                "伴奏版",
                "纯音乐",
                "钢琴",
                "钢琴版",
                "吉他",
                "吉他版",
                "翻弹",
                "教学",
                "附谱",
                "歌单",
                "合集",
                "搬运",
                "转载",
                "抖音",
                "bgm",
                "试听",
                "音源",
                "单曲循环",
                "耳机必备",
                "高清",
                "超清",
                "蓝光",
                "杜比",
                "全景声",
                "无损版",
                "免费",
                "无水印",
                "无广告",
                "mp3",
                "320kbps",
                "版",
            )
            .map { comparisonKey(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }

    private val promotionLabels =
        listOf(
                "越听越上头",
                "越听越好听",
                "前奏一响",
                "开口跪",
                "开口脆",
                "神级现场",
                "建议戴上耳机",
                "建议戴耳机",
                "听哭了",
                "耳朵怀孕",
                "好听到爆",
                "循环播放",
                "无限循环",
                "建议收藏",
                "一键三连",
                "宝藏歌曲",
                "高能预警",
                "听觉盛宴",
                "神仙现场",
                "封神现场",
            )
            .map { comparisonKey(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }

    /** Width/punctuation-insensitive key used only to recognise label-only candidate text. */
    private fun comparisonKey(text: String): String = text.lowercase(Locale.ROOT).replace(keyNoise, "")

    /**
     * True when [text] consists only of known [labels] (in any order and with any separators), so
     * e.g. "4K 60帧 高音质" is a tag soup rather than a name.
     */
    private fun labelsOnly(text: String, labels: List<String>): Boolean {
        var rest = comparisonKey(text)
        if (rest.isEmpty()) return true
        var changed = true
        while (changed && rest.isNotEmpty()) {
            changed = false
            for (label in labels) {
                if (rest.contains(label)) {
                    rest = rest.replace(label, "")
                    changed = true
                }
            }
        }
        return rest.isEmpty()
    }

    private fun metadataOnly(text: String) = labelsOnly(text, metadataLabels)

    private fun promotionOnly(text: String) = labelsOnly(text, promotionLabels)

    /**
     * Bracket text is a weak name, so credit lines like "(Prod. by …)" or "【作词：…】" are
     * rejected as well instead of being promoted into the song title.
     */
    private fun creditLine(text: String): Boolean {
        if (text.length > 80) return true
        if (text.length > 40 && cjkChars.containsMatchIn(text)) return true
        if (text.any { it == ':' || it == '：' || it == '=' }) return true
        if (creditWord.containsMatchIn(text)) return true
        val key = comparisonKey(text)
        return creditPrefixes.any { key.startsWith(it) }
    }

    private fun stripInvisible(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val width = Character.charCount(codePoint)
            val keep =
                when {
                    codePoint == 0x00AD || codePoint == 0x180E || codePoint == 0xFEFF -> false
                    codePoint in 0x200B..0x200F -> false
                    codePoint in 0x2060..0x2064 -> false
                    codePoint in 0xFE00..0xFE0F -> false
                    codePoint in 0xE0100..0xE01EF -> false
                    else -> true
                }
            if (keep) out.append(text.substring(index, index + width))
            index += width
        }
        return out.toString()
    }

    /** HTML entities, NFKC width forms, invisible marks and whitespace runs are normalised first. */
    private fun canonical(title: String): String {
        var text =
            clean(title)
                .replace("&nbsp;", " ")
                .replace(numericEntity) { match ->
                    val token = match.groupValues[1]
                    val code =
                        if (token.startsWith('x')) token.drop(1).toIntOrNull(16)
                        else token.toIntOrNull()
                    if (code != null &&
                        code >= 32 &&
                        Character.isValidCodePoint(code) &&
                        code !in 0xD800..0xDFFF
                    ) {
                        String(Character.toChars(code))
                    } else {
                        match.value
                    }
                }
        text = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return stripInvisible(text).replace(spaces, " ").trim()
    }

    private fun apostropheInWord(text: String, index: Int): Boolean {
        if (index <= 0 || index + 1 >= text.length) return false
        return listOf(text[index - 1], text[index + 1]).all {
            it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
        }
    }

    private fun kindOf(open: Char): Kind =
        when (open) {
            '《',
            '〈' -> Kind.BOOK
            '「',
            '『',
            '“',
            '‘',
            '"',
            '\'' -> Kind.QUOTE
            else -> Kind.BRACKET
        }

    private fun parse(text: String): List<Node> {
        val stack = ArrayDeque<Frame>()
        val roots = mutableListOf<Node>()
        for ((index, char) in text.withIndex()) {
            if (char in "'‘’" && apostropheInWord(text, index)) continue
            val parent = stack.lastOrNull()
            if (parent != null && pairs[parent.open] == char) {
                stack.removeLast()
                val node =
                    Node(
                        text = text.substring(parent.start + 1, index),
                        kind = kindOf(parent.open),
                        children = parent.children,
                        start = parent.start,
                        end = index,
                    )
                if (stack.isEmpty()) roots += node else stack.last().children += node
            } else if (pairs.containsKey(char)) {
                stack.addLast(Frame(index, char))
            }
        }
        // A typo'd bracket ("(", "【", …) must not hide an explicit book/quote name inside it.
        if (stack.isNotEmpty() && stack.all { kindOf(it.open) == Kind.BRACKET }) {
            for (frame in stack) roots += frame.children
        }
        return roots
    }

    /**
     * Start offsets of the top-level wrappers that begin the title (or directly follow earlier
     * leading wrappers). Weak bracket names are only trusted here so that prose like
     * "歌手 - 歌名 (Official Video)" never turns "(Official Video)" into a song name.
     */
    private fun leadingStarts(text: String, roots: List<Node>): Set<Int> {
        val leading = mutableSetOf<Int>()
        var cursor = 0
        for (root in roots) {
            while (cursor < text.length &&
                !text[cursor].isLetterOrDigit() &&
                !pairs.containsKey(text[cursor])
            ) {
                cursor++
            }
            if (root.start != cursor) break
            leading += root.start
            cursor = root.end + 1
        }
        return leading
    }

    private fun candidatesFor(node: Node, bracketsAllowed: Boolean): List<Name> {
        if (node.kind == Kind.BOOK) {
            return listOf(Name(normalized(node.text), Kind.BOOK, bracketAllowed = false))
        }
        val inner =
            node.children.flatMap { child ->
                if (child.kind == Kind.BRACKET) emptyList() else candidatesFor(child, bracketsAllowed)
            }
        if (inner.isNotEmpty()) return inner
        return listOf(
            Name(normalized(node.text), node.kind, node.kind == Kind.BRACKET && bracketsAllowed)
        )
    }

    private fun normalized(raw: String): String {
        var text = stripInvisible(raw).replace(spaces, " ").trim()
        var changed = true
        while (changed) {
            changed = false
            if (text.length > 1 && pairs[text.first()] == text.last()) {
                text = text.substring(1, text.length - 1).trim()
                changed = true
            }
            val trimmed = text.trim { it.isWhitespace() || it in edgeSeparators }
            if (trimmed != text) {
                text = trimmed
                changed = true
            }
        }
        return text
    }

    private fun durationLike(text: String): Boolean {
        val trimmed = text.trim()
        return clockDuration.matches(trimmed) || spokenDuration.matches(trimmed)
    }

    private fun nameLike(text: String): Boolean {
        if (text.isBlank()) return false
        if (durationLike(text)) return false
        return text.any { it.isLetterOrDigit() }
    }

    /** A >80 char CJK candidate in a batch of names is a description copy, not a song name. */
    private fun descriptionCopy(name: Name, batch: Int): Boolean =
        batch > 1 && name.text.length > 80 && cjkChars.containsMatchIn(name.text)

    private fun dedupeKey(text: String): String =
        text.lowercase(Locale.ROOT).replace(spaces, " ").trim()

    private fun acceptedWeak(name: Name): Boolean =
        !metadataOnly(name.text) && !promotionOnly(name.text) && !creditLine(name.text)

    private fun acceptedStrong(name: Name, strongCount: Int, weakUsable: Boolean): Boolean =
        when {
            // A name explicitly wrapped in 《》 is never second-guessed.
            name.kind == Kind.BOOK -> true
            // Keep a lone quote as-is (old behaviour) unless a usable bracket name competes.
            strongCount == 1 && !weakUsable -> true
            else -> !metadataOnly(name.text) && !promotionOnly(name.text)
        }

    fun quotedNames(title: String): List<String> {
        val text = canonical(title)
        if (text.isEmpty()) return emptyList()
        val roots = parse(text)
        if (roots.isEmpty()) return emptyList()
        val leading = leadingStarts(text, roots)
        val names =
            roots
                .flatMap { root -> candidatesFor(root, root.start in leading) }
                .filter { nameLike(it.text) }
        if (names.isEmpty()) return emptyList()
        val strong = names.filter { it.kind != Kind.BRACKET }
        val weak = names.filter { it.kind == Kind.BRACKET && it.bracketAllowed && acceptedWeak(it) }
        val selected =
            strong
                .filter { acceptedStrong(it, strong.size, weak.isNotEmpty()) }
                .ifEmpty { weak }
        val kept = selected.filterNot { descriptionCopy(it, selected.size) }
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (name in kept) {
            if (seen.add(dedupeKey(name.text))) result += name.text
        }
        return result
    }

    private val edgePairs =
        mapOf('【' to '】', '[' to ']', '（' to '）', '(' to ')', '{' to '}')
    private val reverseEdgePairs = edgePairs.entries.associate { (open, close) -> close to open }

    private fun removableEdgeContent(text: String): Boolean =
        metadataOnly(text) ||
            promotionOnly(text) ||
            creditLine(text) ||
            durationLike(text)

    /** Scan (never regex) for a removable wrapper at one edge, returning its content and range. */
    private fun edgeWrapper(text: String, leading: Boolean): Pair<String, IntRange>? {
        if (text.isEmpty()) return null
        if (leading) {
            val close = edgePairs[text.first()] ?: return null
            val end = text.indexOf(close, 1)
            if (end <= 1) return null
            val content = text.substring(1, end).trim()
            if (!removableEdgeContent(content)) return null
            return content to 0..end
        }
        val open = reverseEdgePairs[text.last()] ?: return null
        val start = text.lastIndexOf(open, text.length - 2)
        if (start < 0 || start >= text.length - 2) return null
        val content = text.substring(start + 1, text.length - 1).trim()
        if (!removableEdgeContent(content)) return null
        return content to start..text.lastIndex
    }

    /**
     * No wrapped name exists: peel metadata/promotion wrappers from either edge of the title so
     * common "歌名【高音质】" / "【翻唱】歌名 (Live)" layouts still show a usable song name. Returns
     * the original string when nothing was removable, keeping unmatched/rough titles untouched.
     */
    private fun fallbackTitle(title: String): String {
        val text = canonical(title)
        if (text.isEmpty()) return title
        var current = text
        var changed = false
        while (true) {
            val match = edgeWrapper(current, leading = true) ?: edgeWrapper(current, leading = false)
                ?: break
            current = current.removeRange(match.second).trim()
            changed = true
        }
        if (!changed) return title
        val trimmed = current.trim { it in edgeSeparators || it.isWhitespace() }
        if (trimmed.isBlank()) return title
        return trimmed
    }

    fun display(title: String, enabled: Boolean = true): String =
        if (!enabled) title
        else
            quotedNames(title).takeIf { it.isNotEmpty() }?.joinToString(" / ")
                ?: fallbackTitle(title)
}

object LyricText {
    fun clean(value: String) =
        io.github.sodarx.biliplay.core
            .clean(value)
            .replace(Regex("[♪♫♬♩🎵🎶🎼]"), "")
            .replace("\uFE0F", "")
            .trim()
}
