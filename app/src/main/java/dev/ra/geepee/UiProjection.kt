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
        routeMatchHypotheses = inputs.routeMatchHypotheses,
        locationHistoryPoints = inputs.locationHistoryPoints,
        compass = inputs.compass,
        lastFixTimestampMillis = inputs.currentFix?.timestampMillis,
        darkModeEnabled = inputs.appPreferences.darkModeEnabled,
        orientationMode = inputs.appPreferences.orientationMode,
        routeScale = inputs.appPreferences.routeScale,
        setupOverviewMode = inputs.appPreferences.setupOverviewMode,
        tileContextConfig = inputs.tileContextConfig,
        tileDownloads = inputs.tileDownloads,
        sessionRunning = inputs.sessionState.sessionActive,
        routeLoading = inputs.routeLoadState.routeLoading,
        hasCoarsePermission = inputs.sessionState.hasCoarsePermission,
        hasFinePermission = inputs.sessionState.hasFinePermission,
        batterySaverEnabled = inputs.appPreferences.batterySaverEnabled,
        status = status,
    )
}
