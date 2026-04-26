package dev.ra.geepee

internal data class MapInfoFocus(
    val centerGeoPoint: GeoPoint,
    val windowWidthMeters: Double,
    val projectedBounds: Bounds? = null,
)

internal fun mapInfoFocusChanged(
    previous: MapInfoFocus?,
    current: MapInfoFocus,
): Boolean {
    val previousFocus = previous ?: return true
    if (mapInfoFocusWidthChanged(previousFocus, current)) {
        return true
    }
    val previousBounds = previousFocus.projectedBounds
    val currentBounds = current.projectedBounds
    if (previousBounds != null || currentBounds != null) {
        return previousBounds != currentBounds
    }
    val movementThresholdMeters = maxOf(25.0, current.windowWidthMeters * 0.2)
    return distanceBetweenGeoPointsMeters(previousFocus.centerGeoPoint, current.centerGeoPoint) > movementThresholdMeters
}

private fun mapInfoFocusWidthChanged(
    previous: MapInfoFocus,
    current: MapInfoFocus,
): Boolean {
    return kotlin.math.abs(previous.windowWidthMeters - current.windowWidthMeters) >
        maxOf(5.0, previous.windowWidthMeters * 0.05)
}
