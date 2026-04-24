package dev.ra.geepee

import android.location.Location
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal data class RouteStatusInputs(
    val routeLoading: Boolean,
    val routeModel: RouteModel?,
    val issueMessage: String?,
    val sessionActive: Boolean,
    val hasLocationPermission: Boolean,
    val hasFinePermission: Boolean,
    val locationProvidersEnabled: Boolean,
    val currentFix: LocationFix?,
    val currentAnalysis: RouteAnalysis?,
    val headingDegrees: Double?,
)

internal fun buildRouteStatus(inputs: RouteStatusInputs): RouteStatus {
    if (inputs.routeLoading) {
        return RouteStatus(
            tone = RouteTone.Ready,
            badge = "Loading",
            headline = "Reading route",
            detail = "Parsing the GPX file now.",
        )
    }

    if (inputs.routeModel == null) {
        return if (inputs.issueMessage != null) {
            RouteStatus(
                tone = RouteTone.Warning,
                badge = "Route issue",
                headline = "Could not load route",
                detail = inputs.issueMessage,
            )
        } else {
            RouteStatus(
                tone = RouteTone.Idle,
                badge = "Idle",
                headline = "Load a GPX route",
                detail = "GeePee only shows when you drift from the line.",
            )
        }
    }

    if (!inputs.sessionActive) {
        return RouteStatus(
            tone = RouteTone.Ready,
            badge = "Ready",
            headline = "Route ready",
            detail = "${formatDistance(inputs.routeModel.totalLengthMeters)} loaded. Start when you want live drift alerts.",
        )
    }

    if (!inputs.hasLocationPermission) {
        return RouteStatus(
            tone = RouteTone.Warning,
            badge = "Permission",
            headline = "Allow location",
            detail = "GeePee needs foreground location during an active session.",
        )
    }

    if (!inputs.locationProvidersEnabled) {
        return RouteStatus(
            tone = RouteTone.Warning,
            badge = "Location off",
            headline = "Turn on location",
            detail = "Enable GPS or network location on the phone.",
        )
    }

    if (inputs.currentFix == null || inputs.currentAnalysis == null) {
        return RouteStatus(
            tone = RouteTone.Ready,
            badge = "Locating",
            headline = "Looking for your route position",
            detail = inputs.issueMessage ?: "Keep the app open until the first fix lands.",
        )
    }

    if (!inputs.hasFinePermission) {
        return RouteStatus(
            tone = RouteTone.Warning,
            badge = "Approximate",
            headline = "Precise location is better",
            detail = "Approximate fixes are too loose for reliable off-route alerts.",
        )
    }

    return routeStatusForAnalysis(
        fix = inputs.currentFix,
        analysis = inputs.currentAnalysis,
        headingDegrees = inputs.headingDegrees,
    )
}

internal fun routeStatusForAnalysis(
    fix: LocationFix,
    analysis: RouteAnalysis,
    headingDegrees: Double?,
): RouteStatus {
    val onThreshold = max(12.0, analysis.accuracyMeters?.toDouble() ?: 0.0)
    val driftingThreshold = max(35.0, onThreshold * 2.0)
    val offRoute = analysis.offRouteMeters
    val tone = when {
        offRoute <= onThreshold -> RouteTone.OnRoute
        offRoute <= driftingThreshold -> RouteTone.Drifting
        else -> RouteTone.OffRoute
    }

    val headline = if (tone == RouteTone.OnRoute) {
        "On route"
    } else {
        "${formatDistance(offRoute)} back to route"
    }

    val routeBearing = routeBearingDegrees(fix, analysis.nearestGeoPoint)
    val detailBits = mutableListOf(
        routeDirectionCue(routeBearing, headingDegrees),
        "${formatDistance(analysis.remainingMeters)} left",
    )
    analysis.accuracyMeters?.let { detailBits += "±${formatDistance(it.toDouble())}" }

    return RouteStatus(
        tone = tone,
        badge = when (tone) {
            RouteTone.OnRoute -> "On route"
            RouteTone.Drifting -> "Drifting"
            RouteTone.OffRoute -> "Off route"
            RouteTone.Warning -> "Warning"
            RouteTone.Ready -> "Ready"
            RouteTone.Idle -> "Idle"
        },
        headline = headline,
        detail = detailBits.joinToString(" · "),
    )
}

internal fun routeDirectionCue(absoluteBearing: Double, headingDegrees: Double?): String {
    if (headingDegrees != null) {
        val relative = normalizeSignedHeadingDegrees(absoluteBearing - headingDegrees)
        val magnitude = abs(relative).roundToInt()
        return when {
            magnitude <= 15 -> "Route ahead"
            magnitude >= 150 -> "Route behind"
            relative > 0 -> "Route ${magnitude}° right"
            else -> "Route ${magnitude}° left"
        }
    }

    return "Route ${compassDirection(absoluteBearing)}"
}

internal fun routeBearingDegrees(fix: LocationFix, nearestGeoPoint: GeoPoint): Double {
    val results = FloatArray(3)
    Location.distanceBetween(fix.lat, fix.lon, nearestGeoPoint.lat, nearestGeoPoint.lon, results)
    return normalizeHeadingDegrees(results.getOrNull(1)?.toDouble() ?: 0.0)
}

internal fun compassDirection(value: Double): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = (((normalizeHeadingDegrees(value) + 22.5) % 360.0) / 45.0).toInt()
    return directions[index]
}
