package io.github.sodarx.biliplay.core

import java.util.Locale

/**
 * Sleep timer helpers shared by the playback hub and the UI. The deadline is boot-relative
 * elapsedRealtime so wall-clock changes cannot make a countdown jump.
 */
object SleepTimer {
    const val MAX_MINUTES = 720
    val presets = listOf(15, 30, 60)
    fun endsAt(now: Long, minutes: Int): Long? =
        if (minutes in 1..MAX_MINUTES) now + minutes * 60_000L else null

    fun remaining(endsAt: Long, now: Long): Long = (endsAt - now).coerceAtLeast(0L)

    fun label(remainingMillis: Long): String {
        val total = (remainingMillis.coerceAtLeast(0L) / 1000L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }

    fun parseMinutes(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }
}
