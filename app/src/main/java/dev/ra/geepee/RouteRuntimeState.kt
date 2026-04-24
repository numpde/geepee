package dev.ra.geepee

import android.location.Location
import kotlin.math.hypot

private const val LOCATION_HISTORY_LIMIT = 12
private const val LOCATION_HISTORY_MIN_DISTANCE_METERS = 2.5

internal class RouteRuntimeState {
    var routeModel: RouteModel? = null
        private set

    private var routeMatcher: RouteMatcher? = null
    var currentFix: LocationFix? = null
        private set
    var currentAnalysis: RouteAnalysis? = null
        private set
    var locationHistoryPoints: List<ProjectedPoint> = emptyList()
        private set
    private var currentHeadingDegrees: Double? = null
    private var smoothedHeading: SmoothedHeading? = null

    fun applyRoute(model: RouteModel) {
        routeModel = model
        routeMatcher = RouteMatcher(model)
        clearRouteProjection()
        recomputeAnalysis()
    }

    fun clearRoute() {
        routeModel = null
        routeMatcher = null
        clearLiveState()
    }

    fun resetMatcher() {
        routeMatcher?.reset()
    }

    fun acceptFix(
        fix: LocationFix,
        sessionActive: Boolean,
        batterySaverEnabled: Boolean,
    ) {
        currentFix = fix
        recomputeAnalysis()
        appendLocationHistory(sessionActive)
        refreshSmoothedHeading(batterySaverEnabled)
    }

    fun acceptLocation(
        location: Location,
        sessionActive: Boolean,
        batterySaverEnabled: Boolean,
    ) {
        acceptFix(
            fix = LocationFix(
                lat = location.latitude,
                lon = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                headingDegrees = location.bearing.takeIf { location.hasBearing() },
                speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                timestampMillis = location.time,
                bearingAccuracyDegrees = location.bearingAccuracyDegrees.takeIf { location.hasBearingAccuracy() },
            ),
            sessionActive = sessionActive,
            batterySaverEnabled = batterySaverEnabled,
        )
    }

    fun acceptHeading(
        headingDegrees: Double,
        batterySaverEnabled: Boolean,
    ) {
        currentHeadingDegrees = headingDegrees
        refreshSmoothedHeading(batterySaverEnabled)
    }

    fun buildCompassState(): CompassState? {
        val fix = currentFix ?: return null
        val analysis = currentAnalysis ?: return null
        return CompassState(
            routeBearingDegrees = routeBearingDegrees(fix, analysis.nearestGeoPoint),
            headingDegrees = displayHeadingDegrees(),
        )
    }

    fun displayHeadingDegrees(): Double? {
        return displayHeadingDegrees(
            smoothedHeading = smoothedHeading,
            fix = currentFix,
            sensorHeadingDegrees = currentHeadingDegrees,
        )
    }

    fun clearLiveState() {
        currentFix = null
        clearRouteProjection()
        clearHeadingState()
    }

    fun clearHeadingState() {
        currentHeadingDegrees = null
        smoothedHeading = null
    }

    private fun recomputeAnalysis() {
        val model = routeModel
        val fix = currentFix
        currentAnalysis = if (model != null && fix != null) {
            routeMatcher?.match(fix)
                ?: analyzeLocationAgainstModel(
                    model = model,
                    fix = fix,
                    previousNearestEdgeIndex = currentAnalysis?.nearestEdgeIndex?.takeIf { it >= 0 },
                )
        } else {
            null
        }
    }

    private fun clearRouteProjection() {
        currentAnalysis = null
        locationHistoryPoints = emptyList()
    }

    private fun appendLocationHistory(sessionActive: Boolean) {
        val point = currentAnalysis?.point ?: return
        if (!sessionActive) {
            return
        }

        val previous = locationHistoryPoints.lastOrNull()
        if (previous != null && projectedDistanceMeters(previous, point) < LOCATION_HISTORY_MIN_DISTANCE_METERS) {
            return
        }

        locationHistoryPoints = (locationHistoryPoints + point).takeLast(LOCATION_HISTORY_LIMIT)
    }

    private fun refreshSmoothedHeading(batterySaverEnabled: Boolean) {
        smoothedHeading = smoothHeading(
            previous = smoothedHeading,
            target = currentHeadingReading(
                fix = currentFix,
                sensorHeadingDegrees = currentHeadingDegrees,
            ),
            batterySaverEnabled = batterySaverEnabled,
        )
    }

    private fun projectedDistanceMeters(left: ProjectedPoint, right: ProjectedPoint): Double {
        return hypot(right.x - left.x, right.y - left.y)
    }
}
