package io.github.sodarx.biliplay.core

import java.util.Locale

/** Display-only, balanced extraction. Source titles and video identities remain intact. */
object SongTitles {
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
        )

    private data class Node(val text: String, val book: Boolean, val children: List<Node>)

    private data class Frame(
        val start: Int,
        val open: Char,
        val children: MutableList<Node> = mutableListOf(),
    )

    private data class Name(val text: String, val book: Boolean)

    private val metadata =
        setOf(
            "高音质",
            "无损",
            "无损音质",
            "4k",
            "8k",
            "1080p",
            "hi-res",
            "flac",
            "完整版",
            "纯享版",
            "官方mv",
            "official mv",
            "单曲循环",
            "耳机必备",
        )
    private val promotion = setOf("越听越上头", "越听越好听", "前奏一响", "开口跪", "神级现场", "建议戴上耳机", "听哭了")

    private fun normalized(raw: String): String {
        var text =
            raw.replace(Regex("[\\u200B\\uFEFF]"), "").replace(Regex("[\\p{Z}\\s]+"), " ").trim()
        while (text.length > 1 && pairs[text.first()] == text.last()) text =
            text.substring(1, text.length - 1).trim()
        return text
    }

    private fun apostropheInWord(text: String, index: Int): Boolean {
        if (index <= 0 || index + 1 >= text.length) return false
        return listOf(text[index - 1], text[index + 1]).all {
            it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
        }
    }

    private fun books(node: Node): List<Name> =
        if (node.book) listOf(Name(normalized(node.text), true)) else node.children.flatMap(::books)

    fun quotedNames(title: String): List<String> {
        val text =
            clean(title).replace("&nbsp;", " ").replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { match
                ->
                val token = match.groupValues[1]
                val code =
                    if (token.startsWith('x')) token.drop(1).toIntOrNull(16)
                    else token.toIntOrNull()
                if (
                    code != null &&
                        Character.isValidCodePoint(code) &&
                        code !in 0xD800..0xDFFF &&
                        code >= 32
                )
                    String(Character.toChars(code))
                else match.value
            }
        val stack = ArrayDeque<Frame>()
        val roots = mutableListOf<Node>()
        for ((index, char) in text.withIndex()) {
            if (char in "'‘’" && apostropheInWord(text, index)) continue
            val parent = stack.lastOrNull()
            if (parent != null && pairs[parent.open] == char) {
                stack.removeLast()
                val node =
                    Node(
                        text.substring(parent.start + 1, index),
                        parent.open in "《〈",
                        parent.children,
                    )
                if (stack.isEmpty()) roots += node else stack.last().children += node
            } else if (char in pairs) stack.addLast(Frame(index, char))
        }
        val candidates =
            roots
                .flatMap { node ->
                    books(node).ifEmpty { listOf(Name(normalized(node.text), false)) }
                }
                .filter { it.text.isNotBlank() }
        val hasBook = candidates.any { it.book }
        return candidates
            .filter { name ->
                if (name.book || candidates.size == 1) true
                else {
                    val comparison =
                        name.text.trim('!', '！', '~', '～', '。', '…').lowercase(Locale.ROOT)
                    comparison !in metadata && !(hasBook && comparison in promotion)
                }
            }
            .map { it.text }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun display(title: String, enabled: Boolean = true): String =
        if (!enabled) title
        else quotedNames(title).takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: title
}

object LyricText {
    fun clean(value: String) =
        io.github.sodarx.biliplay.core
            .clean(value)
            .replace(Regex("[♪♫♬♩🎵🎶🎼]"), "")
            .replace("\uFE0F", "")
            .trim()
}
