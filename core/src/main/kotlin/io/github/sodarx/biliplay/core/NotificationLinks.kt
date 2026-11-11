package io.github.sodarx.biliplay.core

import java.net.URI

object NotificationLinks {
    fun resolve(raw: String): String? {
        val value = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        val host = uri.host.orEmpty().lowercase()
        val scheme = uri.scheme.orEmpty().lowercase()
        val tail = (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        if (scheme == "bilibili") {
            val path = uri.path.orEmpty().trim('/')
            val base =
                when {
                    host == "video" &&
                        path.matches(Regex("(?:[0-9]+|av[0-9]+|BV[0-9A-Za-z]{10})")) ->
                        "https://www.bilibili.com/video/" +
                            if (path.all { it.isDigit() }) "av$path" else path
                    host == "space" && path.matches(Regex("[0-9]+")) ->
                        "https://space.bilibili.com/$path"
                    host == "following" && path.matches(Regex("detail/[0-9]+")) ->
                        "https://t.bilibili.com/${path.substringAfterLast('/')}"
                    else -> return null
                }
            return base + tail
        }
        if (
            scheme !in listOf("https", "http") ||
                !(host == "bilibili.com" ||
                    host.endsWith(".bilibili.com") ||
                    host == "b23.tv" ||
                    host == "www.b23.tv")
        )
            return null
        return runCatching {
                URI("https://$host${uri.rawPath.ifBlank { "/" }}$tail").toASCIIString()
            }
            .getOrNull()
    }
}
