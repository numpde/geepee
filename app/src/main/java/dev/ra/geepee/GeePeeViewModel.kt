package dev.ra.geepee

import android.app.Application
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val LOG_TAG = "GeePee"

internal class GeePeeViewModel(application: Application) : AndroidViewModel(application) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val appStateStore = AppStateStore(application)
    private val callbackExecutor = Executor(mainHandler::post)
    private val routeRepository = RouteRepository(
        contentResolver = application.contentResolver,
        appStateStore = appStateStore,
        logTag = LOG_TAG,
    )
    private val routeLoadCoordinator = RouteLoadCoordinator<Uri>(
        loadRoute = routeRepository::loadRoute,
        rememberRoute = routeRepository::rememberSelectedRoute,
        workExecutor = ioExecutor,
        callbackExecutor = callbackExecutor,
        logFailure = { routeUri, error ->
            Log.e(LOG_TAG, "Route load failed for uri=$routeUri", error)
        },
    )
    private val liveTrackingController = LiveTrackingController(
        application = application,
        onLocation = ::handleLocation,
        onHeadingDegrees = ::handleHeadingChanged,
    )
    private val tileContextRepository = TileContextRepository(application)
    private val tileContextConfig = DefaultTileContextConfig
    private val tileDownloadCoordinator = TileDownloadCoordinator(
        tileContextRepository = tileContextRepository,
        tileContextConfig = tileContextConfig,
        callbackExecutor = callbackExecutor,
        logTag = LOG_TAG,
    )
    private val routeContextCoordinator = RouteContextCoordinator(
        tileContextRepository = tileContextRepository,
        tileContextConfig = tileContextConfig,
        callbackExecutor = callbackExecutor,
        logTag = LOG_TAG,
    )
    private val routeRuntimeState = RouteRuntimeState()

    private var routeLoadState = RouteLoadState()
    private var sessionState = SessionState()
    private var appPreferences = AppPreferences()
    private var selectedRouteUri: Uri? = null
    private var selectedRouteBaseName: String? = null
    private var selectedRouteReversed: Boolean = false
    private var tileDownloads: Map<DownloadTileId, TileDownloadSnapshot> = tileContextRepository.cachedTileSnapshots()
    private var routeContextState = RouteContextState()
    private var debugGpsEnabled = false
    private var liveContextFocus: MapInfoFocus? = null

    var uiState by mutableStateOf(GeePeeUiState())
        private set

    init {
        val restoredState = appStateStore.load()
        sessionState = sessionState.copy(sessionActive = restoredState.sessionActive)
        appPreferences = restoredState.preferences
        selectedRouteUri = restoredState.routeUri
        selectedRouteBaseName = restoredState.routeName
        selectedRouteReversed = restoredState.routeReversed
        restoreSelectedRouteIfNeeded(restoredState)
        recomputeUiState()
    }

    fun onPermissionRequestResult(coarseGranted: Boolean, fineGranted: Boolean) {
        applySessionTransition(
            sessionState.onPermissionResult(
                coarseGranted = coarseGranted,
                fineGranted = fineGranted,
                routeLoaded = routeRuntimeState.routeModel != null,
            ),
        )
        if (!sessionState.hasLocationPermission) {
            routeLoadState = routeLoadState.clearIssue()
        }
        syncTrackingState()
        recomputeUiState()
    }

    fun updateLocationPermissions(coarseGranted: Boolean, fineGranted: Boolean) {
        applySessionTransition(
            sessionState.withPermissions(
                coarseGranted = coarseGranted,
                fineGranted = fineGranted,
            ),
        )
        if (!sessionState.hasLocationPermission) {
            routeLoadState = routeLoadState.clearIssue()
        }
        syncTrackingState()
        recomputeUiState()
    }

    fun setForeground(isForeground: Boolean) {
        applySessionTransition(sessionState.withForeground(isForeground))
        syncTrackingState()
        recomputeUiState()
    }

    fun requestSessionStart() {
        applySessionTransition(sessionState.requestStart())
        recomputeUiState()
    }

    fun toggleDarkMode() {
        updatePreferences { copy(darkModeEnabled = !darkModeEnabled) }
        recomputeUiState()
    }

    fun toggleOrientationMode() {
        updatePreferences {
            copy(
                orientationMode = when (orientationMode) {
                    OrientationMode.CourseUp -> OrientationMode.NorthUp
                    OrientationMode.NorthUp -> OrientationMode.CourseUp
                },
            )
        }
        recomputeUiState()
    }

    fun cycleRouteScale() {
        updateRouteScale(appPreferences.routeScale.next())
    }

    fun setRouteScale(scale: RouteScale) {
        updateRouteScale(scale)
    }

    fun startMonitoring() {
        applySessionTransition(
            sessionState.start(routeLoaded = routeRuntimeState.routeModel != null),
        )
        routeLoadState = routeLoadState.clearIssue()
        syncTrackingState()
        recomputeUiState()
    }

    fun setBatterySaverEnabled(enabled: Boolean) {
        if (appPreferences.batterySaverEnabled == enabled) {
            return
        }
        updatePreferences { copy(batterySaverEnabled = enabled) }
        if (sessionState.shouldTrackLocation) {
            stopLocationUpdates()
            startLocationUpdatesIfPossible()
        }
        if (sessionState.shouldTrackHeading) {
            stopHeadingUpdates()
            startHeadingUpdatesIfPossible()
        }
        recomputeUiState()
    }

    fun stopMonitoring() {
        applySessionTransition(sessionState.stop())
        clearMapInfoFocus(clearCoordinator = true)
        debugGpsEnabled = false
        routeLoadState = routeLoadState.clearIssue()
        syncTrackingState()
        recomputeUiState()
    }

    fun requestImmediateLocationRefresh() {
        if (!sessionState.shouldTrackLocation) {
            return
        }

        if (!liveTrackingController.requestImmediateLocationRefresh(sessionState.hasFinePermission)) {
            routeLoadState = routeLoadState.loadFailed("Enable GPS or network location on the phone.")
            recomputeUiState()
        }
    }

    fun downloadTile(tileId: DownloadTileId, estimatedBytes: Long) {
        when (tileDownloads[tileId]?.status) {
            TileDownloadStatus.Downloading -> {
                tileDownloadCoordinator.cancelDownload(tileId) { update ->
                    handleTileDownloadUpdate(tileId, update)
                }
                return
            }
            TileDownloadStatus.Cached -> return
            TileDownloadStatus.Error,
            null,
            -> Unit
        }

        tileDownloads = tileDownloads + (
            tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Downloading,
                estimatedBytes = estimatedBytes,
            )
        )
        recomputeUiState()
        tileDownloadCoordinator.startDownload(
            tileId = tileId,
            estimatedBytes = estimatedBytes,
        ) { update ->
            handleTileDownloadUpdate(tileId, update)
        }
    }

    fun reverseRoute() {
        val routeUri = selectedRouteUri ?: return
        loadRoute(
            uri = routeUri,
            displayName = selectedRouteBaseName,
            reversed = !selectedRouteReversed,
            rememberSelection = true,
        )
    }

    fun loadRoute(
        uri: Uri,
        displayName: String?,
        reversed: Boolean = false,
        rememberSelection: Boolean = true,
        fromRestore: Boolean = false,
    ) {
        resetRouteContextState()
        debugGpsEnabled = false
        routeLoadState = routeLoadState.beginLoading()
        recomputeUiState()

        routeLoadCoordinator.load(
            request = RouteLoadRequest(
                routeRef = uri,
                displayName = displayName,
                reversed = reversed,
                rememberSelection = rememberSelection,
                fromRestore = fromRestore,
            ),
            onOutcome = { outcome ->
                when (outcome) {
                    is RouteLoadOutcome.Success -> {
                        routeRuntimeState.applyRoute(outcome.loadedRoute.model)
                        selectedRouteUri = uri
                        selectedRouteBaseName = outcome.loadedRoute.baseDisplayName
                        selectedRouteReversed = outcome.loadedRoute.isReversed
                        routeLoadState = routeLoadState.loadSucceeded(outcome.loadedRoute.displayName)
                        rebuildRouteContextAsync()
                        rebuildNearbyWaysAsync(force = true)
                    }

                    is RouteLoadOutcome.Failure -> {
                        if (outcome.clearRememberedRoute) {
                            clearRememberedRoute()
                        }
                        routeLoadState = routeLoadState.loadFailed(outcome.issueMessage)
                    }
                }
                recomputeUiState()
            },
        )
    }

    override fun onCleared() {
        liveTrackingController.shutdown()
        ioExecutor.shutdownNow()
        tileDownloadCoordinator.shutdown()
        routeContextCoordinator.shutdown()
        super.onCleared()
    }

    private fun handleLocation(location: Location) {
        if (debugGpsEnabled) {
            return
        }
        routeRuntimeState.acceptLocation(
            location = location,
            sessionActive = sessionState.sessionActive,
            batterySaverEnabled = appPreferences.batterySaverEnabled,
        )
        routeLoadState = routeLoadState.clearIssue()
        rebuildNearbyWaysAsync()
        recomputeUiState()
    }

    private fun handleHeadingChanged(headingDegrees: Double) {
        routeRuntimeState.acceptHeading(
            headingDegrees = headingDegrees,
            batterySaverEnabled = appPreferences.batterySaverEnabled,
        )
        recomputeUiState()
    }

    private fun startLocationUpdatesIfPossible() {
        if (liveTrackingController.receivingLocationUpdates || !sessionState.shouldTrackLocation) {
            return
        }

        // GeePee only requests live location during an active foreground session.
        if (!liveTrackingController.startLocationUpdates(currentLiveTrackingConfig())) {
            routeLoadState = routeLoadState.loadFailed("Enable GPS or network location on the phone.")
            recomputeUiState()
            return
        }
    }

    private fun stopLocationUpdates() {
        if (!liveTrackingController.receivingLocationUpdates) {
            return
        }
        liveTrackingController.stopLocationUpdates()
    }

    private fun startHeadingUpdatesIfPossible() {
        if (liveTrackingController.receivingHeadingUpdates || !sessionState.shouldTrackHeading) {
            return
        }
        liveTrackingController.startHeadingUpdates(currentLiveTrackingConfig())
    }

    private fun stopHeadingUpdates() {
        liveTrackingController.stopHeadingUpdates()
        routeRuntimeState.clearHeadingState()
    }

    private fun restoreSelectedRouteIfNeeded(restoredState: RestorableAppState) {
        val routeUri = restoredState.routeUri ?: return
        loadRoute(
            uri = routeUri,
            displayName = restoredState.routeName,
            reversed = restoredState.routeReversed,
            rememberSelection = false,
            fromRestore = true,
        )
    }

    private fun clearRememberedRoute() {
        resetRouteContextState()
        routeRepository.clearRememberedRoute()
        applySessionTransition(sessionState.stop())
        selectedRouteUri = null
        selectedRouteBaseName = null
        selectedRouteReversed = false
        routeRuntimeState.clearRoute()
        routeLoadState = routeLoadState.clearRoute()
        debugGpsEnabled = false
    }

    fun toggleDebugGps() {
        debugGpsEnabled = !debugGpsEnabled
        if (!debugGpsEnabled) {
            clearMapInfoFocus(clearCoordinator = true)
            requestImmediateLocationRefresh()
        }
        recomputeUiState()
    }

    fun setDebugGpsLocation(point: GeoPoint, focusWindowWidthMeters: Double) {
        val timestampMillis = System.currentTimeMillis()
        routeRuntimeState.teleportToFix(
            fix = LocationFix(
                lat = point.lat,
                lon = point.lon,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = timestampMillis,
            ),
            sessionActive = sessionState.sessionActive,
            batterySaverEnabled = appPreferences.batterySaverEnabled,
        )
        routeLoadState = routeLoadState.clearIssue()
        replaceMapInfoFocus(
            focus = MapInfoFocus(
                centerGeoPoint = point,
                windowWidthMeters = focusWindowWidthMeters,
            ),
            clearCoordinator = true,
        )
        rebuildNearbyWaysAsync(force = true)
        recomputeUiState()
    }

    fun updateLiveContextFocus(focus: MapInfoFocus) {
        val analysis = routeRuntimeState.currentAnalysis ?: run {
            return
        }
        val previousFocus = liveContextFocus
        val defaultWidthMeters = appPreferences.routeScale.windowWidthMeters
        if (
            previousFocus == null &&
            kotlin.math.abs(focus.windowWidthMeters - defaultWidthMeters) <= maxOf(5.0, defaultWidthMeters * 0.05) &&
            distanceBetweenGeoPointsMeters(focus.centerGeoPoint, analysis.nearestGeoPoint) <= 3.0
        ) {
            return
        }
        if (!mapInfoFocusChanged(previousFocus, focus)) {
            return
        }
        liveContextFocus = focus
        rebuildNearbyWaysAsync(force = true)
    }

    private fun updateRouteScale(scale: RouteScale) {
        if (appPreferences.routeScale == scale) {
            return
        }
        updatePreferences { copy(routeScale = scale) }
        recomputeUiState()
    }

    private fun clearMapInfoFocus(clearCoordinator: Boolean) {
        replaceMapInfoFocus(focus = null, clearCoordinator = clearCoordinator)
    }

    private fun replaceMapInfoFocus(
        focus: MapInfoFocus?,
        clearCoordinator: Boolean,
    ) {
        liveContextFocus = focus
        if (clearCoordinator) {
            routeContextCoordinator.clear()
        }
    }

    private fun resetRouteContextState() {
        routeContextState = RouteContextState()
        clearMapInfoFocus(clearCoordinator = true)
    }

    private fun mapInfoFocusChanged(
        previous: MapInfoFocus?,
        current: MapInfoFocus,
    ): Boolean {
        val previousFocus = previous ?: return true
        val widthChanged = kotlin.math.abs(previousFocus.windowWidthMeters - current.windowWidthMeters) >
            maxOf(5.0, previousFocus.windowWidthMeters * 0.05)
        if (widthChanged) {
            return true
        }
        val movementThresholdMeters = maxOf(25.0, current.windowWidthMeters * 0.2)
        return distanceBetweenGeoPointsMeters(previousFocus.centerGeoPoint, current.centerGeoPoint) > movementThresholdMeters
    }

    private fun currentLiveTrackingConfig(): LiveTrackingConfig {
        return liveTrackingConfig(appPreferences.batterySaverEnabled)
    }

    private fun recomputeUiState() {
        uiState = buildGeePeeUiState(
            GeePeeUiProjectionInputs(
                routeLoadState = routeLoadState,
                routeModel = routeRuntimeState.routeModel,
                currentFix = routeRuntimeState.currentFix,
                analysis = routeRuntimeState.currentAnalysis,
                routeMatchHypotheses = routeRuntimeState.currentMatchHypotheses,
                locationHistoryPoints = routeRuntimeState.locationHistoryPoints,
                compass = buildCompassState(),
                sessionState = sessionState,
                appPreferences = appPreferences,
                tileContextConfig = tileContextConfig,
                tileDownloads = tileDownloads,
                routeContextState = routeContextState,
                debugGpsEnabled = debugGpsEnabled,
                locationProvidersEnabled = liveTrackingController.hasEnabledProviders(),
                headingDegrees = routeRuntimeState.displayHeadingDegrees(),
            ),
        )
    }

    private fun buildCompassState(): CompassState? {
        return routeRuntimeState.buildCompassState()
    }

    private fun applySessionTransition(transition: SessionTransition) {
        sessionState = transition.state
        if (transition.clearLiveState) {
            routeRuntimeState.clearLiveState()
        }
        transition.persistSessionActive?.let(appStateStore::setSessionActive)
    }

    private fun syncTrackingState() {
        if (sessionState.shouldTrackLocation) {
            startLocationUpdatesIfPossible()
        } else {
            stopLocationUpdates()
        }

        if (sessionState.shouldTrackHeading) {
            startHeadingUpdatesIfPossible()
        } else {
            stopHeadingUpdates()
        }
    }

    private fun updatePreferences(transform: AppPreferences.() -> AppPreferences) {
        val updatedPreferences = appPreferences.transform()
        if (updatedPreferences == appPreferences) {
            return
        }
        appPreferences = updatedPreferences
        appStateStore.savePreferences(updatedPreferences)
    }

    private fun rebuildRouteContextAsync() {
        val routeModel = routeRuntimeState.routeModel ?: run {
            routeContextState = routeContextState.withPois(emptyList())
            return
        }
        routeContextCoordinator.rebuildRouteContext(routeModel) { result ->
            routeContextState = routeContextState.withPois(result.pois)
            recomputeUiState()
        }
    }

    private fun rebuildNearbyWaysAsync(force: Boolean = false) {
        val routeModel = routeRuntimeState.routeModel ?: run {
            routeContextState = routeContextState.withMapInfo(
                nearbyWays = emptyList(),
                status = null,
            )
            return
        }
        val analysis = routeRuntimeState.currentAnalysis ?: run {
            routeContextState = routeContextState.withMapInfo(
                nearbyWays = emptyList(),
                status = routeContextState.mapInfo.localNearbyWays?.copy(
                    nearbyWayCount = 0,
                    nearbyWaysLoading = false,
                    errorMessage = null,
                ),
            )
            return
        }
        routeContextCoordinator.rebuildNearbyWays(
            routeModel = routeModel,
            analysis = analysis,
            tileDownloads = tileDownloads,
            existingLocalStatus = routeContextState.mapInfo.localNearbyWays,
            focus = liveContextFocus,
            defaultFocusWindowWidthMeters = appPreferences.routeScale.windowWidthMeters,
            force = force,
            onStarted = { startedStatus ->
                routeContextState = routeContextState.withNearbyWayStatus(startedStatus)
                recomputeUiState()
            },
            onResult = { result ->
                routeContextState = routeContextState.withMapInfo(
                    nearbyWays = result.nearbyWays,
                    status = result.localNearbyWays.copy(
                        nearbyWaysLoading = false,
                    ),
                )
                recomputeUiState()
            },
        )
    }

    private fun handleTileDownloadUpdate(
        tileId: DownloadTileId,
        update: TileDownloadUpdate,
    ) {
        when (update) {
            is TileDownloadUpdate.Progress -> {
                val currentSnapshot = tileDownloads[tileId]
                if (currentSnapshot?.status == TileDownloadStatus.Downloading) {
                    tileDownloads = tileDownloads + (
                        tileId to currentSnapshot.copy(
                            downloadedBytes = update.downloadedBytes,
                            actualBytes = update.actualBytes,
                        )
                    )
                    recomputeUiState()
                }
            }

            is TileDownloadUpdate.Success -> {
                tileDownloads = tileDownloads + (
                    tileId to update.snapshot
                )
                rebuildRouteContextAsync()
                rebuildNearbyWaysAsync(force = true)
                recomputeUiState()
            }

            TileDownloadUpdate.Cancelled -> {
                if (tileDownloads[tileId]?.status == TileDownloadStatus.Downloading) {
                    tileDownloads = tileDownloads - tileId
                    recomputeUiState()
                }
            }

            is TileDownloadUpdate.Error -> {
                val estimatedBytes = tileDownloads[tileId]?.estimatedBytes ?: 0L
                tileDownloads = tileDownloads + (
                    tileId to TileDownloadSnapshot(
                        status = TileDownloadStatus.Error,
                        estimatedBytes = estimatedBytes,
                        errorMessage = update.message,
                    )
                )
                recomputeUiState()
            }
        }
    }
}
