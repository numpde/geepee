package dev.ra.geepee

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

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
            onToggleSetupOverviewMode = viewModel::toggleSetupOverviewMode,
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
            onRequestScreenPinning = {
                requestScreenPinning(context)
            },
            onRequestLocationRefresh = viewModel::requestImmediateLocationRefresh,
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
    onToggleSetupOverviewMode: () -> Unit,
    onStartMonitoring: () -> Unit,
    onToggleBatterySaver: () -> Unit,
    onDownloadTile: (DownloadTileId, Long) -> Unit,
    onRequestScreenPinning: () -> Unit,
    onRequestLocationRefresh: () -> Unit,
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
        val showTileOverview = !movementMode && state.setupOverviewMode == SetupOverviewMode.Tiles
        val overviewRouteModel = state.routeModel
        val overviewBounds = setupViewportState.boundsOverride
        val tileGridModel = if (showTileOverview && overviewRouteModel != null && overviewBounds != null) {
            remember(
                overviewRouteModel,
                overviewBounds,
                viewportWidthPx,
                viewportHeightPx,
                state.tileDownloads,
                state.tileContextConfig,
            ) {
                buildTileGridRenderModel(
                    routeModel = overviewRouteModel,
                    bounds = overviewBounds,
                    canvasWidth = viewportWidthPx,
                    canvasHeight = viewportHeightPx,
                    config = state.tileContextConfig,
                    tileSnapshots = state.tileDownloads,
                )
            }
        } else {
            null
        }
        val routeCanvasModifier = if (!movementMode && setupViewportState.isReady) {
            Modifier
                .fillMaxSize()
                .pointerInput(setupViewportState, tileGridModel, showTileOverview) {
                    detectTapGestures(
                        onTap = { point ->
                            if (showTileOverview) {
                                tileGridModel?.tileAt(ScreenPoint(point.x, point.y))?.let { tile ->
                                    onDownloadTile(tile.tileId, tile.estimatedBytes)
                                }
                            }
                        },
                        onDoubleTap = {
                            setupViewportState.reset()
                        },
                    )
                }
                .pointerInput(setupViewportState) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        setupViewportState.transform(
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
            routeScale = state.routeScale,
            boundsOverride = if (movementMode) null else setupViewportState.boundsOverride,
            modifier = routeCanvasModifier,
        )
        if (showTileOverview && tileGridModel != null) {
            TileGridCanvas(
                model = tileGridModel,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (movementMode) {
            MovementTopOverlay(
                state = state,
                onRequestLocationRefresh = onRequestLocationRefresh,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
            MovementMenu(
                routeName = state.routeName,
                darkModeEnabled = state.darkModeEnabled,
                batterySaverEnabled = state.batterySaverEnabled,
                onPickRoute = onPickRoute,
                onStartMonitoring = onStartMonitoring,
                onToggleDarkMode = onToggleDarkMode,
                onToggleBatterySaver = onToggleBatterySaver,
                onRequestScreenPinning = onRequestScreenPinning,
                onStopMonitoring = onStopMonitoring,
                sessionRunning = state.sessionRunning,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
            state.compass?.let { compass ->
                HeadingCompass(
                    compass = compass,
                    toneColor = toneColor,
                    orientationMode = state.orientationMode,
                    onToggleOrientationMode = onToggleOrientationMode,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            ScaleBar(
                routeScale = state.routeScale,
                viewportWidthPx = viewportWidthPx,
                onCycleScale = onCycleScale,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
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
                sessionRunning = state.sessionRunning,
                overviewMode = state.setupOverviewMode,
                onPickRoute = onPickRoute,
                onToggleOverviewMode = onToggleSetupOverviewMode,
                onStartMonitoring = onStartMonitoring,
                onStopMonitoring = onStopMonitoring,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}
