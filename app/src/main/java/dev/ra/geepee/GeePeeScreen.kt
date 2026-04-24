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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun GeePeeApp(
    viewModel: GeePeeViewModel,
    routeScale: RouteScale,
    onCycleScale: () -> Unit,
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val appStateStore = remember(context) { AppStateStore(context.applicationContext) }
    val restoredState = remember(appStateStore) { appStateStore.load() }
    var orientationMode by rememberSaveable { mutableStateOf(restoredState.orientationMode) }
    var darkModeEnabled by rememberSaveable { mutableStateOf(restoredState.darkModeEnabled) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        viewModel.updateLocationPermissions(
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

    GeePeeTheme(darkThemeEnabled = darkModeEnabled) {
        GeePeeScreen(
            state = state,
            darkModeEnabled = darkModeEnabled,
            orientationMode = orientationMode,
            routeScale = routeScale,
            onCycleScale = onCycleScale,
            onToggleOrientationMode = {
                orientationMode = when (orientationMode) {
                    OrientationMode.CourseUp -> OrientationMode.NorthUp
                    OrientationMode.NorthUp -> OrientationMode.CourseUp
                }
                appStateStore.setOrientationMode(orientationMode)
            },
            onToggleDarkMode = {
                darkModeEnabled = !darkModeEnabled
                appStateStore.setDarkModeEnabled(darkModeEnabled)
            },
            onPickRoute = {
                routeLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
            },
            onStartMonitoring = {
                if (state.hasLocationPermission) {
                    viewModel.startMonitoring()
                } else {
                    viewModel.startMonitoring()
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
    darkModeEnabled: Boolean,
    orientationMode: OrientationMode,
    routeScale: RouteScale,
    onCycleScale: () -> Unit,
    onToggleOrientationMode: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onPickRoute: () -> Unit,
    onStartMonitoring: () -> Unit,
    onToggleBatterySaver: () -> Unit,
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
        val routeModel = state.routeModel
        val hasViewport = viewportWidthPx > 0f && viewportHeightPx > 0f
        fun fittedSetupViewport(): RouteViewport? {
            return routeModel?.takeIf { hasViewport }?.let {
                createRouteViewport(
                    contentBounds = it.bounds,
                    canvasWidth = viewportWidthPx.toDouble(),
                    canvasHeight = viewportHeightPx.toDouble(),
                )
            }
        }
        var setupViewport by remember(routeModel, viewportWidthPx, viewportHeightPx) {
            mutableStateOf(
                fittedSetupViewport(),
            )
        }
        val setupBoundsOverride = if (!movementMode && routeModel != null && hasViewport && setupViewport != null) {
            routeViewportBounds(
                viewport = setupViewport!!,
                canvasWidth = viewportWidthPx.toDouble(),
                canvasHeight = viewportHeightPx.toDouble(),
            )
        } else {
            null
        }
        val routeCanvasModifier = if (!movementMode && routeModel != null && hasViewport) {
            Modifier
                .fillMaxSize()
                .pointerInput(routeModel, viewportWidthPx, viewportHeightPx) {
                    detectTapGestures(
                        onDoubleTap = {
                            setupViewport = fittedSetupViewport()
                        },
                    )
                }
                .pointerInput(routeModel, viewportWidthPx, viewportHeightPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val currentViewport = setupViewport ?: fittedSetupViewport() ?: return@detectTransformGestures
                        setupViewport = transformRouteViewport(
                            viewport = currentViewport,
                            contentBounds = routeModel.bounds,
                            canvasWidth = viewportWidthPx.toDouble(),
                            canvasHeight = viewportHeightPx.toDouble(),
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
            orientationMode = orientationMode,
            routeScale = routeScale,
            boundsOverride = setupBoundsOverride,
            modifier = routeCanvasModifier,
        )

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
                darkModeEnabled = darkModeEnabled,
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
                    orientationMode = orientationMode,
                    onToggleOrientationMode = onToggleOrientationMode,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            ScaleBar(
                routeScale = routeScale,
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
                onPickRoute = onPickRoute,
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
