package dev.ra.geepee

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
