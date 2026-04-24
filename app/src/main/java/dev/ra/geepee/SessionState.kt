package dev.ra.geepee

internal data class SessionTransition(
    val state: SessionState,
    val clearLiveState: Boolean = false,
    val persistSessionActive: Boolean? = null,
)

internal data class SessionState(
    val hasCoarsePermission: Boolean = false,
    val hasFinePermission: Boolean = false,
    val sessionActive: Boolean = false,
    val pendingSessionStart: Boolean = false,
    val isForeground: Boolean = false,
) {
    val hasLocationPermission: Boolean
        get() = hasCoarsePermission || hasFinePermission

    val shouldTrackLocation: Boolean
        get() = sessionActive && isForeground && hasLocationPermission

    val shouldTrackHeading: Boolean
        get() = sessionActive && isForeground

    fun onPermissionResult(
        coarseGranted: Boolean,
        fineGranted: Boolean,
        routeLoaded: Boolean,
    ): SessionTransition {
        val permissionUpdate = withPermissions(coarseGranted, fineGranted)
        val nextState = permissionUpdate.state
        return if (nextState.pendingSessionStart && nextState.hasLocationPermission && routeLoaded) {
            SessionTransition(
                state = nextState.copy(
                    sessionActive = true,
                    pendingSessionStart = false,
                ),
                clearLiveState = true,
                persistSessionActive = true,
            )
        } else {
            permissionUpdate
        }
    }

    fun withPermissions(
        coarseGranted: Boolean,
        fineGranted: Boolean,
    ): SessionTransition {
        val nextState = copy(
            hasCoarsePermission = coarseGranted,
            hasFinePermission = fineGranted,
        )
        return if (!nextState.hasLocationPermission) {
            SessionTransition(
                state = nextState.copy(
                    sessionActive = false,
                    pendingSessionStart = false,
                ),
                clearLiveState = nextState.sessionActive,
                persistSessionActive = if (nextState.sessionActive) false else null,
            )
        } else {
            SessionTransition(state = nextState)
        }
    }

    fun withForeground(isForeground: Boolean): SessionTransition {
        return SessionTransition(
            state = copy(isForeground = isForeground),
        )
    }

    fun requestStart(): SessionTransition {
        return SessionTransition(
            state = copy(pendingSessionStart = true),
        )
    }

    fun start(routeLoaded: Boolean): SessionTransition {
        if (!hasLocationPermission || !routeLoaded) {
            return SessionTransition(state = this)
        }
        return SessionTransition(
            state = copy(
                sessionActive = true,
                pendingSessionStart = false,
            ),
            clearLiveState = true,
            persistSessionActive = true,
        )
    }

    fun stop(): SessionTransition {
        return SessionTransition(
            state = copy(
                sessionActive = false,
                pendingSessionStart = false,
            ),
            clearLiveState = sessionActive,
            persistSessionActive = if (sessionActive) false else null,
        )
    }
}
