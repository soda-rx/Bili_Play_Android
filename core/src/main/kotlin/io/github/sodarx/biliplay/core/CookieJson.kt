package io.github.sodarx.biliplay.core

import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted by the Android vault; this codec preserves host-only scope exactly. */
object CookieJson {
    fun encode(cookies: List<Cookie>): String =
        JSONArray()
            .apply {
                cookies.forEach { c ->
                    put(
                        JSONObject()
                            .put("name", c.name)
                            .put("value", c.value)
                            .put("domain", c.domain)
                            .put("path", c.path)
                            .put("hostOnly", c.hostOnly)
                            .put("secure", c.secure)
                            .put("httpOnly", c.httpOnly)
                            .put("persistent", c.persistent)
                            .put("expiresAt", c.expiresAt)
                    )
                }
            }
            .toString()

    fun decode(json: String): List<Cookie> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val c = array.getJSONObject(i)
                Cookie.Builder()
                    .name(c.getString("name"))
                    .value(c.getString("value"))
                    .apply {
                        if (c.getBoolean("hostOnly")) hostOnlyDomain(c.getString("domain"))
                        else domain(c.getString("domain"))
                        path(c.getString("path"))
                        if (c.getBoolean("secure")) secure()
                        if (c.getBoolean("httpOnly")) httpOnly()
                        if (c.getBoolean("persistent")) expiresAt(c.getLong("expiresAt"))
                    }
                    .build()
            }
                .getOrNull()
        }
    }
}
