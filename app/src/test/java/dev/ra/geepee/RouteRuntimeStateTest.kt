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

    @Test
    fun improbableFixJumpResetsMatcherAndHistoryBeforeReprojecting() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0040, 0.0),
                    GeoPoint(0.0040, 0.00025),
                    GeoPoint(0.0, 0.00025),
                ),
            ),
        )
        val firstFix = LocationFix(
            lat = 0.0022,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 0f,
            speedMetersPerSecond = 2.2f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val jumpedFix = LocationFix(
            lat = 0.0003,
            lon = 0.00025,
            accuracyMeters = 4f,
            headingDegrees = 180f,
            speedMetersPerSecond = 2.2f,
            timestampMillis = 3_000L,
            bearingAccuracyDegrees = 8f,
        )

        val runtimeState = RouteRuntimeState().apply {
            applyRoute(route)
            acceptFix(
                fix = firstFix,
                sessionActive = true,
                batterySaverEnabled = false,
            )
        }
        val freshState = RouteRuntimeState().apply {
            applyRoute(route)
            acceptFix(
                fix = jumpedFix,
                sessionActive = true,
                batterySaverEnabled = false,
            )
        }

        runtimeState.acceptFix(
            fix = jumpedFix,
            sessionActive = true,
            batterySaverEnabled = false,
        )

        assertNotNull(runtimeState.currentAnalysis)
        assertEquals(1, runtimeState.locationHistoryPoints.size)
        assertEquals(
            freshState.currentAnalysis!!.routeMeters,
            runtimeState.currentAnalysis!!.routeMeters,
            0.5,
        )
        assertEquals(
            freshState.currentAnalysis!!.nearestEdgeIndex,
            runtimeState.currentAnalysis!!.nearestEdgeIndex,
        )
    }

    @Test
    fun improbableLoopJumpReacquiresStartBranchInsteadOfDraggingEndProgressForward() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0015, 0.0),
                    GeoPoint(0.0015, 0.0015),
                    GeoPoint(0.0, 0.0015),
                    GeoPoint(0.0, 0.0),
                ),
            ),
        )
        val loopEndFix = LocationFix(
            lat = 0.0,
            lon = 0.0012,
            accuracyMeters = 4f,
            headingDegrees = 270f,
            speedMetersPerSecond = 2.5f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val restartFix = LocationFix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 0f,
            speedMetersPerSecond = 2.5f,
            timestampMillis = 3_000L,
            bearingAccuracyDegrees = 8f,
        )
        val firstForwardFix = LocationFix(
            lat = 0.00035,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 0f,
            speedMetersPerSecond = 2.5f,
            timestampMillis = 5_000L,
            bearingAccuracyDegrees = 8f,
        )

        val runtimeState = RouteRuntimeState().apply {
            applyRoute(route)
            acceptFix(
                fix = loopEndFix,
                sessionActive = true,
                batterySaverEnabled = false,
            )
        }

        runtimeState.acceptFix(
            fix = restartFix,
            sessionActive = true,
            batterySaverEnabled = false,
        )
        val restartMatch = requireNotNull(runtimeState.currentAnalysis)

        runtimeState.acceptFix(
            fix = firstForwardFix,
            sessionActive = true,
            batterySaverEnabled = false,
        )
        val forwardMatch = requireNotNull(runtimeState.currentAnalysis)

        assertTrue(restartMatch.offRouteMeters < 1.0)
        assertTrue(forwardMatch.offRouteMeters < 2.0)
        assertTrue(forwardMatch.routeMeters < route.totalLengthMeters / 2.0)
        assertTrue(forwardMatch.routeMeters > restartMatch.routeMeters)
        assertEquals(2, runtimeState.locationHistoryPoints.size)
    }
}
