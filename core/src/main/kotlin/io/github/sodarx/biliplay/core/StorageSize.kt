package io.github.sodarx.biliplay.core

import java.util.Locale

/** Human-readable storage sizes with an automatically selected unit. */
object StorageSize {
    private val units = listOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        if (unit == 0) return "${bytes} B"
        val pattern =
            when {
                value >= 100 -> "%.0f"
                value >= 10 -> "%.1f"
                else -> "%.1f"
            }
        return "${String.format(Locale.ROOT, pattern, value)} ${units[unit]}"
    }
}
