package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRuntimeStateTest {
    @Test
    fun applyRoutePreservesLatestFixAndImmediatelyReprojectsIt() {
        val runtimeState = RouteRuntimeState()
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0010, 0.0),
                ),
            ),
        )
        val fix = LocationFix(
            lat = 0.0004,
            lon = 0.0,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 1_000L,
        )

        runtimeState.acceptFix(
            fix = fix,
            sessionActive = true,
            batterySaverEnabled = false,
        )
        runtimeState.applyRoute(route)

        assertEquals(fix, runtimeState.currentFix)
        assertNotNull(runtimeState.currentAnalysis)
    }

    @Test
    fun applyRouteClearsOldHistoryButKeepsCurrentProjection() {
        val originalRoute = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0010, 0.0),
                ),
            ),
        )
        val replacementRoute = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.0010),
                ),
            ),
        )
        val runtimeState = RouteRuntimeState().apply {
            applyRoute(originalRoute)
            acceptFix(
                fix = LocationFix(
                    lat = 0.0005,
                    lon = 0.0,
                    accuracyMeters = 5f,
                    headingDegrees = null,
                    speedMetersPerSecond = null,
                    timestampMillis = 1_000L,
                ),
                sessionActive = true,
                batterySaverEnabled = false,
            )
        }

        assertTrue(runtimeState.locationHistoryPoints.isNotEmpty())

        runtimeState.applyRoute(replacementRoute)

        assertTrue(runtimeState.locationHistoryPoints.isEmpty())
        assertNotNull(runtimeState.currentFix)
        assertNotNull(runtimeState.currentAnalysis)
    }
}
