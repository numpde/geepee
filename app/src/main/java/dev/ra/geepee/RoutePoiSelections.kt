package dev.ra.geepee

import androidx.compose.ui.geometry.Offset

internal data class RoutePoiUiState(
    val selectedPois: List<RoutePoiSelectionInfo> = emptyList(),
) {
    fun clear(): RoutePoiUiState = if (selectedPois.isEmpty()) this else RoutePoiUiState()

    fun onCanvasTap(
        routeModel: RouteModel,
        analysis: RouteAnalysis?,
        orientationMode: OrientationMode,
        headingDegrees: Double?,
        currentReferenceGeoPoint: GeoPoint?,
        pois: List<RoutePoi>,
        screenPoint: ScreenPoint,
        maxDistancePx: Float,
        windowWidthMeters: Double,
        canvasWidth: Float,
        canvasHeight: Float,
        boundsOverride: Bounds?,
    ): RoutePoiUiState {
        return copy(
            selectedPois = selectRoutePoiSelections(
                routeModel = routeModel,
                analysis = analysis,
                orientationMode = orientationMode,
                headingDegrees = headingDegrees,
                currentReferenceGeoPoint = currentReferenceGeoPoint,
                pois = pois,
                screenPoint = screenPoint,
                maxDistancePx = maxDistancePx,
                windowWidthMeters = windowWidthMeters,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                boundsOverride = boundsOverride,
            ),
        )
    }

    fun clearOnTransform(
        pan: Offset,
        zoom: Float,
    ): RoutePoiUiState {
        return if (selectedPois.isNotEmpty() && (pan.x != 0f || pan.y != 0f || zoom != 1f)) {
            clear()
        } else {
            this
        }
    }
}

internal data class RoutePoiSelectionInfo(
    val kind: RoutePoiKind,
    val title: String,
    val distanceMeters: Double?,
)

internal fun selectRoutePoiSelections(
    routeModel: RouteModel,
    analysis: RouteAnalysis?,
    orientationMode: OrientationMode,
    headingDegrees: Double?,
    currentReferenceGeoPoint: GeoPoint?,
    pois: List<RoutePoi>,
    screenPoint: ScreenPoint,
    maxDistancePx: Float,
    windowWidthMeters: Double,
    canvasWidth: Float,
    canvasHeight: Float,
    boundsOverride: Bounds?,
): List<RoutePoiSelectionInfo> {
    val poiMarkers = buildRouteRenderModel(
        routeModel = routeModel,
        analysis = analysis,
        matchHypotheses = emptyList(),
        historyPoints = emptyList(),
        pois = pois,
        nearbyWays = emptyList(),
        localWindowWidthMeters = windowWidthMeters,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        lookAheadFraction = 0.0,
        rotationDegrees = routeViewRotationDegrees(
            orientationMode = orientationMode,
            headingDegrees = headingDegrees,
        ),
        includeGradientPolylines = false,
        boundsOverride = boundsOverride,
    ).poiMarkers
    val selectedMarkers = routePoiMarkersNearScreenPoint(
        markers = poiMarkers,
        tap = screenPoint,
        maxDistancePx = maxDistancePx,
    )
    return routePoiSelectionsFromMarkers(
        markers = selectedMarkers,
        origin = currentReferenceGeoPoint,
    )
}

internal fun routePoiSelectionsFromMarkers(
    markers: List<RoutePoiScreenMarker>,
    origin: GeoPoint?,
): List<RoutePoiSelectionInfo> {
    if (markers.isEmpty()) {
        return emptyList()
    }
    return markers
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

internal fun routePoiSelectionTitle(
    marker: RoutePoiScreenMarker,
): String {
    return marker.name ?: when (marker.kind) {
        RoutePoiKind.DrinkingWater -> "Drinking water"
        RoutePoiKind.Toilets -> "Toilets"
        RoutePoiKind.Shelter -> "Shelter"
        RoutePoiKind.PicnicSite -> "Picnic site"
        RoutePoiKind.BicycleRepairStation -> "Bicycle repair station"
        RoutePoiKind.BicycleShop -> "Bicycle shop"
    }
}
