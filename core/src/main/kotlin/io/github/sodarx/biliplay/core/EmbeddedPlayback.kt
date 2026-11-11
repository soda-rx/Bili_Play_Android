package io.github.sodarx.biliplay.core

/** Reads JSON as data only; it never evaluates code supplied by a web page. */
object EmbeddedPlayback {
    fun read(html: String): String {
        val marker =
            Regex("(?:window[.])?__playinfo__[ ]*=[ ]*").find(html)
                ?: throw ApiFailure(-1, "视频网页暂未提供音源")
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) throw ApiFailure(-1, "网页音源格式无效")
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == 92.toChar()) escaped = true else if (ch == '"') quoted = false
            } else
                when (ch) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
        }
        throw ApiFailure(-1, "网页音源数据不完整")
    }
}
