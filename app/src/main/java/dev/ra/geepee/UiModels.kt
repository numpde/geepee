package dev.ra.geepee

import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.ln

internal enum class RouteTone {
    Idle,
    Ready,
    OnRoute,
    Drifting,
    OffRoute,
    Warning,
}

internal data class RouteStatus(
    val tone: RouteTone,
    val badge: String,
    val headline: String,
    val detail: String,
)

internal data class CompassState(
    val routeBearingDegrees: Double,
    val headingDegrees: Double?,
)

internal data class RouteMapInfoUiState(
    val pois: List<RoutePoi> = emptyList(),
    val nearbyWays: List<RouteNearbyWaySnippet> = emptyList(),
    val availabilityText: String? = null,
)

internal data class GeePeeUiState(
    val routeName: String? = null,
    val routeModel: RouteModel? = null,
    val analysis: RouteAnalysis? = null,
    val currentReferenceGeoPoint: GeoPoint? = null,
    val routeMatchHypotheses: List<RouteMatchDisplayHypothesis> = emptyList(),
    val locationHistoryPoints: List<ProjectedPoint> = emptyList(),
    val compass: CompassState? = null,
    val lastFixTimestampMillis: Long? = null,
    val darkModeEnabled: Boolean = true,
    val orientationMode: OrientationMode = OrientationMode.CourseUp,
    val routeScale: RouteScale = RouteScale.TwoHundred,
    val tileContextConfig: TileContextConfig = DefaultTileContextConfig,
    val tileDownloads: Map<DownloadTileId, TileDownloadSnapshot> = emptyMap(),
    val mapInfo: RouteMapInfoUiState = RouteMapInfoUiState(),
    val debugGpsEnabled: Boolean = false,
    val sessionRunning: Boolean = false,
    val routeLoading: Boolean = false,
    val hasCoarsePermission: Boolean = false,
    val hasFinePermission: Boolean = false,
    val batterySaverEnabled: Boolean = true,
    val status: RouteStatus = RouteStatus(
        tone = RouteTone.Idle,
        badge = "Idle",
        headline = "Load a GPX route",
        detail = "GeePee only watches your drift from the line.",
    ),
) {
    val hasLocationPermission: Boolean
        get() = hasCoarsePermission || hasFinePermission
}

internal enum class OrientationMode {
    NorthUp,
    CourseUp,
}

internal enum class RouteScale(val label: String, val windowWidthMeters: Double) {
    Ten("10 m", 10.0),
    Twenty("20 m", 20.0),
    Fifty("50 m", 50.0),
    Hundred("100 m", 100.0),
    TwoHundred("200 m", 200.0),
    FiveHundred("500 m", 500.0),
    Kilometer("1 km", 1000.0),
    ThreeKilometers("3 km", 3000.0),
    TenKilometers("10 km", 10_000.0),
}

internal fun RouteScale.zoomIn(): RouteScale {
    val index = RouteScale.entries.indexOf(this)
    return RouteScale.entries[max(index - 1, 0)]
}

internal fun RouteScale.zoomOut(): RouteScale {
    val index = RouteScale.entries.indexOf(this)
    return RouteScale.entries[min(index + 1, RouteScale.entries.lastIndex)]
}

internal fun RouteScale.next(): RouteScale {
    val index = RouteScale.entries.indexOf(this)
    val nextIndex = (index + 1) % RouteScale.entries.size
    return RouteScale.entries[nextIndex]
}

internal fun RouteScale.scaleBarDistanceMeters(): Double {
    val target = windowWidthMeters * 0.28
    val candidates = listOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
    return candidates.lastOrNull { it <= target } ?: candidates.first()
}

internal fun closestRouteScale(windowWidthMeters: Double): RouteScale {
    return RouteScale.entries.minBy { scale ->
        abs(ln(scale.windowWidthMeters / windowWidthMeters.coerceAtLeast(1.0)))
    }
}

internal fun nextRouteScaleFrom(windowWidthMeters: Double): RouteScale {
    val current = closestRouteScale(windowWidthMeters)
    return current.next()
}

internal fun formatAge(ageMillis: Long): String {
    val totalSeconds = ageMillis / 1_000L
    return when {
        totalSeconds < 60L -> "$totalSeconds sec"
        totalSeconds < 3_600L -> "${totalSeconds / 60L} min"
        else -> "${totalSeconds / 3_600L} hr"
    }
}
