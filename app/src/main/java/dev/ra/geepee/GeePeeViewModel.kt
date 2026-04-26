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
    private val tileDownloadExecutor: ExecutorService = Executors.newCachedThreadPool()
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
    private val routeRuntimeState = RouteRuntimeState()
    private val tileContextConfig = DefaultTileContextConfig

    private var routeLoadState = RouteLoadState()
    private var sessionState = SessionState()
    private var appPreferences = AppPreferences()
    private var tileDownloads: Map<DownloadTileId, TileDownloadSnapshot> = tileContextRepository.cachedTileSnapshots()

    var uiState by mutableStateOf(GeePeeUiState())
        private set

    init {
        val restoredState = appStateStore.load()
        sessionState = sessionState.copy(sessionActive = restoredState.sessionActive)
        appPreferences = restoredState.preferences
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

    fun toggleSetupOverviewMode() {
        updatePreferences {
            copy(
                setupOverviewMode = when (setupOverviewMode) {
                    SetupOverviewMode.Route -> SetupOverviewMode.Tiles
                    SetupOverviewMode.Tiles -> SetupOverviewMode.Route
                },
            )
        }
        recomputeUiState()
    }

    fun zoomInRouteScale() {
        updateRouteScale(appPreferences.routeScale.zoomIn())
    }

    fun zoomOutRouteScale() {
        updateRouteScale(appPreferences.routeScale.zoomOut())
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
            TileDownloadStatus.Downloading,
            TileDownloadStatus.Cached,
            -> return
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

        tileDownloadExecutor.execute {
            try {
                val cachedSnapshot = tileContextRepository.downloadTile(
                    tileId = tileId,
                    config = tileContextConfig,
                ) { downloadedBytes, contentLengthBytes ->
                    callbackExecutor.execute {
                        val currentSnapshot = tileDownloads[tileId]
                        if (currentSnapshot?.status == TileDownloadStatus.Downloading) {
                            tileDownloads = tileDownloads + (
                                tileId to currentSnapshot.copy(
                                    downloadedBytes = downloadedBytes,
                                    actualBytes = contentLengthBytes,
                                )
                            )
                            recomputeUiState()
                        }
                    }
                }
                callbackExecutor.execute {
                    tileDownloads = tileDownloads + (
                        tileId to cachedSnapshot.copy(
                            estimatedBytes = estimatedBytes,
                        )
                    )
                    recomputeUiState()
                }
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Tile context download failed for $tileId", error)
                callbackExecutor.execute {
                    tileDownloads = tileDownloads + (
                        tileId to TileDownloadSnapshot(
                            status = TileDownloadStatus.Error,
                            estimatedBytes = estimatedBytes,
                            errorMessage = error.message ?: "Download failed",
                        )
                    )
                    recomputeUiState()
                }
            }
        }
    }

    fun loadRoute(
        uri: Uri,
        displayName: String?,
        rememberSelection: Boolean = true,
        fromRestore: Boolean = false,
    ) {
        routeLoadState = routeLoadState.beginLoading()
        recomputeUiState()

        routeLoadCoordinator.load(
            request = RouteLoadRequest(
                routeRef = uri,
                displayName = displayName,
                rememberSelection = rememberSelection,
                fromRestore = fromRestore,
            ),
            onOutcome = { outcome ->
                when (outcome) {
                    is RouteLoadOutcome.Success -> {
                        routeRuntimeState.applyRoute(outcome.loadedRoute.model)
                        routeLoadState = routeLoadState.loadSucceeded(outcome.loadedRoute.displayName)
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
        tileDownloadExecutor.shutdownNow()
        super.onCleared()
    }

    private fun handleLocation(location: Location) {
        routeRuntimeState.acceptLocation(
            location = location,
            sessionActive = sessionState.sessionActive,
            batterySaverEnabled = appPreferences.batterySaverEnabled,
        )
        routeLoadState = routeLoadState.clearIssue()
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
            rememberSelection = false,
            fromRestore = true,
        )
    }

    private fun clearRememberedRoute() {
        routeRepository.clearRememberedRoute()
        applySessionTransition(sessionState.stop())
        routeRuntimeState.clearRoute()
        routeLoadState = routeLoadState.clearRoute()
    }

    private fun updateRouteScale(scale: RouteScale) {
        if (appPreferences.routeScale == scale) {
            return
        }
        updatePreferences { copy(routeScale = scale) }
        recomputeUiState()
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
}
