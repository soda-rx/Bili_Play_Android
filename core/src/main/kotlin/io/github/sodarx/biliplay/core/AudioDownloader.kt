package io.github.sodarx.biliplay.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*

class AudioDownloader(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build(),
    private val allowed: (String) -> Boolean = MediaUrls::allowed,
) {
    suspend fun download(urls: List<String>, target: File, progress: (Long, Long) -> Unit) {
        require(urls.isNotEmpty())
        var last: Exception? = null
        for (candidate in urls) {
            require(allowed(candidate))
            try {
                var current = candidate
                var complete = false
                repeat(6) {
                    if (!complete) {
                        val redirect = receive(current, target, progress)
                        if (redirect == null) complete = true
                        else {
                            require(allowed(redirect)) { "音源跳转来源无效" }
                            current = redirect
                        }
                    }
                }
                check(complete) { "音源跳转次数过多" }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("音源下载失败", last)
    }

    private suspend fun receive(
        url: String,
        target: File,
        progress: (Long, Long) -> Unit,
    ): String? {
        val finished = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                val call =
                    client.newCall(
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", BilibiliApi.USER_AGENT)
                            .header("Referer", "https://www.bilibili.com/")
                            .build()
                    )
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            try {
                                if (continuation.isActive)
                                    continuation.resumeWithException(IOException("音源连接失败"))
                            } finally {
                                finished.complete(Unit)
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                response.use {
                                    if (!continuation.isActive) return
                                    try {
                                        if (response.code in 300..399) {
                                            val redirect =
                                                response.header("Location")?.let {
                                                    response.request.url.resolve(it)?.toString()
                                                } ?: throw IOException("音源跳转无效")
                                            require(allowed(redirect)) { "音源跳转来源无效" }
                                            continuation.resume(redirect)
                                            return
                                        }
                                        check(response.isSuccessful) { "音源暂时不可用" }
                                        val body = response.body ?: throw IOException("音源为空")
                                        val total = body.contentLength()
                                        var count = 0L
                                        body.byteStream().use { input ->
                                            target.outputStream().use { output ->
                                                val buffer = ByteArray(65536)
                                                while (continuation.isActive) {
                                                    val read = input.read(buffer)
                                                    if (read < 0) break
                                                    output.write(buffer, 0, read)
                                                    count += read
                                                    if (continuation.isActive)
                                                        progress(count, total)
                                                }
                                            }
                                        }
                                        if (continuation.isActive) {
                                            check(count > 0) { "音源为空" }
                                            continuation.resume(null)
                                        }
                                    } catch (error: Exception) {
                                        if (continuation.isActive)
                                            continuation.resumeWithException(error)
                                    }
                                }
                            } finally {
                                finished.complete(Unit)
                            }
                        }
                    }
                )
            }
        } finally {
            // Cancellation returns only after the callback has closed its output stream.
            withContext(NonCancellable) { finished.await() }
        }
    }
}
