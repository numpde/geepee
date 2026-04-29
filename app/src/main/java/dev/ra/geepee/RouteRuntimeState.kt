package dev.ra.geepee

import android.location.Location
import kotlin.math.hypot
import kotlin.math.max

private const val LOCATION_HISTORY_LIMIT = 12
private const val LOCATION_HISTORY_MIN_DISTANCE_METERS = 2.5
private const val IMPROBABLE_JUMP_BASE_METERS = 120.0
private const val IMPROBABLE_JUMP_SPEED_MULTIPLIER = 3.0
private const val IMPROBABLE_JUMP_ACCURACY_MULTIPLIER = 4.0

internal class RouteRuntimeState {
    var routeModel: RouteModel? = null
        private set

    private var routeMatcher: RouteMatcher? = null
    var currentFix: LocationFix? = null
        private set
    var currentAnalysis: RouteAnalysis? = null
        private set
    var currentBelief: RouteBelief? = null
        private set
    var currentMatchHypotheses: List<RouteMatchDisplayHypothesis> = emptyList()
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
        if (shouldResetForImprobableJump(currentFix, fix)) {
            routeMatcher?.reset()
            clearRouteProjection()
        }
        currentFix = fix
        recomputeAnalysis()
        appendLocationHistory(sessionActive)
        refreshSmoothedHeading(batterySaverEnabled)
    }

    fun teleportToFix(
        fix: LocationFix,
        sessionActive: Boolean,
        batterySaverEnabled: Boolean,
    ) {
        routeMatcher?.reset()
        clearRouteProjection()
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
        if (model != null && fix != null) {
            val matchResult = routeMatcher?.match(fix)
            currentAnalysis = matchResult?.analysis
                ?: analyzeLocationAgainstModel(
                    model = model,
                    fix = fix,
                    previousNearestEdgeIndex = currentAnalysis?.nearestEdgeIndex?.takeIf { it >= 0 },
                )
            currentBelief = matchResult?.belief
            currentMatchHypotheses = matchResult?.hypotheses.orEmpty()
        } else {
            currentAnalysis = null
            currentBelief = null
            currentMatchHypotheses = emptyList()
        }
    }

    private fun clearRouteProjection() {
        currentAnalysis = null
        currentBelief = null
        currentMatchHypotheses = emptyList()
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

    private fun shouldResetForImprobableJump(
        previousFix: LocationFix?,
        currentFix: LocationFix,
    ): Boolean {
        if (previousFix == null) {
            return false
        }

        val elapsedSeconds = max(
            1.0,
            (currentFix.timestampMillis - previousFix.timestampMillis).toDouble() / 1_000.0,
        )
        val averageSpeedMetersPerSecond = listOfNotNull(
            previousFix.speedMetersPerSecond?.toDouble(),
            currentFix.speedMetersPerSecond?.toDouble(),
        ).let { speeds ->
            if (speeds.isEmpty()) {
                0.0
            } else {
                speeds.average()
            }
        }
        val accuracyAllowanceMeters = max(
            previousFix.accuracyMeters?.toDouble() ?: 0.0,
            currentFix.accuracyMeters?.toDouble() ?: 0.0,
        ) * IMPROBABLE_JUMP_ACCURACY_MULTIPLIER
        val improbableJumpThresholdMeters = max(
            IMPROBABLE_JUMP_BASE_METERS,
            averageSpeedMetersPerSecond * elapsedSeconds * IMPROBABLE_JUMP_SPEED_MULTIPLIER + accuracyAllowanceMeters,
        )
        return geoDistanceMeters(
            start = GeoPoint(lat = previousFix.lat, lon = previousFix.lon),
            end = GeoPoint(lat = currentFix.lat, lon = currentFix.lon),
        ) > improbableJumpThresholdMeters
    }

    private fun geoDistanceMeters(
        start: GeoPoint,
        end: GeoPoint,
    ): Double {
        val startLatRadians = Math.toRadians(start.lat)
        val endLatRadians = Math.toRadians(end.lat)
        val deltaX = Math.toRadians(end.lon - start.lon) * kotlin.math.cos((startLatRadians + endLatRadians) / 2.0)
        val deltaY = endLatRadians - startLatRadians
        return hypot(deltaX, deltaY) * 6_371_000.0
    }
}
