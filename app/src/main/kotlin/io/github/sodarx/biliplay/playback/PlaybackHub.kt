package io.github.sodarx.biliplay.playback

import android.net.Uri
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.data.*
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class PlaybackSnapshot(
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val origin: PlaybackOrigin? = null,
    val queueIndex: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val sources: List<AudioSource> = emptyList(),
    val qualityIndex: Int = 0,
    val speed: Float = 1f,
    val mode: QueueMode = QueueMode.RepeatAll,
    val loading: Boolean = false,
    val error: String? = null,
    val profileId: String = "guest",
)

class PlaybackHub(private val repository: AppRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(PlaybackSnapshot())
    val state = mutable.asStateFlow()
    private var player: ExoPlayer? = null
    private var load: Job? = null
    private var retries = 0
    private var candidateIndex = 0
    private var candidates: List<String> = emptyList()
    private var currentMime = "audio/mp4"
    private var profileId = repository.profile.value.id
    private var playEpoch = 0L
    private var restored = false
    private var lastSave = 0L

    private data class Save(
        val profile: Profile,
        val snapshot: PlaybackSnapshot,
        val done: CompletableDeferred<Unit>? = null,
    )

    private val saves = Channel<Save>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for ((profile, s, done) in saves) {
                try {
                    profile.library.checkpoint(
                        mapOf(
                            "queue" to TrackJson.encodeAll(s.queue),
                            "current" to
                                s.current?.let { TrackJson.encode(it).toString() }.orEmpty(),
                            "position" to "${s.position}",
                            "duration" to "${s.duration}",
                            "speed" to "${s.speed}",
                            "queue_mode" to s.mode.name,
                            "queue_origin" to s.origin?.encode().orEmpty(),
                            "queue_index" to "${s.queueIndex}",
                        ),
                        s.current.takeIf { profile.preferences.read().recordHistory && s.playing },
                        s.position,
                    )
                } catch (_: Exception) {
                    if (state.value.profileId == profile.id)
                        mutable.update { it.copy(error = "播放记录保存失败，请检查存储空间") }
                } finally {
                    done?.complete(Unit)
                }
            }
        }
        scope.launch {
            repository.profile.collect { p ->
                val old = state.value
                if (restored && old.profileId == profileId) {
                    val done = CompletableDeferred<Unit>()
                    saves.send(Save(repository.open(profileId), old, done))
                    done.await()
                }
                cancelLoading()
                player?.stop()
                player?.clearMediaItems()
                profileId = p.id
                val queue = withContext(Dispatchers.IO) { p.library.tracks("queue") }
                val savedCurrent = runCatching {
                    TrackJson.decode(JSONObject(p.library.get("current")))
                }
                    .getOrNull()
                val queueIndex =
                    QueueMembership.restoredIndex(
                        queue,
                        savedCurrent,
                        p.library.get("queue_index", "-1").toIntOrNull() ?: -1,
                    )
                val current = queue.getOrNull(queueIndex)
                val duration =
                    PlaybackRestore.duration(
                        if (current != null) p.library.get("duration", "0").toLongOrNull() ?: 0
                        else 0,
                        current?.duration ?: 0,
                    )
                val position =
                    PlaybackRestore.position(
                        if (current != null) p.library.get("position", "0").toLongOrNull() ?: 0
                        else 0,
                        duration,
                    )
                mutable.value =
                    PlaybackSnapshot(
                        current,
                        queue,
                        queueIndex = queueIndex,
                        origin =
                            if (queue.isEmpty()) null
                            else
                                PlaybackOrigin.decode(p.library.get("queue_origin"))
                                    ?: PlaybackOrigin.infer(current, queue),
                        position = position,
                        duration = duration,
                        speed =
                            p.library.get("speed", "1.0").toFloatOrNull()?.coerceIn(.5f, 2f) ?: 1f,
                        mode =
                            QueueMode.entries.firstOrNull { it.name == p.library.get("queue_mode") }
                                ?: QueueMode.RepeatAll,
                        profileId = p.id,
                    )
                restored = true
            }
        }
        scope.launch {
            while (isActive) {
                delay(if (state.value.playing) 100L else 500L)
                synchronize()
                if (System.currentTimeMillis() - lastSave > 2_000) {
                    persist()
                    lastSave = System.currentTimeMillis()
                }
            }
        }
    }

    fun attach(value: ExoPlayer) {
        player = value
        value.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = synchronize()

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) next(ended = true)
                }

                override fun onPlayerError(error: PlaybackException) {
                    val t = state.value.current
                    if (t != null && t.localUri.isEmpty() && candidateIndex + 1 < candidates.size) {
                        candidateIndex++
                        val position = state.value.position
                        player?.setMediaItem(
                            item(t, candidates[candidateIndex], currentMime),
                            position,
                        )
                        player?.prepare()
                        player?.play()
                    } else if (t != null && t.localUri.isEmpty() && retries++ < 1)
                        play(t, startPosition = state.value.position, retry = true)
                    else {
                        val http =
                            generateSequence(error as Throwable?) { it.cause }
                                .filterIsInstance<
                                    androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                                >()
                                .firstOrNull()
                        val reason =
                            http?.let { "音源连接失败（HTTP ${it.responseCode}）" }
                                ?: "播放失败（${error.errorCodeName}）"
                        mutable.update { it.copy(loading = false, error = reason) }
                    }
                }
            }
        )
    }

    fun detach() {
        synchronize()
        persist()
        load?.cancel()
        player = null
        mutable.update { it.copy(playing = false, loading = false) }
    }

    private fun synchronize() {
        val p = player ?: return
        mutable.update {
            it.copy(
                playing = p.isPlaying,
                buffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady,
                position =
                    if (p.currentMediaItem?.mediaId == it.current?.key && p.mediaItemCount > 0)
                        p.currentPosition.coerceAtLeast(0)
                    else it.position,
                duration =
                    if (p.currentMediaItem?.mediaId == it.current?.key && p.duration > 0) p.duration
                    else it.duration,
            )
        }
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun play(
        track: Track,
        queue: List<Track>? = null,
        startPosition: Long? = null,
        retry: Boolean = false,
        origin: PlaybackOrigin? = if (queue == null) state.value.origin else null,
        queueIndex: Int? = null,
    ): Job {
        if (!retry) retries = 0
        val profile = repository.profile.value
        load?.cancel()
        val epoch = ++playEpoch
        load = scope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val local =
                    profile.library.downloads().firstOrNull {
                        it.track.bvid == track.bvid &&
                            it.track.page == track.page &&
                            it.status == "complete" &&
                            it.uri.isNotEmpty()
                    }
                val resolved =
                    if (local != null) local.track.copy(localUri = local.uri)
                    else if (track.cid > 0) track.copy(localUri = "")
                    else
                        profile.api.video(VideoInput(track.bvid, track.page)).parts.firstOrNull {
                            it.page == track.page
                        } ?: throw ApiFailure(-1, "所选分 P 不存在")
                val sources =
                    if (local != null) emptyList()
                    else profile.api.audio(resolved, profile.account?.vip == true)
                if (repository.profile.value !== profile || epoch != playEpoch) return@launch
                val p = player ?: throw IllegalStateException("播放器尚未连接")
                val nextQueue =
                    (queue
                            ?: state.value.queue.let { q ->
                                if (q.any { it.bvid == resolved.bvid && it.page == resolved.page })
                                    q
                                else q + resolved
                            })
                        .map {
                            if (it.bvid == resolved.bvid && it.page == resolved.page) resolved
                            else it
                        }
                val selectedIndex =
                    QueueMembership.restoredIndex(
                        nextQueue,
                        resolved,
                        queueIndex
                            ?: if (queue == null && state.value.current?.key == resolved.key)
                                state.value.queueIndex
                            else -1,
                    )
                val start =
                    startPosition
                        ?: if (
                            state.value.current?.key == resolved.key &&
                                state.value.queueIndex == selectedIndex
                        )
                            state.value.position
                        else 0
                mutable.update {
                    it.copy(
                        current = resolved,
                        queue = nextQueue,
                        queueIndex = selectedIndex,
                        origin = origin,
                        sources = sources,
                        qualityIndex = 0,
                        position = start,
                        duration = resolved.duration.coerceAtLeast(0) * 1000,
                    )
                }
                candidateIndex = 0
                candidates =
                    if (local != null) emptyList()
                    else (listOf(sources.first().url) + sources.first().backups).distinct()
                currentMime = if (local != null) "audio/mpeg" else sources.first().mime
                val uri = local?.uri ?: candidates.first()
                p.setMediaItem(
                    item(resolved, uri, if (local != null) "audio/mpeg" else sources.first().mime),
                    start,
                )
                p.prepare()
                p.setPlaybackSpeed(state.value.speed)
                p.play()
                if (profile.preferences.read().recordHistory)
                    withContext(Dispatchers.IO) { profile.library.record(resolved, start) }
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiFailure && e.requiresLogin) repository.markExpired(profile.id)
                mutable.update {
                    it.copy(error = if (e is ApiFailure) e.message else "播放失败，请检查网络或下载文件")
                }
            } finally {
                if (repository.profile.value === profile && epoch == playEpoch)
                    mutable.update { it.copy(loading = false) }
            }
        }
        return checkNotNull(load)
    }

    private fun item(t: Track, uri: String, mime: String) =
        MediaItem.Builder()
            .setMediaId(t.key)
            .setUri(uri)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(
                        SongTitles.display(
                            t.title,
                            repository.profile.value.preferences.read().smartSongTitles,
                        )
                    )
                    .setArtist(t.uploader)
                    .setArtworkUri(t.cover.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .build()
            )
            .build()

    fun toggle() {
        val p = player ?: return
        if (p.mediaItemCount == 0) {
            state.value.current?.let { play(it, startPosition = state.value.position) }
            return
        }
        if (p.playWhenReady) p.pause() else p.play()
        checkpoint()
    }

    fun stop() {
        load?.cancel()
        player?.pause()
        player?.clearMediaItems()
        mutable.update { it.copy(playing = false, loading = false) }
        persist()
    }

    fun seek(position: Long) {
        player?.seekTo(position.coerceAtLeast(0))
        checkpoint()
    }

    fun speed(value: Float) {
        val v = value.coerceIn(.5f, 2f)
        player?.setPlaybackSpeed(v)
        mutable.update { it.copy(speed = v) }
        persist()
    }

    fun mode(value: QueueMode) {
        mutable.update { it.copy(mode = value) }
        persist()
    }

    fun next(direction: Int = 1, ended: Boolean = false) {
        val s = state.value
        val i = QueueMembership.restoredIndex(s.queue, s.current, s.queueIndex)
        val next =
            QueuePolicy.nextIndex(s.queue.size, i, s.mode, ended, direction, Random.nextInt())
        if (next == null) {
            player?.pause()
            persist()
            return
        }
        play(s.queue[next], startPosition = 0, queueIndex = next)
    }

    fun enqueue(track: Track, next: Boolean = false) {
        val s = state.value
        val q = s.queue.toMutableList()
        val current = QueueMembership.restoredIndex(q, s.current, s.queueIndex)
        val at = if (next) (current + 1).coerceIn(0, q.size) else q.size
        q.add(at, track)
        mutable.update {
            it.copy(queue = q, queueIndex = if (current >= at) current + 1 else current)
        }
        persist()
    }

    fun remove(index: Int) {
        val old = state.value
        if (index !in old.queue.indices) return
        cancelLoading()
        val current = QueueMembership.restoredIndex(old.queue, old.current, old.queueIndex)
        val selected = QueueMembership.afterRemoval(old.queue.size, current, index)
        val queue = old.queue.filterIndexed { i, _ -> i != index }
        if (current >= 0 && current != index) {
            mutable.update {
                it.copy(queue = queue, current = queue.getOrNull(selected), queueIndex = selected)
            }
            persist()
        } else {
            resetActualPlayback(queue)
            queue.getOrNull(selected)?.let { play(it, startPosition = 0, queueIndex = selected) }
        }
    }

    fun move(index: Int, direction: Int, persistNow: Boolean = true) {
        val s = state.value
        val q = s.queue.toMutableList()
        val target = index + direction
        if (index !in q.indices || target !in q.indices) return
        val current = QueueMembership.restoredIndex(q, s.current, s.queueIndex)
        val t = q.removeAt(index)
        q.add(target, t)
        val selected = QueueMembership.afterMove(current, index, target)
        mutable.update {
            it.copy(queue = q, queueIndex = selected, current = q.getOrNull(selected))
        }
        if (persistNow) persist()
    }

    fun saveQueueOrder() {
        persist()
    }

    private fun cancelLoading() {
        playEpoch++
        load?.cancel()
        load = null
        mutable.update { it.copy(loading = false) }
    }

    private fun resetActualPlayback(queue: List<Track> = emptyList()) {
        cancelLoading()
        candidates = emptyList()
        candidateIndex = 0
        retries = 0
        mutable.update {
            PlaybackSnapshot(
                queue = queue,
                origin = if (queue.isEmpty()) null else it.origin,
                speed = it.speed,
                mode = it.mode,
                profileId = it.profileId,
            )
        }
        player?.stop()
        player?.clearMediaItems()
        persist()
    }

    fun clearQueue() = resetActualPlayback()

    fun quality(index: Int) {
        val s = state.value
        val source = s.sources.getOrNull(index) ?: return
        val track = s.current ?: return
        val p = player ?: return
        candidates = (listOf(source.url) + source.backups).distinct()
        candidateIndex = 0
        currentMime = source.mime
        retries = 0
        val resume = p.playWhenReady
        val position = p.currentPosition
        p.setMediaItem(item(track, source.url, source.mime), position)
        p.prepare()
        p.playWhenReady = resume
        mutable.update { it.copy(qualityIndex = index) }
    }

    fun checkpoint() {
        synchronize()
        persist()
    }

    private fun persist() {
        val profile = repository.profile.value
        val snapshot = state.value
        if (restored && profile.id == snapshot.profileId) saves.trySend(Save(profile, snapshot))
    }
}
