package dev.ra.geepee

internal data class RouteLoadState(
    val routeName: String? = null,
    val issueMessage: String? = null,
    val routeLoading: Boolean = false,
) {
    fun beginLoading(): RouteLoadState {
        return copy(routeLoading = true, issueMessage = null)
    }

    fun loadSucceeded(routeName: String): RouteLoadState {
        return copy(routeName = routeName, issueMessage = null, routeLoading = false)
    }

    fun loadFailed(issueMessage: String): RouteLoadState {
        return copy(issueMessage = issueMessage, routeLoading = false)
    }

    fun clearIssue(): RouteLoadState {
        return copy(issueMessage = null)
    }

    fun clearRoute(): RouteLoadState {
        return RouteLoadState()
    }
}
