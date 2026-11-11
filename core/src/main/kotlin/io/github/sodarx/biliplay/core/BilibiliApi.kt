package io.github.sodarx.biliplay.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class BilibiliApi(
    client: OkHttpClient? = null,
    private val apiBase: HttpUrl = "https://api.bilibili.com/".toHttpUrl(),
    val sessions: SessionCookies = SessionCookies(),
) {
    private val client =
        client
            ?: OkHttpClient.Builder()
                .cookieJar(sessions)
                .followRedirects(false)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    internal val desktopGate = kotlinx.coroutines.sync.Semaphore(2)
    internal val desktopArchives =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Page<Track>>>()
    @Volatile internal var desktopRetryAfter = 0L
    internal var desktopRotation = 0
    internal var desktopLikedRotation = 0
    private val keysLock = Mutex()
    private var keys: Triple<String, String, Long>? = null

    internal suspend fun exchange(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(IOException("网络连接失败，请重试", e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!continuation.isActive) return
                            try {
                                if (!it.isSuccessful)
                                    throw ApiFailure(
                                        it.code,
                                        if (it.code in listOf(412, 429)) "请求暂时受限，请稍后重试"
                                        else "服务器暂时不可用",
                                    )
                                val body = it.body ?: throw IOException("响应为空")
                                if (body.contentLength() > 16 * 1024 * 1024)
                                    throw IOException("响应过大")
                                val source = body.source()
                                source.request(16L * 1024 * 1024 + 1)
                                if (source.buffer.size > 16L * 1024 * 1024)
                                    throw IOException("响应过大")
                                val bytes = source.readByteArray()
                                if (bytes.size > 16 * 1024 * 1024) throw IOException("响应过大")
                                continuation.resume(bytes.toString(Charsets.UTF_8))
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            )
        }

    internal fun envelope(text: String, anonymousNav: Boolean = false): JSONObject {
        val json = JSONObject(text)
        val code = json.optInt("code", -1)
        if (
            code != 0 &&
                !(anonymousNav &&
                    code == -101 &&
                    json.optJSONObject("data")?.optJSONObject("wbi_img") != null)
        )
            throw ApiFailure(
                code,
                when (code) {
                    -101,
                    -111 -> "登录已过期，请重新登录"
                    -352,
                    -412 -> "请求暂时受限，请稍后重试"
                    12002 -> "评论区已关闭"
                    else -> "请求未完成（$code）"
                },
            )
        return json.optJSONObject("data")
            ?: json.optJSONObject("result")
            ?: JSONObject().apply { (json.opt("data") as? JSONArray)?.let { put("items", it) } }
    }

    internal fun endpoint(path: String): HttpUrl {
        val url = apiBase.resolve(path) ?: error("接口地址无效")
        val testOrigin =
            url.scheme == apiBase.scheme && url.host == apiBase.host && url.port == apiBase.port
        require(
            testOrigin ||
                (url.isHttps && (url.host == "bilibili.com" || url.host.endsWith(".bilibili.com")))
        ) {
            "接口来源无效"
        }
        return url
    }

    private suspend fun body(url: HttpUrl, allowAnonymousNav: Boolean = false): JSONObject {
        val text =
            exchange(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
            )
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            envelope(text, allowAnonymousNav)
        }
    }

    internal suspend fun post(path: String, values: Map<String, String>): JSONObject {
        val csrf = sessions.value("bili_jct") ?: throw ApiFailure(-101, "请先登录")
        val form =
            okhttp3.FormBody.Builder()
                .apply {
                    (values + mapOf("csrf" to csrf, "csrf_token" to csrf)).forEach { (k, v) ->
                        add(k, v)
                    }
                }
                .build()
        return envelope(
            exchange(
                Request.Builder()
                    .url(endpoint(path))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .post(form)
                    .build()
            )
        )
    }

    internal suspend fun rawJson(url: String): JSONObject {
        require(MediaUrls.allowed(url)) { "资源地址无效" }
        return JSONObject(
            exchange(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
            )
        )
    }

    internal suspend fun get(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        signed: Boolean = false,
    ): JSONObject {
        val base = endpoint(path)
        val url =
            if (signed) {
                val k = signingKeys()
                base
                    .newBuilder()
                    .encodedQuery(
                        WbiSigner.sign(
                            parameters,
                            k.first,
                            k.second,
                            System.currentTimeMillis() / 1000,
                        )
                    )
                    .build()
            } else
                base
                    .newBuilder()
                    .apply { parameters.forEach { (k, v) -> addQueryParameter(k, v) } }
                    .build()
        return body(url, path == "x/web-interface/nav")
    }

    private suspend fun signingKeys(): Triple<String, String, Long> = keysLock.withLock {
        keys?.takeIf { System.currentTimeMillis() - it.third < 3_600_000 }
            ?: run {
                val data = get("x/web-interface/nav")
                val images = data.optJSONObject("wbi_img") ?: throw ApiFailure(-1, "签名信息暂不可用")
                fun key(name: String) =
                    images.optString(name).substringAfterLast('/').substringBefore('.')
                Triple(key("img_url"), key("sub_url"), System.currentTimeMillis()).also {
                    keys = it
                }
            }
    }

    suspend fun search(keyword: String, page: Int = 1): List<Track> {
        require(keyword.isNotBlank())
        return PreferredMusic.rank(
            items(
                    get(
                            "x/web-interface/wbi/search/type",
                            mapOf(
                                "search_type" to "video",
                                "keyword" to keyword,
                                "page" to page.toString(),
                            ),
                            true,
                        )
                        .optJSONArray("result")
                )
                .mapNotNull(::track),
            keyword,
        )
    }

    suspend fun video(input: VideoInput): Video {
        val data =
            get(
                "x/web-interface/view",
                if (input.aid > 0) mapOf("aid" to "${input.aid}") else mapOf("bvid" to input.bvid),
            )
        val base = track(data) ?: throw ApiFailure(-1, "视频信息无效")
        if (
            (input.bvid.isNotEmpty() && base.bvid != input.bvid) ||
                (input.aid > 0 && base.aid != input.aid)
        )
            throw ApiFailure(-1, "返回的视频与请求不一致")
        val parts =
            items(data.optJSONArray("pages"))
                .map { p ->
                    base.copy(
                        cid = p.optLong("cid"),
                        page = p.optInt("page", 1),
                        part = p.optString("part"),
                        duration = p.optLong("duration"),
                    )
                }
                .filter { it.cid > 0 }
        if (parts.isEmpty()) throw ApiFailure(-1, "没有可播放的分 P")
        val season = data.optJSONObject("ugc_season")
        val collection =
            items(season?.optJSONArray("sections"))
                .flatMap { items(it.optJSONArray("episodes")) }
                .mapNotNull { episode ->
                    val arc = episode.optJSONObject("arc") ?: JSONObject()
                    val bvid = episode.optString("bvid", arc.optString("bvid"))
                    if (!Regex("BV[0-9A-Za-z]{10}").matches(bvid)) null
                    else
                        Track(
                            episode.optLong("aid", arc.optLong("aid")),
                            bvid,
                            clean(episode.optString("title", arc.optString("title"))),
                            arc.optJSONObject("author")?.optString("name") ?: base.uploader,
                            image(arc.optString("pic", episode.optString("cover"))),
                            arc.optLong("duration"),
                            episode.optLong(
                                "cid",
                                episode.optJSONObject("page")?.optLong("cid") ?: 0,
                            ),
                            uploaderId =
                                arc.optJSONObject("author")?.optLong("mid") ?: base.uploaderId,
                        )
                }
                .distinctBy { it.bvid }
        return Video(base, parts, collection, season?.optString("title").orEmpty())
    }

    suspend fun audio(track: Track, hiRes: Boolean = false): List<AudioSource> {
        require(track.cid > 0)
        val args =
            mapOf(
                "bvid" to track.bvid,
                "avid" to track.aid.toString(),
                "cid" to track.cid.toString(),
                "fnval" to if (hiRes) "4048" else "16",
                "fnver" to "0",
                "qn" to "80",
            )
        val attempts: List<suspend () -> List<AudioSource>> =
            listOf(
                { AudioParser.parse(get("x/player/wbi/playurl", args, true)) },
                { pageAudio(track) },
                { AudioParser.parse(get("x/player/playurl", args)) },
            )
        var last: Exception? = null
        for (attempt in attempts) {
            try {
                return attempt()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (
                    e is ApiFailure &&
                        e.code in listOf(-101, -111, -403, 401, 403, 412, 429, -352, -412)
                )
                    throw e
                last = e
            }
        }
        if (last is ApiFailure) throw last
        throw ApiFailure(-1, "未获得可播放音源，请重试或重新登录")
    }

    private suspend fun pageAudio(track: Track): List<AudioSource> {
        val html =
            exchange(
                Request.Builder()
                    .url(endpoint("https://www.bilibili.com/video/${track.bvid}/?p=${track.page}"))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
            )
        return AudioParser.parse(envelope(EmbeddedPlayback.read(html)))
    }

    suspend fun resolveVideoInput(value: String): VideoInput? {
        VideoLinks.parse(value)?.let {
            return it
        }
        var url = value.replaceFirst("http://b23.tv/", "https://b23.tv/")
        if (!VideoLinks.allowedShortLink(url)) return null
        repeat(5) {
            val next =
                suspendCancellableCoroutine<String> { continuation ->
                    val call =
                        client.newCall(
                            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                        )
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (continuation.isActive)
                                    continuation.resumeWithException(IOException("短链接解析失败"))
                            }

                            override fun onResponse(call: Call, response: Response) {
                                response.use {
                                    if (!continuation.isActive) return
                                    val location = response.header("Location")
                                    val target = location?.let {
                                        response.request.url.resolve(it)?.toString()
                                    }
                                    if (
                                        response.code in 300..399 &&
                                            target != null &&
                                            VideoLinks.allowedRedirect(target)
                                    )
                                        continuation.resume(target)
                                    else
                                        continuation.resumeWithException(
                                            ApiFailure(-1, "短链接没有指向有效视频")
                                        )
                                }
                            }
                        }
                    )
                }
            VideoLinks.parse(next)?.let {
                return it
            }
            require(VideoLinks.allowedShortLink(next))
            url = next
        }
        throw ApiFailure(-1, "短链接跳转次数过多")
    }

    internal fun track(data: JSONObject): Track? {
        val bvid = data.optString("bvid")
        if (!Regex("BV[0-9A-Za-z]{10}").matches(bvid)) return null
        val owner = data.optJSONObject("owner")
        val title = data.optString("title").replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
        val pic = image(data.optString("pic", data.optString("cover")))
        val rawDuration = data.opt("duration")
        val duration =
            if (rawDuration is Number) rawDuration.toLong()
            else
                data.optString("duration").split(':').fold(0L) { total, value ->
                    total * 60 + (value.toLongOrNull() ?: 0)
                }
        return Track(
            data.optLong("aid", data.optLong("id")),
            bvid,
            title,
            owner?.optString("name") ?: data.optString("author"),
            pic,
            duration,
            uploaderId = owner?.optLong("mid") ?: data.optLong("mid"),
        )
    }
}

