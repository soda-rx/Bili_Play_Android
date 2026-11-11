package io.github.sodarx.biliplay.core

import java.util.UUID

object DownloadNames {
    fun privateFile(track: Track, job: UUID): String {
        val video =
            track.bvid.takeIf { Regex("BV[0-9A-Za-z]{10}").matches(it) }
                ?: "av${track.aid.coerceAtLeast(0)}"
        return "${video}_p${track.page.coerceAtLeast(1)}_$job.mp3"
    }
}
