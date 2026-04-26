package dev.ra.geepee

internal data class MapInfoFocus(
    val centerGeoPoint: GeoPoint,
    val windowWidthMeters: Double,
    val projectedBounds: Bounds,
)

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

private fun mapInfoFocusWidthChanged(
    previous: MapInfoFocus,
    current: MapInfoFocus,
): Boolean {
    return kotlin.math.abs(previous.windowWidthMeters - current.windowWidthMeters) >
        maxOf(5.0, previous.windowWidthMeters * 0.05)
}
