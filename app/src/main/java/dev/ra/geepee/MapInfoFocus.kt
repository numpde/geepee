package dev.ra.geepee

internal data class MapInfoFocus(
    val centerGeoPoint: GeoPoint,
    val windowWidthMeters: Double,
    val projectedBounds: Bounds,
)

internal fun nearbyWayMapInfoFocusOrDefault(
    explicitFocus: MapInfoFocus?,
    routeModel: RouteModel,
    analysis: RouteAnalysis,
    config: TileContextConfig,
    defaultWindowWidthMeters: Double,
): MapInfoFocus {
    return explicitFocus ?: MapInfoFocus(
        centerGeoPoint = analysis.nearestGeoPoint,
        windowWidthMeters = defaultWindowWidthMeters,
        projectedBounds = nearbyWayFocusBounds(
            routeModel = routeModel,
            focusGeoPoint = analysis.nearestGeoPoint,
            focusWindowWidthMeters = defaultWindowWidthMeters,
            haloMeters = config.wayHaloMeters,
            continuationMeters = config.nearbyWayContinuationMeters,
        ) ?: routeModel.bounds,
    )
}

internal fun expandedNearbyWayMapInfoBounds(
    focus: MapInfoFocus,
    config: TileContextConfig,
): Bounds {
    return expandBounds(
        focus.projectedBounds,
        config.wayHaloMeters + config.nearbyWayContinuationMeters,
    )
}

internal fun mapInfoFocusChanged(
    previous: MapInfoFocus?,
    current: MapInfoFocus,
): Boolean {
    val previousFocus = previous ?: return true
    if (mapInfoFocusWidthChanged(previousFocus, current)) {
        return true
    }
    return previousFocus.projectedBounds != current.projectedBounds
}

internal fun shouldAcceptMapInfoFocusUpdate(
    previous: MapInfoFocus?,
    current: MapInfoFocus,
    defaultWindowWidthMeters: Double,
    matchedGeoPoint: GeoPoint,
): Boolean {
    if (previous == null && isDefaultMapInfoFocus(current, defaultWindowWidthMeters, matchedGeoPoint)) {
        return false
    }
    return mapInfoFocusChanged(previous, current)
}

internal fun isDefaultMapInfoFocus(
    focus: MapInfoFocus,
    defaultWindowWidthMeters: Double,
    matchedGeoPoint: GeoPoint,
): Boolean {
    val widthMatchesDefault = kotlin.math.abs(focus.windowWidthMeters - defaultWindowWidthMeters) <=
        maxOf(5.0, defaultWindowWidthMeters * 0.05)
    if (!widthMatchesDefault) {
        return false
    }
    return distanceBetweenGeoPointsMeters(focus.centerGeoPoint, matchedGeoPoint) <= 3.0
}

private fun mapInfoFocusWidthChanged(
    previous: MapInfoFocus,
    current: MapInfoFocus,
): Boolean {
    return kotlin.math.abs(previous.windowWidthMeters - current.windowWidthMeters) >
        maxOf(5.0, previous.windowWidthMeters * 0.05)
}
