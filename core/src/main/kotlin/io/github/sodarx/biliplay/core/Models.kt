package io.github.sodarx.biliplay.core

import java.net.URI

data class Track(
    val aid: Long,
    val bvid: String,
    val title: String,
    val uploader: String,
    val cover: String,
    val duration: Long,
    val cid: Long = 0,
    val page: Int = 1,
    val part: String = "",
    val uploaderId: Long = 0,
    val localUri: String = "",
) {
    val key
        get() = "$bvid:$page"
}

data class Video(
    val track: Track,
    val parts: List<Track>,
    val collection: List<Track> = emptyList(),
    val collectionTitle: String = "",
)

data class AudioSource(
    val url: String,
    val backups: List<String>,
    val quality: String,
    val mime: String,
)

data class VideoInput(val bvid: String, val page: Int = 1, val aid: Long = 0)

data class Account(
    val id: Long,
    val name: String,
    val avatar: String,
    val vip: Boolean,
    val level: Int = 0,
)

class ApiFailure(val code: Int, message: String) : Exception(message) {
    val requiresLogin: Boolean
        get() = code == -101 || code == -111
}

object VideoLinks {
    private val bvid = Regex("BV[0-9A-Za-z]{10}")
    private val aid = Regex("av([0-9]+)", RegexOption.IGNORE_CASE)

    fun parse(input: String): VideoInput? {
        val text = input.trim()
        if (bvid.matches(text)) return VideoInput(text)
        aid.matchEntire(text)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let {
                return VideoInput("", aid = it)
            }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (
            uri.scheme !in setOf("https", "http") ||
                uri.host !in setOf("bilibili.com", "www.bilibili.com", "m.bilibili.com") ||
                uri.userInfo != null ||
                uri.port !in setOf(-1, 443)
        )
            return null
        val part =
            Regex("^/video/(BV[0-9A-Za-z]{10}|av[0-9]+)/?$", RegexOption.IGNORE_CASE)
                .matchEntire(uri.path.orEmpty())
                ?.groupValues
                ?.get(1) ?: return null
        val page =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .firstOrNull { it.startsWith("p=") }
                ?.substringAfter('=')
                ?.toIntOrNull() ?: 1
        if (page < 1) return null
        return if (bvid.matches(part)) VideoInput(part, page)
        else
            aid.matchEntire(part)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { VideoInput("", page, it) }
    }

    fun allowedShortLink(value: String): Boolean {
        val u = runCatching { URI(value) }.getOrNull() ?: return false
        return u.scheme == "https" &&
            u.userInfo == null &&
            u.port in setOf(-1, 443) &&
            u.host in setOf("b23.tv", "www.b23.tv")
    }

    fun allowedRedirect(value: String) = parse(value) != null || allowedShortLink(value)
}

object MediaUrls {
    private val domains =
        listOf("bilivideo.com", "bilivideo.cn", "akamaized.net", "bilibili.com", "hdslb.com")

    fun normalize(value: String): String? {
        val text = value.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (uri.host == null || uri.userInfo != null || uri.scheme !in setOf("http", "https"))
            return null
        if (uri.scheme == "http" && uri.port !in setOf(-1, 80)) return null
        val upgraded =
            if (uri.scheme == "http")
                "https://" +
                    uri.rawAuthority.removeSuffix(":80") +
                    uri.rawPath.orEmpty() +
                    (uri.rawQuery?.let { "?$it" } ?: "")
            else text
        return upgraded.takeIf(::allowed)
    }

    fun allowed(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            domains.any { host == it || host.endsWith(".$it") }
    }
}
