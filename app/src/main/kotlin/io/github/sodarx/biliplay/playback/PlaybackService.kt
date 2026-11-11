package io.github.sodarx.biliplay.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.sodarx.biliplay.BiliPlayApplication
import io.github.sodarx.biliplay.MainActivity
import io.github.sodarx.biliplay.core.BilibiliApi

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var engine: ExoPlayer? = null
    private val hub
        get() = (application as BiliPlayApplication).playback

    override fun onCreate() {
        super.onCreate()
        val http =
            DefaultHttpDataSource.Factory()
                .setUserAgent(BilibiliApi.USER_AGENT)
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(6000)
                .setReadTimeoutMs(15000)
        val p =
            ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        engine = p
        hub.attach(p)
        val forwarded =
            object : ForwardingPlayer(p) {
                override fun getAvailableCommands(): Player.Commands =
                    Player.Commands.Builder()
                        .addAll(super.getAvailableCommands())
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build()

                override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

                override fun seekToNext() = hub.next()

                override fun seekToNextMediaItem() = hub.next()

                override fun seekToPrevious() = hub.next(-1)

                override fun seekToPreviousMediaItem() = hub.next(-1)
            }
        val intent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .setAction(io.github.sodarx.biliplay.core.PlaybackLaunch.ACTION)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session = MediaSession.Builder(this, forwarded).setSessionActivity(intent).build()
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) =
        if (info.uid == Process.myUid() || info.isTrusted) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        hub.checkpoint()
        val p = engine
        if (
            !hub.state.value.loading &&
                (p == null ||
                    !p.playWhenReady ||
                    p.mediaItemCount == 0 ||
                    p.playbackState == Player.STATE_ENDED)
        )
            stopSelf()
    }

    override fun onDestroy() {
        hub.detach()
        session?.release()
        engine?.release()
        session = null
        engine = null
        super.onDestroy()
    }
}
