package dev.ra.geepee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

@Composable
internal fun rememberRouteViewportState(
    contentBounds: Bounds?,
    initialViewport: RouteViewport?,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    minimumWidthMetersOverride: Double? = null,
): RouteViewportState {
    return remember(contentBounds, viewportWidthPx, viewportHeightPx, minimumWidthMetersOverride) {
        RouteViewportState(
            contentBounds = contentBounds,
            initialViewport = initialViewport,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            minimumWidthMetersOverride = minimumWidthMetersOverride,
        )
    }
}

@Composable
internal fun rememberSetupViewportState(
    routeModel: RouteModel?,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): RouteViewportState {
    return rememberRouteViewportState(
        contentBounds = routeModel?.bounds,
        initialViewport = null,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        minimumWidthMetersOverride = null,
    )
}

internal data class MovementViewportController(
    val activeViewportState: RouteViewportState,
    val viewportFocus: MapInfoFocus?,
    val handleTransform: (ScreenPoint, ScreenPoint, Float) -> Unit,
    val handleDoubleTap: () -> Unit,
    val snapToNextScale: () -> RouteScale,
)

private enum class MovementCameraMode {
    FollowAnchor,
    Free,
}

@Composable
internal fun rememberMovementViewportController(
    routeModel: RouteModel?,
    analysis: RouteAnalysis?,
    routeScale: RouteScale,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    debugGpsEnabled: Boolean,
    routeRotationDegrees: Double,
    minimumWidthMetersOverride: Double = 6.0,
): MovementViewportController {
    val resetViewport = routeModel?.let { model ->
        initialMovementViewport(
            routeModel = model,
            analysis = analysis,
            routeScale = routeScale,
        )
    }
    var liveCameraMode by remember(routeModel) {
        mutableStateOf(MovementCameraMode.FollowAnchor)
    }
    val liveViewportState = rememberRouteViewportState(
        contentBounds = routeModel?.bounds,
        initialViewport = resetViewport,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        minimumWidthMetersOverride = minimumWidthMetersOverride,
    )
    val debugViewportState = rememberRouteViewportState(
        contentBounds = routeModel?.bounds,
        initialViewport = resetViewport,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        minimumWidthMetersOverride = minimumWidthMetersOverride,
    )
    LaunchedEffect(debugGpsEnabled, resetViewport) {
        if (debugGpsEnabled) {
            debugViewportState.setResetViewport(
                viewport = resetViewport,
                applyImmediately = true,
            )
        }
    }
    LaunchedEffect(debugGpsEnabled, routeModel, resetViewport) {
        if (!debugGpsEnabled && routeModel != null) {
            liveViewportState.setResetViewport(
                viewport = resetViewport,
                applyImmediately = liveViewportState.viewport == null,
            )
        }
    }
    val anchorPoint = resetViewport?.let { viewport ->
        ProjectedPoint(viewport.centerX, viewport.centerY)
    }
    val currentRouteRotationDegrees by rememberUpdatedState(routeRotationDegrees)
    LaunchedEffect(debugGpsEnabled, anchorPoint, liveCameraMode) {
        if (!debugGpsEnabled && liveCameraMode == MovementCameraMode.FollowAnchor) {
            anchorPoint?.let(liveViewportState::recenterOn)
        }
    }

    val activeViewportState = if (debugGpsEnabled) debugViewportState else liveViewportState
    val viewportFocus = if (routeModel != null) {
        val projectedBounds = activeViewportState.boundsOverride
        val viewport = activeViewportState.viewport
        if (projectedBounds != null && viewport != null) {
            MapInfoFocus(
                centerGeoPoint = unprojectPoint(
                    point = ProjectedPoint(viewport.centerX, viewport.centerY),
                    projection = routeModel.projection,
                ),
                windowWidthMeters = viewport.widthMeters,
                projectedBounds = projectedBounds,
            )
        } else {
            null
        }
    } else {
        null
    }

    return MovementViewportController(
        activeViewportState = activeViewportState,
        viewportFocus = viewportFocus,
        handleTransform = { centroid, pan, zoomChange ->
            if (!debugGpsEnabled) {
                liveCameraMode = MovementCameraMode.Free
            }
            activeViewportState.transform(
                centroid = centroid,
                pan = pan,
                zoomChange = zoomChange,
                rotationDegrees = currentRouteRotationDegrees,
            )
        },
        handleDoubleTap = {
            if (!debugGpsEnabled) {
                liveCameraMode = MovementCameraMode.FollowAnchor
            }
            anchorPoint?.let(activeViewportState::recenterOn) ?: activeViewportState.reset()
        },
        snapToNextScale = {
            val nextScale = nextRouteScaleFrom(activeViewportState.viewport?.widthMeters ?: routeScale.windowWidthMeters)
            activeViewportState.setWidthMeters(nextScale.windowWidthMeters)
            nextScale
        },
    )
}

