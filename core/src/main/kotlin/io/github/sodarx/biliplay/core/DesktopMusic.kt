package io.github.sodarx.biliplay.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONObject

/** Port of Windows MusicPage: rendered channel cards only, never scripts or generic feed. */
object MusicChannelPage {
    private fun first(text: String, pattern: String) =
        Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.getOrNull(1).orEmpty()

    private fun clean(text: String): String =
        text
            .replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()

    private fun attribute(tag: String, name: String) =
        clean(first(tag, "\\s$name\\s*=\\s*[\"']([^\"']*)[\"']"))

    private fun imageIn(markup: String): String =
        Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
            .findAll(markup)
            .mapNotNull { tag ->
                sequenceOf("data-src", "src")
                    .map { attribute(tag.value, it).substringBefore('@') }
                    .map {
                        if (it.startsWith("//")) "https:$it"
                        else it.replaceFirst("http://", "https://")
                    }
                    .firstOrNull {
                        MediaUrls.allowed(it) &&
                            !it.contains("/bfs/face/") &&
                            !it.contains("/bfs/garb/")
                    }
            }
            .firstOrNull()
            .orEmpty()

    fun parse(html: String): List<Track> =
        Regex("<div class=\"feed-card[^\"]*\"")
            .split(html.take(4 * 1024 * 1024))
            .drop(1)
            .mapNotNull { raw ->
                val card = raw.take(40000)
                val bvid = first(card, "bilibili\\.com/video/(BV[0-9A-Za-z]{10})")
                val title =
                    clean(first(card, "class=\"bili-video-card__title\"[^>]*title=\"([^\"]*)\""))
                val coverMarkup =
                    Regex("<picture\\b[^>]*>.*?</picture>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(card)
                        .firstOrNull {
                            attribute(it.value.substringBefore('>') + ">", "class")
                                .split(' ')
                                .contains("bili-video-card__cover")
                        }
                        ?.value
                        ?: Regex("<img\\b[^>]*>")
                            .findAll(card)
                            .firstOrNull {
                                attribute(it.value, "class")
                                    .split(' ')
                                    .contains("bili-video-card__cover")
                            }
                            ?.value
                val cover =
                    if (coverMarkup != null) imageIn(coverMarkup)
                    else {
                        val candidates =
                            Regex("<a\\b[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)
                                .findAll(card)
                                .filter { it.value.substringBefore('>').contains("/video/$bvid") }
                                .map { imageIn(it.value) }
                                .filter { it.isNotBlank() }
                                .toList()
                        candidates.firstOrNull()
                            ?: Regex("<img\\b[^>]*>")
                                .findAll(card)
                                .map { imageIn(it.value) }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .toList()
                                .singleOrNull()
                                .orEmpty()
                    }
                val author =
                    Regex(
                            "<a(?=[^>]*class=\"[^\"]*bili-video-card__author)(?=[^>]*href=\"(?:https:)?//space.bilibili.com/(\\d+)[^\"]*\")[^>]*>(.*?)</a>",
                            RegexOption.DOT_MATCHES_ALL,
                        )
                        .find(card)
                val authorContent = author?.groupValues?.get(2).orEmpty()
                val name =
                    clean(
                            first(authorContent, "<span[^>]*title=\"([^\"]*)\"").ifBlank {
                                authorContent
                            }
                        )
                        .substringBeforeLast(" · ")
                val time =
                    first(card, "<span[^>]*>(\\d{1,3}:\\d{2}(?::\\d{2})?)</span>").split(':').fold(
                        0L
                    ) { a, v ->
                        a * 60 + (v.toLongOrNull() ?: 0)
                    }
                if (bvid.isBlank() || title.isBlank() || cover.isBlank()) null
                else
                    Track(
                        0,
                        bvid,
                        title,
                        name,
                        cover,
                        time,
                        uploaderId = author?.groupValues?.get(1)?.toLongOrNull() ?: 0,
                    )
            }
            .distinctBy { it.bvid }
}

private suspend fun BilibiliApi.musicChannel(page: Int): Page<Track> {
    val referer = "https://www.bilibili.com/c/music/"
    if (page == 1) {
        try {
            val html =
                withTimeoutOrNull(12000) {
                    exchange(
                        Request.Builder()
                            .url(referer)
                            .header("User-Agent", BilibiliApi.USER_AGENT)
                            .header("Referer", referer)
                            .build()
                    )
                }
            val parsed = html?.let(MusicChannelPage::parse).orEmpty()
            if (parsed.isNotEmpty()) return Page(parsed, true, page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }
    val params =
        mapOf(
            "display_id" to "$page",
            "request_cnt" to "15",
            "from_region" to "1003",
            "device" to "web",
            "plat" to "30",
            "web_location" to "333.40138",
        )
    val url =
        endpoint("x/web-interface/region/feed/rcmd")
            .newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    val data =
        try {
            envelope(
                exchange(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", BilibiliApi.USER_AGENT)
                        .header("Referer", referer)
                        .build()
                )
            )
        } catch (e: ApiFailure) {
            if (e.code == 62013) return Page(emptyList(), false, page) else throw e
        }
    val tracks =
        items(data.optJSONArray("archives"))
            .filter { !it.has("tid") || it.optInt("tid") == 0 || MusicRecommendations.isMusic(it) }
            .mapNotNull { row ->
                val copy = JSONObject(row.toString())
                row.optJSONObject("author")?.let { copy.put("owner", it) }
                track(copy)
            }
    return Page(tracks, tracks.isNotEmpty(), page)
}

private suspend fun BilibiliApi.preferredArchive(uid: Long, page: Int): Page<Track> {
    val key = "$uid:$page"
    val now = System.currentTimeMillis()
    desktopArchives[key]
        ?.takeIf { now - it.first < 900000 }
        ?.let {
            return it.second
        }
    return desktopGate.withPermit {
        desktopArchives[key]
            ?.takeIf { System.currentTimeMillis() - it.first < 900000 }
            ?.let {
                return@withPermit it.second
            }
        if (System.currentTimeMillis() < desktopRetryAfter)
            return@withPermit Page(emptyList(), false, page)
        try {
            val result = uploaderVideos(uid, page, musicOnly = true)
            if (desktopArchives.size > 128) desktopArchives.clear()
            desktopArchives[key] = System.currentTimeMillis() to result
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            if (e.requiresLogin) throw e
            if (e.code in listOf(-352, -403, -799))
                desktopRetryAfter = System.currentTimeMillis() + 120000
            Page(emptyList(), false, page)
        } catch (_: Exception) {
            Page(emptyList(), false, page)
        }
    }
}

suspend fun BilibiliApi.desktopMusic(page: Int = 1, independent: Boolean = false): Page<Track> =
    coroutineScope {
        require(page >= 1)
        if (independent) desktopLikedRotation++
        else if (page == 1) {
            desktopRotation = kotlin.random.Random.nextInt(15)
            desktopLikedRotation = desktopRotation
        }
        val rotation = if (independent) desktopLikedRotation else desktopRotation
        val group = DesktopMusicRotation.group(page, rotation)
        val archives =
            PreferredMusic.uploaders.drop(group * 3).take(3).map { uid ->
                async {
                    withTimeoutOrNull(6000) {
                        preferredArchive(uid, DesktopMusicRotation.archivePage(page, rotation))
                    } ?: Page(emptyList(), false, page)
                }
            }
        val channel = async {
            try {
                musicChannel(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiFailure) {
                if (e.requiresLogin) throw e else Page(emptyList(), false, page)
            } catch (_: Exception) {
                Page(emptyList(), false, page)
            }
        }
        val batches = archives.awaitAll()
        val general = channel.await()
        val offset = DesktopMusicRotation.offset(page, rotation)
        val preferred = DesktopMusicRotation.interleave(batches.map { it.items }, offset)
        Page(
            PreferredMusic.blend(preferred, general.items),
            general.hasMore || batches.any { it.hasMore || it.items.size > offset + 6 },
            page,
        )
    }
