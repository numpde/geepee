package dev.ra.geepee

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val currentBelief: RouteBelief?,
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

    val currentBelief = inputs.currentBelief ?: return RouteStatus(
        tone = RouteTone.Ready,
        badge = "Locating",
        headline = "Estimating route confidence",
        detail = inputs.issueMessage ?: "Keep the app open until route confidence is available.",
    )

    return routeStatusForAnalysis(
        fix = inputs.currentFix,
        analysis = inputs.currentAnalysis,
        belief = currentBelief,
        headingDegrees = inputs.headingDegrees,
    )
}

internal fun routeStatusForAnalysis(
    fix: LocationFix,
    analysis: RouteAnalysis,
    belief: RouteBelief,
    headingDegrees: Double?,
): RouteStatus {
    val offRoute = analysis.offRouteMeters
    val tone = when (belief.adherence) {
        RouteAdherence.OnRoute -> RouteTone.OnRoute
        RouteAdherence.Uncertain -> RouteTone.Drifting
        RouteAdherence.OffRoute -> RouteTone.OffRoute
    }

    val headline = when (belief.adherence) {
        RouteAdherence.OnRoute -> "On route"
        RouteAdherence.Uncertain -> "Position uncertain"
        RouteAdherence.OffRoute -> "${formatDistance(offRoute)} back to route"
    }

    val routeBearing = routeBearingDegrees(fix, analysis.nearestGeoPoint)
    val detailBits = mutableListOf(
        routeDirectionCue(routeBearing, headingDegrees),
        "${formatDistance(analysis.remainingMeters)} left",
    )
    analysis.accuracyMeters?.let { detailBits += "±${formatDistance(it.toDouble())}" }

    return RouteStatus(
        tone = tone,
        badge = when (belief.adherence) {
            RouteAdherence.OnRoute -> "On route"
            RouteAdherence.Uncertain -> "Uncertain"
            RouteAdherence.OffRoute -> "Off route"
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
    val startLat = Math.toRadians(fix.lat)
    val endLat = Math.toRadians(nearestGeoPoint.lat)
    val deltaLon = Math.toRadians(nearestGeoPoint.lon - fix.lon)
    val y = sin(deltaLon) * cos(endLat)
    val x = cos(startLat) * sin(endLat) - sin(startLat) * cos(endLat) * cos(deltaLon)
    return normalizeHeadingDegrees(Math.toDegrees(atan2(y, x)))
}

internal fun compassDirection(value: Double): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = (((normalizeHeadingDegrees(value) + 22.5) % 360.0) / 45.0).toInt()
    return directions[index]
}