internal class RouteViewportState(
    private val contentBounds: Bounds?,
    private val initialViewport: RouteViewport?,
    private val viewportWidthPx: Float,
    private val viewportHeightPx: Float,
    private val minimumWidthMetersOverride: Double?,
) {
    private var resetViewport: RouteViewport? = initialViewport ?: fittedViewport()

    var viewport by mutableStateOf(resetViewport)
        private set

    val isReady: Boolean
        get() = contentBounds != null && viewportWidthPx > 0f && viewportHeightPx > 0f

    val boundsOverride: Bounds?
        get() = if (isReady) {
            viewport?.let {
                routeViewportBounds(
                    viewport = it,
                    canvasWidth = viewportWidthPx.toDouble(),
                    canvasHeight = viewportHeightPx.toDouble(),
                )
            }
        } else {
            null
        }

    fun reset() {
        viewport = resetViewport ?: fittedViewport()
    }

    fun transform(
        centroid: ScreenPoint,
        pan: ScreenPoint,
        zoomChange: Float,
        rotationDegrees: Double = 0.0,
    ) {
        val currentContentBounds = contentBounds ?: return
        val currentViewport = viewport ?: fittedViewport() ?: return
        viewport = transformRouteViewport(
            viewport = currentViewport,
            contentBounds = currentContentBounds,
            canvasWidth = viewportWidthPx.toDouble(),
            canvasHeight = viewportHeightPx.toDouble(),
            centroid = centroid,
            pan = pan,
            zoomChange = zoomChange,
            rotationDegrees = rotationDegrees,
            minimumWidthMeters = effectiveMinimumWidthMeters(currentContentBounds),
        )
    }

    fun setResetViewport(
        viewport: RouteViewport?,
        applyImmediately: Boolean,
    ) {
        resetViewport = viewport ?: fittedViewport()
        if (applyImmediately) {
            this.viewport = resetViewport
        }
    }

    fun setWidthMeters(widthMeters: Double) {
        val currentContentBounds = contentBounds ?: return
        val currentViewport = viewport ?: fittedViewport() ?: return
        viewport = clampRouteViewportForState(
            viewport = currentViewport.copy(widthMeters = widthMeters),
            contentBounds = currentContentBounds,
        )
    }

    fun recenterOn(
        point: ProjectedPoint,
        preserveWidthMeters: Boolean = true,
    ) {
        val currentContentBounds = contentBounds ?: return
        val currentViewport = viewport ?: fittedViewport() ?: return
        viewport = clampRouteViewportForState(
            viewport = RouteViewport(
                centerX = point.x,
                centerY = point.y,
                widthMeters = if (preserveWidthMeters) {
                    currentViewport.widthMeters
                } else {
                    resetViewport?.widthMeters ?: currentViewport.widthMeters
                },
            ),
            contentBounds = currentContentBounds,
        )
    }

    private fun fittedViewport(): RouteViewport? {
        return contentBounds?.takeIf { viewportWidthPx > 0f && viewportHeightPx > 0f }?.let {
            createRouteViewport(
                contentBounds = it,
                canvasWidth = viewportWidthPx.toDouble(),
                canvasHeight = viewportHeightPx.toDouble(),
            )
        }
    }

    private fun clampRouteViewportForState(
        viewport: RouteViewport,
        contentBounds: Bounds,
    ): RouteViewport {
        val effectiveMinWidthMeters = effectiveMinimumWidthMeters(contentBounds)
        val maxWidthMeters = fittedViewportWidthMeters(
            contentBounds = contentBounds,
            canvasWidth = viewportWidthPx.toDouble(),
            canvasHeight = viewportHeightPx.toDouble(),
        )
        val clampedViewport = viewport.copy(
            widthMeters = clamp(
                value = viewport.widthMeters,
                minValue = effectiveMinWidthMeters,
                maxValue = maxWidthMeters,
            ),
        )
        return clampRouteViewport(
            viewport = clampedViewport,
            contentBounds = contentBounds,
            canvasWidth = viewportWidthPx.toDouble(),
            canvasHeight = viewportHeightPx.toDouble(),
            minimumWidthMeters = effectiveMinWidthMeters,
        )
    }

    private fun effectiveMinimumWidthMeters(contentBounds: Bounds): Double {
        return minimumWidthMetersOverride ?: minimumViewportWidthMeters(contentBounds)
    }
}

private fun initialMovementViewport(
    routeModel: RouteModel,
    analysis: RouteAnalysis?,
    routeScale: RouteScale,
): RouteViewport {
    val anchorPoint = currentMovementAnchorPoint(routeModel, analysis)
    return RouteViewport(
        centerX = anchorPoint.x,
        centerY = anchorPoint.y,
        widthMeters = routeScale.windowWidthMeters,
    )
}

private fun currentMovementAnchorPoint(
    routeModel: RouteModel,
    analysis: RouteAnalysis?,
): ProjectedPoint {
    return analysis?.nearestPoint ?: routeModel.bounds.let { bounds ->
        ProjectedPoint(
            x = (bounds.minX + bounds.maxX) / 2.0,
            y = (bounds.minY + bounds.maxY) / 2.0,
        )
    }
}
