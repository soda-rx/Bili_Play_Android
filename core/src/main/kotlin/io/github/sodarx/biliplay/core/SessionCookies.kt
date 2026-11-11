package io.github.sodarx.biliplay.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class SessionCookies(private val persist: (List<Cookie>) -> Unit = {}) : CookieJar {
    private val lock = Any()
    @Volatile private var enabled = true
    private val storedCookies = mutableListOf<Cookie>()

    private fun domain(host: String) = host == "bilibili.com" || host.endsWith(".bilibili.com")

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!enabled || !url.isHttps || !domain(url.host)) return
        synchronized(lock) {
            if (!enabled) return
            cookies
                .filter { domain(it.domain) && it.matches(url) }
                .forEach { c ->
                    storedCookies.removeAll {
                        it.name == c.name && it.domain == c.domain && it.path == c.path
                    }
                    if (c.expiresAt > System.currentTimeMillis()) storedCookies.add(c)
                }
            persist(storedCookies.toList())
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(lock) {
            if (!url.isHttps || !domain(url.host)) emptyList()
            else
                storedCookies.filter {
                    it.expiresAt > System.currentTimeMillis() && it.matches(url)
                }
        }

    fun value(name: String): String? =
        synchronized(lock) {
            storedCookies
                .lastOrNull { it.name == name && it.expiresAt > System.currentTimeMillis() }
                ?.value
        }

    fun snapshot(): List<Cookie> =
        synchronized(lock) {
            storedCookies.filter { it.expiresAt > System.currentTimeMillis() }.toList()
        }

    fun restore(values: List<Cookie>) {
        synchronized(lock) {
            if (!enabled) return
            storedCookies.clear()
            storedCookies.addAll(
                values.filter { domain(it.domain) && it.expiresAt > System.currentTimeMillis() }
            )
        }
    }

    fun invalidate() {
        synchronized(lock) {
            enabled = false
            storedCookies.clear()
        }
    }

    fun clear() {
        synchronized(lock) {
            enabled = false
            storedCookies.clear()
            persist(emptyList())
        }
    }

    fun importHeader(header: String) {
        if (!enabled) return
        val allowed =
            setOf(
                "SESSDATA",
                "bili_jct",
                "DedeUserID",
                "DedeUserID__ckMd5",
                "sid",
                "buvid3",
                "buvid4",
            )
        val values =
            header.split(';').mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name !in allowed || value.isBlank() || value.any { it == '\r' || it == '\n' })
                    null
                else
                    runCatching {
                        Cookie.Builder()
                            .name(name)
                            .value(value)
                            .domain("bilibili.com")
                            .path("/")
                            .secure()
                            .expiresAt(System.currentTimeMillis() + 180L * 86400000)
                            .build()
                    }
                        .getOrNull()
            }
        synchronized(lock) {
            if (!enabled) return
            values.forEach { c ->
                storedCookies.removeAll { it.name == c.name }
                storedCookies.add(c)
            }
            persist(storedCookies.toList())
        }
    }

    override fun toString() = "SessionCookies(redacted)"
}
