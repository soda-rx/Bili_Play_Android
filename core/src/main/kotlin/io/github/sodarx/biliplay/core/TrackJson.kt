package io.github.sodarx.biliplay.core

import org.json.JSONArray
import org.json.JSONObject

object TrackJson {
    fun encode(t: Track) =
        JSONObject()
            .put("aid", t.aid)
            .put("bvid", t.bvid)
            .put("title", t.title)
            .put("uploader", t.uploader)
            .put("cover", t.cover)
            .put("duration", t.duration)
            .put("cid", t.cid)
            .put("page", t.page)
            .put("part", t.part)
            .put("uploaderId", t.uploaderId)
            .put("localUri", t.localUri)

    fun decode(o: JSONObject) =
        Track(
            o.optLong("aid"),
            o.optString("bvid"),
            o.optString("title"),
            o.optString("uploader"),
            o.optString("cover"),
            o.optLong("duration"),
            o.optLong("cid"),
            o.optInt("page", 1),
            o.optString("part"),
            o.optLong("uploaderId"),
            o.optString("localUri"),
        )

    fun encodeAll(tracks: List<Track>) =
        JSONArray().apply { tracks.forEach { put(encode(it)) } }.toString()

    fun decodeAll(text: String): List<Track> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).map { decode(a.getJSONObject(it)) }
    }
        .getOrDefault(emptyList())
}

internal fun clean(value: String) =
    value
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

internal fun image(value: String): String {
    val v =
        if (value.startsWith("//")) "https:$value" else value.replaceFirst("http://", "https://")
    return v.takeIf(MediaUrls::allowed).orEmpty()
}

internal fun JSONObject.flag(name: String) = optBoolean(name) || optInt(name, 0) == 1
