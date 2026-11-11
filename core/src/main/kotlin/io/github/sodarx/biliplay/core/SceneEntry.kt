package io.github.sodarx.biliplay.core

data class SceneEntry(val profileId: String, val group: String, val visit: Long) {
    companion object {
        fun of(profileId: String, route: String, visit: Long): SceneEntry =
            when (route) {
                in CoverPresentation.primaryRoutes -> SceneEntry(profileId, "primary", 0)
                "player",
                "queue" -> SceneEntry(profileId, "playback", 0)
                else -> SceneEntry(profileId, route, visit)
            }
    }
}

data class RouteVisit(val route: String, val id: Long)

/** Back restores an existing visit; forward navigation always creates a fresh one. */
class RouteHistory {
    private val stack = ArrayDeque<RouteVisit>()
    private var serial = 0L

    fun clear() {
        stack.clear()
    }

    fun navigate(current: RouteVisit, route: String): RouteVisit {
        if (route in CoverPresentation.primaryRoutes) stack.clear()
        if (current.route == route) return current
        if (route !in CoverPresentation.primaryRoutes) stack.addLast(current)
        return RouteVisit(route, ++serial)
    }

    fun back(fallback: String): RouteVisit =
        stack.removeLastOrNull() ?: RouteVisit(fallback, ++serial)
}
