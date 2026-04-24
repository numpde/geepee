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
}
