package dev.ra.geepee

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal data class MovementViewState(
    val viewportFocus: MapInfoFocus?,
    val fallbackTileGridBounds: Bounds?,
    val fallbackWindowWidthMeters: Double,
    val fallbackReferencePoint: GeoPoint?,
    val mapInfoEnabled: Boolean,
) {
    val tileGridBounds: Bounds?
        get() = viewportFocus?.projectedBounds ?: fallbackTileGridBounds

    val windowWidthMeters: Double
        get() = viewportFocus?.windowWidthMeters ?: fallbackWindowWidthMeters

    val openInPoint: GeoPoint?
        get() = viewportFocus?.centerGeoPoint ?: fallbackReferencePoint

    val effectiveMapInfoFocus: MapInfoFocus?
        get() = if (mapInfoEnabled) viewportFocus else null
}

internal data class PreviewTileSelectionState(
    val selectedTileIds: Set<DownloadTileId> = emptySet(),
) {
    val selectionModeActive: Boolean
        get() = selectedTileIds.isNotEmpty()

    fun retainCached(
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileSelectionState {
        val retainedTileIds = selectedTileIds.filterTo(linkedSetOf()) { tileId ->
            tileSnapshots[tileId]?.status == TileDownloadStatus.Cached
        }
        return if (retainedTileIds == selectedTileIds) {
            this
        } else {
            copy(selectedTileIds = retainedTileIds)
        }
    }

    fun onTap(tile: TileGridDisplayTile?): PreviewTileTapResult {
        if (tile == null) {
            return PreviewTileTapResult(this)
        }
        return if (selectionModeActive) {
            PreviewTileTapResult(toggleCachedTile(tile))
        } else if (tile.isCached) {
            PreviewTileTapResult(this)
        } else {
            PreviewTileTapResult(
                selectionState = this,
                downloadRequest = PreviewTileDownloadRequest(
                    tileId = tile.tileId,
                    estimatedBytes = tile.estimatedBytes,
                ),
            )
        }
    }

    fun onLongPress(tile: TileGridDisplayTile?): PreviewTileSelectionState {
        return toggleCachedTile(tile)
    }

    private fun toggleCachedTile(tile: TileGridDisplayTile?): PreviewTileSelectionState {
        if (tile?.isCached != true) {
            return this
        }
        val nextSelectedTileIds = selectedTileIds.toMutableSet().also { tileIds ->
            if (!tileIds.add(tile.tileId)) {
                tileIds.remove(tile.tileId)
            }
        }.toSet()
        return copy(selectedTileIds = nextSelectedTileIds)
    }
}

internal data class PreviewTileTapResult(
    val selectionState: PreviewTileSelectionState,
    val downloadRequest: PreviewTileDownloadRequest? = null,
)

internal data class PreviewTileDownloadRequest(
    val tileId: DownloadTileId,
    val estimatedBytes: Long,
)

private val TileGridDisplayTile.isCached: Boolean
    get() = snapshot?.status == TileDownloadStatus.Cached

@Composable
internal fun GeePeeApp(
    viewModel: GeePeeViewModel,
) {
    val state = viewModel.uiState
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        viewModel.onPermissionRequestResult(
            coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true,
        )
    }

    val routeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        viewModel.loadRoute(uri, queryDisplayName(context, uri))
    }

    GeePeeTheme(darkThemeEnabled = state.darkModeEnabled) {
        GeePeeScreen(
            state = state,
            onCycleScale = viewModel::cycleRouteScale,
            onToggleOrientationMode = viewModel::toggleOrientationMode,
            onToggleDarkMode = viewModel::toggleDarkMode,
            onPickRoute = {
                routeLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
            },
            onReverseRoute = viewModel::reverseRoute,
            onStartMonitoring = {
                if (state.hasLocationPermission) {
                    viewModel.startMonitoring()
                } else {
                    viewModel.requestSessionStart()
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                }
            },
            onToggleBatterySaver = {
                viewModel.setBatterySaverEnabled(!state.batterySaverEnabled)
            },
            onDownloadTile = viewModel::downloadTile,
            onBuildTileDeletePlan = viewModel::buildTileDeletePlan,
            onExecuteTileDeletePlan = viewModel::executeTileDeletePlan,
            onRequestScreenPinning = {
                requestScreenPinning(context)
            },
            onRequestLocationRefresh = viewModel::requestImmediateLocationRefresh,
            onToggleDebugGps = viewModel::toggleDebugGps,
            onSetDebugGpsLocation = viewModel::setDebugGpsLocation,
            onUpdateLiveContextFocus = viewModel::updateLiveContextFocus,
            onSetRouteScale = viewModel::setRouteScale,
            onOpenInExternalMap = { point, label, windowWidthMeters ->
                openLocationInExternalMap(
                    context = context,
                    point = point,
                    label = label,
                    windowWidthMeters = windowWidthMeters,
                )
            },
            onOpenInOsmBrowser = { point, windowWidthMeters ->
                openLocationInOsmBrowser(
                    context = context,
                    point = point,
                    windowWidthMeters = windowWidthMeters,
                )
            },
            onStopMonitoring = viewModel::stopMonitoring,
        )
    }
}

