package dev.ra.geepee

internal data class MapInfoFocus(
    val centerGeoPoint: GeoPoint,
    val windowWidthMeters: Double,
    val projectedBounds: Bounds? = null,
)