internal fun items(array: JSONArray?): List<JSONObject> =
    if (array == null) emptyList()
    else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

object AudioParser {
    fun parse(data: JSONObject): List<AudioSource> {
        val dash = data.optJSONObject("dash") ?: throw ApiFailure(-1, "当前资源没有可用的独立音频")
        val normal =
            items(dash.optJSONArray("audio"))
                .sortedWith(
                    compareByDescending<JSONObject> { it.optString("codecs").startsWith("mp4a") }
                        .thenByDescending { it.optLong("bandwidth") }
                )
                .mapNotNull { parseOne(it, "标准") }
                .firstOrNull() ?: throw ApiFailure(-1, "当前资源没有可用的受支持音源")
        val flac =
            dash
                .optJSONObject("flac")
                ?.optJSONObject("audio")
                ?.takeIf { it.optInt("id") == 30251 }
                ?.let { parseOne(it, "Hi-Res") }
        return listOfNotNull(normal, flac)
    }

    private fun parseOne(data: JSONObject, quality: String): AudioSource? {
        val backups = data.optJSONArray("backupUrl") ?: data.optJSONArray("backup_url")
        val urls =
            (listOf(data.optString("baseUrl"), data.optString("base_url")) +
                    (0 until (backups?.length() ?: 0)).map { backups!!.optString(it) })
                .mapNotNull(MediaUrls::normalize)
                .distinct()
        return urls.firstOrNull()?.let {
            AudioSource(
                it,
                urls.drop(1),
                quality,
                data.optString("mimeType", data.optString("mime_type", "audio/mp4")),
            )
        }
    }
}
