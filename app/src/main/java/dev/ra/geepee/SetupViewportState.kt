package dev.ra.geepee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun rememberSetupViewportState(
    routeModel: RouteModel?,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): SetupViewportState {
    return remember(routeModel, viewportWidthPx, viewportHeightPx) {
        SetupViewportState(
            routeModel = routeModel,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
    }
}

internal class SetupViewportState(
    private val routeModel: RouteModel?,
    private val viewportWidthPx: Float,
    private val viewportHeightPx: Float,
) {
    var viewport by mutableStateOf(fittedViewport())
        private set

    val isReady: Boolean
        get() = routeModel != null && viewportWidthPx > 0f && viewportHeightPx > 0f

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
        viewport = fittedViewport()
    }

    fun transform(
        centroid: ScreenPoint,
        pan: ScreenPoint,
        zoomChange: Float,
    ) {
        val model = routeModel ?: return
        val currentViewport = viewport ?: fittedViewport() ?: return
        viewport = transformRouteViewport(
            viewport = currentViewport,
            contentBounds = model.bounds,
            canvasWidth = viewportWidthPx.toDouble(),
            canvasHeight = viewportHeightPx.toDouble(),
            centroid = centroid,
            pan = pan,
            zoomChange = zoomChange,
        )
    }

    private fun fittedViewport(): RouteViewport? {
        return routeModel?.takeIf { viewportWidthPx > 0f && viewportHeightPx > 0f }?.let {
            createRouteViewport(
                contentBounds = it.bounds,
                canvasWidth = viewportWidthPx.toDouble(),
                canvasHeight = viewportHeightPx.toDouble(),
            )
        }
    }
}
