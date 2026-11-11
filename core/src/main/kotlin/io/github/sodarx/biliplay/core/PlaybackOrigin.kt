package io.github.sodarx.biliplay.core

import org.json.JSONObject

/** Queue provenance is account-local, stored alongside the existing playback checkpoint. */
data class PlaybackOrigin(
    val kind: String,
    val id: String,
    val title: String,
    val cover: String = "",
) {
    fun encode(): String =
        JSONObject()
            .put("kind", kind)
            .put("id", id)
            .put("title", title)
            .put("cover", cover)
            .toString()

    companion object {
        fun decode(raw: String): PlaybackOrigin? = runCatching {
            val obj = JSONObject(raw)
            val kind = obj.getString("kind")
            val id = obj.getString("id")
            val title = obj.getString("title")
            require(
                kind in setOf("folder", "collection", "parts") &&
                    id.isNotBlank() &&
                    title.isNotBlank()
            )
            PlaybackOrigin(kind, id, title, obj.optString("cover"))
        }
            .getOrNull()

        fun fromVideo(video: Video?): PlaybackOrigin? =
            when {
                video == null -> null
                video.collection.isNotEmpty() ->
                    PlaybackOrigin(
                        "collection",
                        video.track.bvid,
                        video.collectionTitle.ifBlank { video.track.title },
                        video.track.cover,
                    )
                video.parts.size > 1 ->
                    PlaybackOrigin("parts", video.track.bvid, video.track.title, video.track.cover)
                else -> null
            }

        /**
         * Older checkpoints can identify multi-P safely; unrelated queues are never labelled
         * collections.
         */
        fun infer(current: Track?, queue: List<Track>): PlaybackOrigin? =
            current
                ?.takeIf { queue.size > 1 && queue.all { t -> t.bvid == it.bvid } }
                ?.let { PlaybackOrigin("parts", it.bvid, it.title, it.cover) }
    }
}
