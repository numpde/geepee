package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStatusFormatterTest {
    @Test
    fun buildRouteStatusReturnsReadySummaryWhenRouteLoadedButSessionInactive() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.001),
                ),
            ),
        )

        val status = buildRouteStatus(
            RouteStatusInputs(
                routeLoading = false,
                routeModel = routeModel,
                issueMessage = null,
                sessionActive = false,
                hasLocationPermission = false,
                hasFinePermission = false,
                locationProvidersEnabled = false,
                currentFix = null,
                currentAnalysis = null,
                currentBelief = null,
                headingDegrees = null,
            ),
        )

        assertEquals(RouteTone.Ready, status.tone)
        assertEquals("Ready", status.badge)
        assertEquals("Route ready", status.headline)
        assertTrue(status.detail.contains("loaded. Start when you want live drift alerts."))
    }

    @Test
    fun buildRouteStatusWarnsWhenSessionIsActiveWithOnlyApproximateLocation() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.001),
                ),
            ),
        )
        val analysis = RouteAnalysis(
            point = ProjectedPoint(0.0, 0.0),
            nearestPoint = ProjectedPoint(0.0, 0.0),
            nearestGeoPoint = GeoPoint(0.0, 0.0),
            routeTangentX = 1.0,
            routeTangentY = 0.0,
            offRouteMeters = 0.0,
            routeMeters = 0.0,
            progressMeters = 0.0,
            remainingMeters = 100.0,
            progressRatio = 0.0,
            accuracyMeters = 24f,
            nearestEdgeIndex = 0,
        )

        val status = buildRouteStatus(
            RouteStatusInputs(
                routeLoading = false,
                routeModel = routeModel,
                issueMessage = null,
                sessionActive = true,
                hasLocationPermission = true,
                hasFinePermission = false,
                locationProvidersEnabled = true,
                currentFix = LocationFix(
                    lat = 0.0,
                    lon = 0.0,
                    accuracyMeters = 24f,
                    headingDegrees = 0f,
                    speedMetersPerSecond = 1f,
                    timestampMillis = 1_000L,
                ),
                currentAnalysis = analysis,
                currentBelief = routeBelief(
                    fix = LocationFix(
                        lat = 0.0,
                        lon = 0.0,
                        accuracyMeters = 24f,
                        headingDegrees = 0f,
                        speedMetersPerSecond = 1f,
                        timestampMillis = 1_000L,
                    ),
                    analysis = analysis,
                    adherence = RouteAdherence.OnRoute,
                ),
                headingDegrees = 0.0,
            ),
        )

        assertEquals(RouteTone.Warning, status.tone)
        assertEquals("Approximate", status.badge)
        assertEquals("Precise location is better", status.headline)
    }

    @Test
    fun routeDirectionCueUsesRelativeHeadingAndCompassFallback() {
        assertEquals("Route ahead", routeDirectionCue(5.0, 0.0))
        assertEquals("Route 90° right", routeDirectionCue(90.0, 0.0))
        assertEquals("Route 90° left", routeDirectionCue(270.0, 0.0))
        assertEquals("Route SW", routeDirectionCue(225.0, null))
    }

    @Test
    fun routeStatusUsesBeliefAdherenceInsteadOfDistanceThresholds() {
        val fix = LocationFix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 80f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 1_000L,
        )
        val analysis = routeAnalysis(
            offRouteMeters = 5.0,
            accuracyMeters = 80f,
        )

        val status = routeStatusForAnalysis(
            fix = fix,
            analysis = analysis,
            belief = routeBelief(
                fix = fix,
                analysis = analysis,
                adherence = RouteAdherence.Uncertain,
                routeProbability = 0.5,
                offRouteProbability = 0.5,
            ),
            headingDegrees = null,
        )

        assertEquals(RouteTone.Drifting, status.tone)
        assertEquals("Uncertain", status.badge)
        assertEquals("Position uncertain", status.headline)
    }
}

private fun routeAnalysis(
    offRouteMeters: Double = 0.0,
    accuracyMeters: Float? = 4f,
): RouteAnalysis {
    return RouteAnalysis(
        point = ProjectedPoint(0.0, 0.0),
        nearestPoint = ProjectedPoint(0.0, 0.0),
        nearestGeoPoint = GeoPoint(0.0, 0.0),
        routeTangentX = 1.0,
        routeTangentY = 0.0,
        offRouteMeters = offRouteMeters,
        routeMeters = 0.0,
        progressMeters = 0.0,
        remainingMeters = 100.0,
        progressRatio = 0.0,
        accuracyMeters = accuracyMeters,
        nearestEdgeIndex = 0,
    )
}

private fun routeBelief(
    fix: LocationFix,
    analysis: RouteAnalysis,
    adherence: RouteAdherence,
    routeProbability: Double = 1.0,
    offRouteProbability: Double = 0.0,
): RouteBelief {
    return RouteBelief(
        fix = fix,
        sigmaMeters = 8.0,
        routeProbability = routeProbability,
        offRouteProbability = offRouteProbability,
        adherence = adherence,
        primaryRouteAnalysis = analysis,
        routeCandidates = listOf(
            RouteCandidateBelief(
                analysis = analysis,
                posteriorProbability = routeProbability,
                routeConditionalProbability = 1.0,
                isPrimary = true,
            ),
        ),
    )
}
