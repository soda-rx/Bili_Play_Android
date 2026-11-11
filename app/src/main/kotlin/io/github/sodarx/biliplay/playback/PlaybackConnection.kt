package io.github.sodarx.biliplay.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class PlaybackConnection(private val context: Context) {
    private val executor = ContextCompat.getMainExecutor(context)
    private var future: ListenableFuture<MediaController>? = null

    suspend fun ready(): MediaController {
        val old = future
        if (
            old != null &&
                (old.isCancelled ||
                    (old.isDone && !runCatching { old.get().isConnected }.getOrDefault(false)))
        ) {
            MediaController.releaseFuture(old)
            future = null
        }
        val pending =
            future
                ?: MediaController.Builder(
                        context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                    )
                    .buildAsync()
                    .also { future = it }
        return suspendCancellableCoroutine { c ->
            pending.addListener(
                {
                    if (c.isActive)
                        runCatching { pending.get() }
                            .onSuccess { c.resume(it) }
                            .onFailure { c.resumeWithException(it) }
                },
                executor,
            )
        }
    }

    fun close() {
        future?.let(MediaController::releaseFuture)
        future = null
    }
}
