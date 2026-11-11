package io.github.sodarx.biliplay.core

object PlaybackLaunch {
    const val ACTION = "io.github.sodarx.biliplay.OPEN_PLAYER"

    fun canOpen(
        agreement: Boolean,
        themeReady: Boolean,
        loggedIn: Boolean,
        currentKey: String?,
        queueKeys: List<String>,
    ): Boolean =
        agreement && themeReady && loggedIn && currentKey != null && currentKey in queueKeys
}
