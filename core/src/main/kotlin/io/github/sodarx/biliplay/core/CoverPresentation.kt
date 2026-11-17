package io.github.sodarx.biliplay.core

enum class CoverDestination {
    Hidden,
    Dock,
    Player,
}

object CoverPresentation {
    val primaryRoutes = listOf("home", "favorites", "mine")
    val playerBarRoutes = primaryRoutes + "search"

    fun destination(route: String, hasAccount: Boolean, hasTrack: Boolean): CoverDestination =
        when {
            !hasAccount || !hasTrack || route == "login" -> CoverDestination.Hidden
            route == "player" -> CoverDestination.Player
            route in playerBarRoutes -> CoverDestination.Dock
            else -> CoverDestination.Hidden
        }

    /** Hidden artwork has no visible geometry to morph from (notably lyric-mode exit). */
    fun shouldMorph(
        previous: CoverDestination,
        next: CoverDestination,
        hasSource: Boolean,
        moving: Boolean,
    ): Boolean =
        hasSource &&
            previous != CoverDestination.Hidden &&
            next != CoverDestination.Hidden &&
            (previous != next || moving)

    fun acceptsMediaEvent(
        route: String,
        session: Long,
        key: String?,
        eventSession: Long,
        eventKey: String,
    ): Boolean = route == "player" && session == eventSession && key == eventKey

    fun shouldCommitPage(userInitiated: Boolean, scrolling: Boolean, route: String): Boolean =
        userInitiated && !scrolling && route == "player"

    fun canCaptureSource(destination: CoverDestination, paging: Boolean): Boolean =
        destination != CoverDestination.Hidden && (destination == CoverDestination.Dock || !paging)

    fun isNewPlayerEntry(previousRoute: String, nextRoute: String): Boolean =
        nextRoute == "player" && previousRoute !in setOf("player", "queue", "comments", "replies")

    fun lyricsAfterNavigation(previousRoute: String, nextRoute: String, lyrics: Boolean): Boolean =
        if (isNewPlayerEntry(previousRoute, nextRoute)) false else lyrics

    fun playerOverlayVisible(
        requested: CoverDestination,
        presented: CoverDestination,
        moving: Boolean,
        hasFrozenSource: Boolean,
        paging: Boolean,
    ): Boolean =
        requested == CoverDestination.Player &&
            !paging &&
            (moving || (presented == CoverDestination.Dock && hasFrozenSource))

    /**
     * Pager owns stationary detail/capsule art; foreground owns only the transfer. A remembered
     * lyric selection must never hide the restored dock artwork.
     */
    fun foregroundArtworkVisible(
        destination: CoverDestination,
        playerTransfer: Boolean,
        moving: Boolean,
        capsuleInPage: Boolean,
        paging: Boolean,
    ): Boolean =
        when (destination) {
            CoverDestination.Player -> !paging && playerTransfer
            CoverDestination.Dock -> moving || !capsuleInPage
            CoverDestination.Hidden -> false
        }

    fun sceneLayer(route: String): Float =
        if (route in listOf("player", "queue", "playback")) 1f else 0f

    fun capsuleSurfaceVisible(
        requested: CoverDestination,
        presented: CoverDestination,
        moving: Boolean,
    ): Boolean = requested == CoverDestination.Dock && presented == CoverDestination.Dock && !moving

    /** The rim emerges with the shrinking artwork, rather than at its destination. */
    fun capsuleRimProgress(startSide: Float, currentSide: Float, targetSide: Float): Float {
        if (
            !startSide.isFinite() ||
                startSide <= 0f ||
                !currentSide.isFinite() ||
                currentSide <= 0f ||
                !targetSide.isFinite() ||
                targetSide <= 0f ||
                startSide <= targetSide
        )
            return 0f
        val travel = ((startSide - currentSide) / (startSide - targetSide)).coerceIn(0f, 1f)
        return ((travel - .55f) / .45f).coerceIn(0f, 1f)
    }

    fun bottomNavigationSpace(
        route: String,
        account: Boolean,
        measured: Float?,
        systemBottom: Float,
    ): Float {
        if (!account || route !in primaryRoutes) return 0f
        return measured?.takeIf { it.isFinite() && it > 0f }
            ?: (80f + systemBottom.takeIf { it.isFinite() && it > 0f }.let { it ?: 0f })
    }

    fun navigationLayer(
        route: String,
        account: Boolean,
        comments: Boolean,
        playbackInFront: Boolean = false,
    ): Float = if (account && !comments && !playbackInFront && route in primaryRoutes) 2f else -1f

    fun playerAnchorTop(actualTop: Float, sheetOffset: Float): Float =
        actualTop - if (sheetOffset.isFinite()) sheetOffset else 0f

    fun validBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        minimumTop: Float,
    ): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() } &&
            right > left &&
            bottom > top &&
            top >= minimumTop
}