@Composable
private fun GeePeeScreen(
    state: GeePeeUiState,
    onCycleScale: () -> Unit,
    onToggleOrientationMode: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onPickRoute: () -> Unit,
    onReverseRoute: () -> Unit,
    onStartMonitoring: () -> Unit,
    onToggleBatterySaver: () -> Unit,
    onDownloadTile: (DownloadTileId, Long) -> Unit,
    onBuildTileDeletePlan: (Set<DownloadTileId>) -> TileDeletePlan,
    onExecuteTileDeletePlan: (TileDeletePlan) -> Unit,
    onRequestScreenPinning: () -> Unit,
    onRequestLocationRefresh: () -> Unit,
    onToggleDebugGps: () -> Unit,
    onSetDebugGpsLocation: (MapInfoFocus) -> Unit,
    onUpdateLiveContextFocus: (MapInfoFocus) -> Unit,
    onSetRouteScale: (RouteScale) -> Unit,
    onOpenInExternalMap: (GeoPoint, String, Double?) -> Unit,
    onOpenInOsmBrowser: (GeoPoint, Double?) -> Unit,
    onStopMonitoring: () -> Unit,
) {
    val colors = geePeeColors()
    val toneColor = toneColor(state.status.tone)
    val movementMode = state.sessionRunning && state.routeModel != null

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val setupViewportState = rememberSetupViewportState(
            routeModel = state.routeModel,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
        val movementViewportController = if (movementMode) {
            rememberMovementViewportController(
                routeModel = state.routeModel,
                analysis = state.analysis,
                routeScale = state.routeScale,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                debugGpsEnabled = state.debugGpsEnabled,
                minimumWidthMetersOverride = 6.0,
            )
        } else {
            null
        }
        val showTileOverview = !movementMode
        val tileGridRouteModel = state.routeModel
        val movementViewState = remember(
            movementMode,
            movementViewportController?.viewportFocus,
            setupViewportState.boundsOverride,
            state.routeScale,
            state.currentReferenceGeoPoint,
            state.analysis,
        ) {
            buildMovementViewState(
                movementMode = movementMode,
                viewportFocus = movementViewportController?.viewportFocus,
                setupBounds = setupViewportState.boundsOverride,
                routeScale = state.routeScale,
                currentReferenceGeoPoint = state.currentReferenceGeoPoint,
                hasAnalysis = state.analysis != null,
            )
        }
        val poiTapRadiusPx = with(density) { 28.dp.toPx() }
        val routeCanvasTapPolicy = remember(density) {
            RouteCanvasTapPolicy(
                maxDoubleTapDistancePx = with(density) { 32.dp.toPx() },
            )
        }
        var selectedPois by remember(state.routeName, movementMode) {
            mutableStateOf(emptyList<RoutePoiSelectionInfo>())
        }
        var tileSelectionState by remember(state.routeName, movementMode) {
            mutableStateOf(PreviewTileSelectionState())
        }
        LaunchedEffect(state.tileDownloads, tileSelectionState) {
            val retainedSelectionState = tileSelectionState.retainCached(state.tileDownloads)
            if (retainedSelectionState != tileSelectionState) {
                tileSelectionState = retainedSelectionState
            }
        }
        val selectedTileIds = tileSelectionState.selectedTileIds
        val deleteTilesLabel = deleteTilesActionLabel(selectedTileIds)
        var deleteTilesPlan by remember { mutableStateOf<TileDeletePlan?>(null) }
        LaunchedEffect(movementViewState.effectiveMapInfoFocus) {
            movementViewState.effectiveMapInfoFocus?.let { focus ->
                delay(250)
                onUpdateLiveContextFocus(focus)
            }
        }
        val routeTileMetricsById = if (tileGridRouteModel != null) {
            remember(tileGridRouteModel, state.tileContextConfig) {
                buildRouteTileMetricsIndex(
                    routeModel = tileGridRouteModel,
                    config = state.tileContextConfig,
                )
            }
        } else {
            null
        }
        val tileGridBounds = movementViewState.tileGridBounds
        val tileGridModel = if (tileGridRouteModel != null && tileGridBounds != null) {
            remember(
                tileGridRouteModel,
                routeTileMetricsById,
                tileGridBounds,
                viewportWidthPx,
                viewportHeightPx,
                state.tileDownloads,
                selectedTileIds,
                state.tileContextConfig,
            ) {
                buildTileGridRenderModel(
                    routeModel = tileGridRouteModel,
                    routeTileMetricsById = routeTileMetricsById ?: emptyMap(),
                    bounds = tileGridBounds,
                    canvasWidth = viewportWidthPx,
                    canvasHeight = viewportHeightPx,
                    config = state.tileContextConfig,
                    tileSnapshots = state.tileDownloads,
                    selectedTileIds = selectedTileIds,
                )
            }
        } else {
            null
        }
        val visibleTileGridModel = if (movementMode) {
            tileGridModel?.fullyVisibleWithin(
                width = viewportWidthPx,
                height = viewportHeightPx,
            )
        } else {
            tileGridModel
        }
        val activeViewportState = when {
            !movementMode -> setupViewportState
            else -> movementViewportController!!.activeViewportState
        }
        val routeCanvasModifier = if (activeViewportState.isReady) {
            Modifier
                .fillMaxSize()
                .pointerInput(activeViewportState, tileGridModel, showTileOverview, routeCanvasTapPolicy) {
                    detectRouteCanvasTapGestures(
                        policy = routeCanvasTapPolicy,
                        onTap = { point ->
                            if (showTileOverview) {
                                val tapResult = tileSelectionState.onTap(
                                    tileGridModel?.tileAt(ScreenPoint(point.x, point.y)),
                                )
                                tileSelectionState = tapResult.selectionState
                                tapResult.downloadRequest?.let { request ->
                                    onDownloadTile(request.tileId, request.estimatedBytes)
                                }
                            } else if (movementMode) {
                                selectedPois = tappedPoiSelections(
                                    state = state,
                                    screenPoint = ScreenPoint(point.x, point.y),
                                    maxDistancePx = poiTapRadiusPx,
                                    windowWidthMeters = movementViewState.windowWidthMeters,
                                    canvasWidth = viewportWidthPx,
                                    canvasHeight = viewportHeightPx,
                                    boundsOverride = movementViewState.viewportFocus?.projectedBounds,
                                )
                            }
                        },
                        onDoubleTap = {
                            selectedPois = emptyList()
                            if (movementMode) {
                                movementViewportController?.handleDoubleTap?.invoke()
                            } else {
                                activeViewportState.reset()
                            }
                        },
                        onLongPress = { point ->
                            if (showTileOverview) {
                                tileSelectionState = tileSelectionState.onLongPress(
                                    tileGridModel?.tileAt(ScreenPoint(point.x, point.y)),
                                )
                            }
                        },
                    )
                }
                .pointerInput(activeViewportState) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (selectedPois.isNotEmpty() && (pan.x != 0f || pan.y != 0f || zoom != 1f)) {
                            selectedPois = emptyList()
                        }
                        movementViewportController?.handleTransform?.invoke(
                            ScreenPoint(centroid.x, centroid.y),
                            ScreenPoint(pan.x, pan.y),
                            zoom,
                        ) ?: activeViewportState.transform(
                            centroid = ScreenPoint(centroid.x, centroid.y),
                            pan = ScreenPoint(pan.x, pan.y),
                            zoomChange = zoom,
                        )
                    }
                }
        } else {
            Modifier.fillMaxSize()
        }

        RouteCanvas(
            state = state,
            toneColor = toneColor,
            orientationMode = state.orientationMode,
            windowWidthMeters = movementViewState.windowWidthMeters,
            boundsOverride = movementViewState.tileGridBounds,
            modifier = routeCanvasModifier,
        )
        if (visibleTileGridModel != null) {
            TileGridCanvas(
                model = visibleTileGridModel,
                visualStyle = if (movementMode) {
                    TileGridVisualStyle.LiveOverlay
                } else {
                    TileGridVisualStyle.Preview
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (movementMode) {
            val movementMenuState = MovementMenuState(
                state.routeName,
                state.darkModeEnabled,
                state.batterySaverEnabled,
                state.debugGpsEnabled,
                movementViewState.openInPoint != null,
                state.hasCachedTiles,
                state.sessionRunning,
            )
            MovementTopOverlay(
                state = state,
                selectedPois = selectedPois,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
            if (state.debugGpsEnabled) {
                DebugGpsCrosshair(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            MovementBottomChrome(
                primaryControls = {
                    MovementBottomControls(
                        menuState = movementMenuState,
                        showSetDebugGpsHere = state.debugGpsEnabled,
                        onPickRoute = onPickRoute,
                        onRequestLocationRefresh = onRequestLocationRefresh,
                        onStartMonitoring = onStartMonitoring,
                        onToggleDarkMode = onToggleDarkMode,
                        onToggleBatterySaver = onToggleBatterySaver,
                        onToggleDebugGps = onToggleDebugGps,
                        onDeleteTiles = {
                            deleteTilesPlan = onBuildTileDeletePlan(selectedTileIds)
                        },
                        deleteTilesLabel = deleteTilesLabel,
                        onRequestScreenPinning = onRequestScreenPinning,
                        onStopMonitoring = onStopMonitoring,
                        onSetDebugGpsHere = {
                            movementViewState.viewportFocus?.let(onSetDebugGpsLocation)
                        },
                        onOpenInExternalMap = {
                            movementViewState.openInPoint?.let { point ->
                                onOpenInExternalMap(
                                    point,
                                    state.routeName ?: "GeePee",
                                    movementViewState.windowWidthMeters,
                                )
                            }
                        },
                        onOpenInOsmBrowser = {
                            movementViewState.openInPoint?.let { point ->
                                onOpenInOsmBrowser(
                                    point,
                                    movementViewState.windowWidthMeters,
                                )
                            }
                        },
                    )
                },
                leadingUtility = state.compass?.let { compass ->
                    {
                        HeadingCompass(
                            compass = compass,
                            toneColor = toneColor,
                            orientationMode = state.orientationMode,
                            onToggleOrientationMode = onToggleOrientationMode,
                        )
                    }
                },
                trailingUtility = {
                    ScaleBar(
                        routeScale = state.routeScale,
                        windowWidthMeters = movementViewState.windowWidthMeters,
                        viewportWidthPx = viewportWidthPx,
                        onCycleScale = {
                            if (movementMode) {
                                val nextScale = movementViewportController!!.snapToNextScale()
                                onSetRouteScale(nextScale)
                            } else {
                                onCycleScale()
                            }
                        },
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        } else {
            SetupTopOverlay(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
            SetupActions(
                hasRoute = state.routeModel != null,
                hasCachedTiles = state.hasCachedTiles,
                sessionRunning = state.sessionRunning,
                onPickRoute = onPickRoute,
                onReverseRoute = onReverseRoute,
                onDeleteTiles = {
                    deleteTilesPlan = onBuildTileDeletePlan(selectedTileIds)
                },
                deleteTilesLabel = deleteTilesLabel,
                onStartMonitoring = onStartMonitoring,
                onStopMonitoring = onStopMonitoring,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        deleteTilesPlan?.let { plan ->
            DeleteTilesDialog(
                plan = plan,
                onConfirm = {
                    deleteTilesPlan = null
                    onExecuteTileDeletePlan(plan)
                    tileSelectionState = PreviewTileSelectionState()
                },
                onDismiss = { deleteTilesPlan = null },
            )
        }
    }
}

internal fun buildMovementViewState(
    movementMode: Boolean,
    viewportFocus: MapInfoFocus?,
    setupBounds: Bounds?,
    routeScale: RouteScale,
    currentReferenceGeoPoint: GeoPoint?,
    hasAnalysis: Boolean,
): MovementViewState {
    return if (movementMode) {
        MovementViewState(
            viewportFocus = viewportFocus,
            fallbackTileGridBounds = null,
            fallbackWindowWidthMeters = routeScale.windowWidthMeters,
            fallbackReferencePoint = currentReferenceGeoPoint,
            mapInfoEnabled = hasAnalysis,
        )
    } else {
        MovementViewState(
            viewportFocus = null,
            fallbackTileGridBounds = setupBounds,
            fallbackWindowWidthMeters = routeScale.windowWidthMeters,
            fallbackReferencePoint = currentReferenceGeoPoint,
            mapInfoEnabled = false,
        )
    }
}

private fun tappedPoiSelections(
    state: GeePeeUiState,
    screenPoint: ScreenPoint,
    maxDistancePx: Float,
    windowWidthMeters: Double,
    canvasWidth: Float,
    canvasHeight: Float,
    boundsOverride: Bounds?,
): List<RoutePoiSelectionInfo> {
    val routeModel = state.routeModel ?: return emptyList()
    val routeRotationDegrees = if (state.orientationMode == OrientationMode.CourseUp) {
        -(state.compass?.headingDegrees?.toFloat() ?: 0f)
    } else {
        0f
    }
    val poiMarkers = buildRouteRenderModel(
        routeModel = routeModel,
        analysis = state.analysis,
        matchHypotheses = emptyList(),
        historyPoints = emptyList(),
        pois = state.mapInfo.pois,
        nearbyWays = emptyList(),
        localWindowWidthMeters = windowWidthMeters,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        lookAheadFraction = 0.0,
        rotationDegrees = routeRotationDegrees,
        includeGradientPolylines = false,
        boundsOverride = boundsOverride,
    ).poiMarkers
    val selectedMarkers = routePoiMarkersNearScreenPoint(
        markers = poiMarkers,
        tap = screenPoint,
        maxDistancePx = maxDistancePx,
    )
    if (selectedMarkers.isEmpty()) {
        return emptyList()
    }
    val origin = state.currentReferenceGeoPoint
    return selectedMarkers
        .distinctBy(RoutePoiScreenMarker::featureId)
        .map { marker ->
            RoutePoiSelectionInfo(
                kind = marker.kind,
                title = routePoiSelectionTitle(marker),
                distanceMeters = origin?.let { distanceBetweenGeoPointsMeters(it, marker.geoPoint) },
            )
        }
        .sortedWith(
            compareBy<RoutePoiSelectionInfo> { it.distanceMeters ?: Double.POSITIVE_INFINITY }
                .thenBy(RoutePoiSelectionInfo::title),
        )
}

internal fun deleteTilesActionLabel(selectedTileIds: Set<DownloadTileId>): String {
    return if (selectedTileIds.isNotEmpty()) {
        "Delete selected tiles"
    } else {
        "Delete unused tiles"
    }
}

internal data class DeleteTilesDialogCopy(
    val title: String,
    val message: String,
)

internal fun deleteTilesDialogCopy(plan: TileDeletePlan): DeleteTilesDialogCopy {
    val actionText = if (plan.tileCount == 0) {
        "Nothing would be deleted right now."
    } else {
        "This will delete ${plan.tileCount} downloaded tiles and free ${formatStorageMegabytes(plan.freedBytes)}."
    }
    return when (plan.mode) {
        TileDeleteMode.Selected -> DeleteTilesDialogCopy(
            title = "Delete selected tiles?",
            message = buildString {
                append("This will delete exactly the selected downloaded tiles, even if they are on the current route or were used recently.")
                append("\n\n")
                append("Derived map-info cache for those tiles will also be removed.")
                append("\n\n")
                append(actionText)
            },
        )
        TileDeleteMode.Unused -> DeleteTilesDialogCopy(
            title = "Delete unused tiles?",
            message = buildString {
                append("This removes downloaded tiles that are not needed for the current route or current view, are not being downloaded, and were not used recently.")
                append("\n\n")
                append("Derived map-info cache for those tiles will also be removed.")
                append("\n\n")
                append(actionText)
            },
        )
    }
}

@Composable
private fun DeleteTilesDialog(
    plan: TileDeletePlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = remember(plan) { deleteTilesDialogCopy(plan) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = copy.title)
        },
        text = {
            Text(text = copy.message)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = plan.tileCount > 0,
            ) {
                Text(text = "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        },
    )
}
