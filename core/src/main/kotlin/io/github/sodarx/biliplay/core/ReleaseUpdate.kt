package io.github.sodarx.biliplay.core

import java.net.URI
import org.json.JSONObject

data class StableVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        fun parse(value: String): StableVersion? {
            if (!Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)").matches(value)) return null
            val numbers = value.split('.').map { it.toLongOrNull() ?: return null }
            return StableVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

data class ReleaseUpdate(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
) {
    companion object {
        const val LatestUrl = "https://api.github.com/repos/soda-rx/Bili_Play_Android/releases/latest"
        const val MaxBytes = 300L * 1024 * 1024

        fun parse(json: JSONObject, installed: String): ReleaseUpdate? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            val version = StableVersion.parse(tag) ?: return null
            val current = StableVersion.parse(installed.substringBefore('-')) ?: return null
            if (version < current || (version == current && '-' !in installed)) return null
            val assets = json.optJSONArray("assets") ?: return null
            val candidates = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name").equals("Bili_Play_Android_$tag.apk", ignoreCase = true) }
            val asset = candidates.singleOrNull() ?: return null
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
                uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null ||
                uri.rawPath != "/soda-rx/Bili_Play_Android/releases/download/$tag/$name") return null
            val size = asset.optLong("size")
            if (size !in 1..MaxBytes) return null
            val digest = asset.optString("digest").takeIf { it.isNotBlank() && it != "null" }
            if (digest != null && !Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) return null
            return ReleaseUpdate(tag, json.optString("body").take(6000), url, size,
                digest?.substringAfter(':')?.lowercase())
        }

        fun trustedDownloadUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
                uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        }
    }
}
