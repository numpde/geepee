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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
            onDownloadTiles = viewModel::downloadTiles,
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

private data class RouteCanvasTapContext(
    val previewTileUiState: PreviewTileUiState,
    val tileGridModel: TileGridRenderModel?,
    val movementMode: Boolean,
    val routeModel: RouteModel?,
    val analysis: RouteAnalysis?,
    val orientationMode: OrientationMode,
    val headingDegrees: Double?,
    val currentReferenceGeoPoint: GeoPoint?,
    val pois: List<RoutePoi>,
    val windowWidthMeters: Double,
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val boundsOverride: Bounds?,
    val tileResolutionPolicy: TileResolutionPolicy,
) {
    val showTileOverview: Boolean
        get() = !movementMode
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
    onDownloadTiles: (List<TileDownloadRequest>) -> Unit,
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
            val routeRotationDegrees = routeViewRotationDegrees(
                orientationMode = state.orientationMode,
                headingDegrees = state.compass?.headingDegrees,
            ).toDouble()
            rememberMovementViewportController(
                routeModel = state.routeModel,
                analysis = state.analysis,
                routeScale = state.routeScale,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                debugGpsEnabled = state.debugGpsEnabled,
                routeRotationDegrees = routeRotationDegrees,
                minimumWidthMetersOverride = 6.0,
            )
        } else {
            null
        }
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
        var routePoiUiState by remember(state.routeName, movementMode) {
            mutableStateOf(RoutePoiUiState())
        }
        var previewTileUiState by remember(state.routeName, movementMode) {
            mutableStateOf(PreviewTileUiState())
        }
        val resolvedPreviewTileUiState = remember(previewTileUiState, state.tileDownloads) {
            previewTileUiState.resolve(state.tileDownloads)
        }
        val selectedTileIds = resolvedPreviewTileUiState.selectedTileIds
        val deleteTilesLabel = resolvedPreviewTileUiState.deleteTilesActionLabel
        LaunchedEffect(movementViewState.effectiveMapInfoFocus) {
            movementViewState.effectiveMapInfoFocus?.let { focus ->
                delay(250)
                onUpdateLiveContextFocus(focus)
            }
        }
        val tileGridBounds = movementViewState.tileGridBounds
        val tileGridModel = if (tileGridRouteModel != null && tileGridBounds != null) {
            remember(
                tileGridRouteModel,
                tileGridBounds,
                viewportWidthPx,
                viewportHeightPx,
                state.tileDownloads,
                selectedTileIds,
                state.tileContextConfig,
            ) {
                buildTileGridRenderModel(
                    routeModel = tileGridRouteModel,
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
        val currentTapContext by rememberUpdatedState(
            RouteCanvasTapContext(
                previewTileUiState = resolvedPreviewTileUiState,
                tileGridModel = tileGridModel,
                movementMode = movementMode,
                routeModel = state.routeModel,
                analysis = state.analysis,
                orientationMode = state.orientationMode,
                headingDegrees = state.compass?.headingDegrees,
                currentReferenceGeoPoint = state.currentReferenceGeoPoint,
                pois = state.mapInfo.pois,
                windowWidthMeters = movementViewState.windowWidthMeters,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                boundsOverride = movementViewState.viewportFocus?.projectedBounds,
                tileResolutionPolicy = state.tileContextConfig.resolutionPolicy,
            ),
        )
        val currentMovementViewportController by rememberUpdatedState(movementViewportController)
        val currentOnDownloadTiles by rememberUpdatedState(onDownloadTiles)
        val routeCanvasModifier = if (activeViewportState.isReady) {
            Modifier
                .fillMaxSize()
                .pointerInput(activeViewportState, routeCanvasTapPolicy) {
                    detectRouteCanvasTapGestures(
                        policy = routeCanvasTapPolicy,
                        onTap = { point ->
                            val tapContext = currentTapContext
                            if (tapContext.showTileOverview) {
                                val tapTransition = tapContext.previewTileUiState.onTap(
                                    tile = tapContext.tileGridModel?.tileAt(ScreenPoint(point.x, point.y)),
                                )
                                previewTileUiState = tapTransition.state
                                tapTransition.downloadRequest?.let { request ->
                                    currentOnDownloadTiles(request.tileRequests)
                                }
                                tapTransition.zoomRequest?.let {
                                    activeViewportState.zoomInToNextDataTileResolution(
                                        policy = tapContext.tileResolutionPolicy,
                                    )
                                }
                            } else if (tapContext.movementMode) {
                                tapContext.routeModel?.let { routeModel ->
                                    routePoiUiState = routePoiUiState.onCanvasTap(
                                        routeModel = routeModel,
                                        analysis = tapContext.analysis,
                                        orientationMode = tapContext.orientationMode,
                                        headingDegrees = tapContext.headingDegrees,
                                        currentReferenceGeoPoint = tapContext.currentReferenceGeoPoint,
                                        pois = tapContext.pois,
                                        screenPoint = ScreenPoint(point.x, point.y),
                                        maxDistancePx = poiTapRadiusPx,
                                        windowWidthMeters = tapContext.windowWidthMeters,
                                        canvasWidth = tapContext.viewportWidthPx,
                                        canvasHeight = tapContext.viewportHeightPx,
                                        boundsOverride = tapContext.boundsOverride,
                                    )
                                }
                            }
                        },
                        onDoubleTap = {
                            routePoiUiState = routePoiUiState.clear()
                            if (currentTapContext.movementMode) {
                                currentMovementViewportController?.handleDoubleTap?.invoke()
                            } else {
                                activeViewportState.reset()
                            }
                        },
                        onLongPress = { point ->
                            val tapContext = currentTapContext
                            if (tapContext.showTileOverview) {
                                previewTileUiState = tapContext.previewTileUiState.onLongPress(
                                    tile = tapContext.tileGridModel?.tileAt(ScreenPoint(point.x, point.y)),
                                )
                            }
                        },
                    )
                }
                .pointerInput(activeViewportState) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        routePoiUiState = routePoiUiState.clearOnTransform(
                            pan = Offset(pan.x, pan.y),
                            zoom = zoom,
                        )
                        currentMovementViewportController?.handleTransform?.invoke(
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
                selectedPois = routePoiUiState.selectedPois,
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
                            previewTileUiState = resolvedPreviewTileUiState.requestDelete(
                                buildPlan = onBuildTileDeletePlan,
                            )
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
                    previewTileUiState = resolvedPreviewTileUiState.requestDelete(
                        buildPlan = onBuildTileDeletePlan,
                    )
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
        resolvedPreviewTileUiState.pendingDeletePlan?.let { plan ->
            TileDeleteDialog(
                plan = plan,
                onConfirm = {
                    resolvedPreviewTileUiState.confirmDelete()?.let { nextState ->
                        onExecuteTileDeletePlan(plan)
                        previewTileUiState = nextState
                    }
                },
                onDismiss = { previewTileUiState = resolvedPreviewTileUiState.dismissDelete() },
            )
        }
    }
}
