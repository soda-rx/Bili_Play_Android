package io.github.sodarx.biliplay.core

import org.json.JSONArray

/** Verified server-side deletion paths and local-only fallbacks for notification rows. */
object NotificationDeletion {
    fun serverSide(notification: Notification): Boolean =
        when {
            notification.category == "私信" ->
                notification.talkerId > 0 && notification.sessionType in 1..2
            else -> feedId(notification) != null
        }

    fun feedId(notification: Notification): Long? {
        val pieces = notification.id.split(':')
        if (pieces.size != 2 || pieces[0] !in setOf("like", "reply", "at")) return null
        return pieces[1].toLongOrNull()?.takeIf { it > 0 }
    }

    fun feedType(notification: Notification): String? =
        when (notification.id.substringBefore(':')) {
            "like" -> "0"
            "reply" -> "1"
            "at" -> "2"
            else -> null
        }
}

/**
 * Notifications without a verified cloud delete stay hidden on this device only. The record is
 * stored per account and never keeps message text, so a later refresh cannot resurrect a row.
 */
object NotificationDismissals {
    const val MaxEntries = 500

    fun dismiss(current: List<String>, id: String): List<String> {
        if (id.isBlank()) return current
        return (listOf(id) + current.filterNot { it == id }).take(MaxEntries)
    }

    fun filter(items: List<Notification>, dismissed: Set<String>): List<Notification> =
        if (dismissed.isEmpty()) items else items.filterNot { it.id in dismissed }

    fun decode(raw: String): List<String> =
        runCatching {
                val array = JSONArray(raw)
                (0 until array.length())
                    .mapNotNull { index ->
                        array.optString(index).takeIf { it.isNotBlank() }
                    }
                    .distinct()
                    .take(MaxEntries)
            }
            .getOrDefault(emptyList())

    fun encode(ids: List<String>): String =
        JSONArray(ids.distinct().take(MaxEntries)).toString()
}
