package dev.ra.geepee

internal data class GeePeeUiProjectionInputs(
    val routeLoadState: RouteLoadState,
    val routeModel: RouteModel?,
    val currentFix: LocationFix?,
    val analysis: RouteAnalysis?,
    val routeMatchHypotheses: List<RouteMatchDisplayHypothesis>,
    val locationHistoryPoints: List<ProjectedPoint>,
    val compass: CompassState?,
    val sessionState: SessionState,
    val appPreferences: AppPreferences,
    val tileContextConfig: TileContextConfig,
    val tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
    val routeContextState: RouteContextState,
    val debugGpsEnabled: Boolean,
    val locationProvidersEnabled: Boolean,
    val headingDegrees: Double?,
)

internal fun buildGeePeeUiState(inputs: GeePeeUiProjectionInputs): GeePeeUiState {
    val status = buildRouteStatus(
        RouteStatusInputs(
            routeLoading = inputs.routeLoadState.routeLoading,
            routeModel = inputs.routeModel,
            issueMessage = inputs.routeLoadState.issueMessage,
            sessionActive = inputs.sessionState.sessionActive,
            hasLocationPermission = inputs.sessionState.hasLocationPermission,
            hasFinePermission = inputs.sessionState.hasFinePermission,
            locationProvidersEnabled = inputs.locationProvidersEnabled,
            currentFix = inputs.currentFix,
            currentAnalysis = inputs.analysis,
            headingDegrees = inputs.headingDegrees,
        ),
    )
    return GeePeeUiState(
        routeName = inputs.routeLoadState.routeName,
        routeModel = inputs.routeModel,
        analysis = inputs.analysis,
        currentLocationGeoPoint = inputs.analysis?.nearestGeoPoint ?: inputs.currentFix?.let { fix ->
            GeoPoint(lat = fix.lat, lon = fix.lon)
        },
        routeMatchHypotheses = inputs.routeMatchHypotheses,
        locationHistoryPoints = inputs.locationHistoryPoints,
        compass = inputs.compass,
        lastFixTimestampMillis = inputs.currentFix?.timestampMillis,
        darkModeEnabled = inputs.appPreferences.darkModeEnabled,
        orientationMode = inputs.appPreferences.orientationMode,
        routeScale = inputs.appPreferences.routeScale,
        tileContextConfig = inputs.tileContextConfig,
        tileDownloads = inputs.tileDownloads,
        routePois = inputs.routeContextState.pois,
        routeContextDebugText = routeContextDebugText(inputs.routeContextState.mapInfo.localNearbyWays),
        routeNearbyWays = inputs.routeContextState.mapInfo.nearbyWays,
        debugGpsEnabled = inputs.debugGpsEnabled,
        sessionRunning = inputs.sessionState.sessionActive,
        routeLoading = inputs.routeLoadState.routeLoading,
        hasCoarsePermission = inputs.sessionState.hasCoarsePermission,
        hasFinePermission = inputs.sessionState.hasFinePermission,
        batterySaverEnabled = inputs.appPreferences.batterySaverEnabled,
        status = status,
    )
}

internal fun routeContextDebugText(status: LocalNearbyWayDebugStatus?): String? {
    status ?: return null
    status.errorMessage?.let {
        return "Map info for this view: unavailable"
    }
    val hasVisibleTileData = status.hasVisibleTileData ?: return null
    if (!hasVisibleTileData) {
        return "Map info for this view: not downloaded"
    }
    return when {
        status.nearbyWaysLoading -> "Map info for this view: loading…"
        status.localTileCount > 0 && status.loadedLocalTileCount < status.localTileCount -> "Map info for this view: partly available"
        else -> "Map info for this view: available"
    }
}
