package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class RouteLoadCoordinatorTest {
    private val directExecutor = Executor { command -> command.run() }
    private val routeRef = "content://geepee/route.gpx"

    @Test
    fun successfulLoadRemembersSelectionWhenRequested() {
        var remembered: Triple<String, String, Boolean>? = null
        var outcome: RouteLoadOutcome? = null
        val coordinator = RouteLoadCoordinator<String>(
            loadRoute = { _, _, reversed ->
                LoadedRoute(
                    model = RouteModel(
                        projection = Projection(0.0, 0.0, 1.0),
                        segments = emptyList(),
                        edges = emptyList(),
                        spatialIndex = RouteSpatialIndex(
                            cellSizeMeters = 1.0,
                            cells = emptyMap(),
                            minCellX = 0,
                            maxCellX = 0,
                            minCellY = 0,
                            maxCellY = 0,
                        ),
                        pointCount = 0,
                        totalLengthMeters = 0.0,
                        bounds = Bounds(0.0, 0.0, 0.0, 0.0),
                    ),
                    displayName = "Tisza",
                    baseDisplayName = "Tisza",
                    isReversed = reversed,
                )
            },
            rememberRoute = { uri, routeName, reversed -> remembered = Triple(uri, routeName, reversed) },
            workExecutor = directExecutor,
            callbackExecutor = directExecutor,
            logFailure = { _, _ -> },
        )

        coordinator.load(
            request = RouteLoadRequest(
                routeRef = routeRef,
                displayName = null,
                reversed = true,
                rememberSelection = true,
                fromRestore = false,
            ),
            onOutcome = { outcome = it },
        )

        assertEquals(Triple(routeRef, "Tisza", true), remembered)
        assertTrue(outcome is RouteLoadOutcome.Success)
    }

    @Test
    fun failedRestoreLoadAsksCallerToClearRememberedRoute() {
        var outcome: RouteLoadOutcome? = null
        val coordinator = RouteLoadCoordinator<String>(
            loadRoute = { _, _, _ -> throw IllegalArgumentException("bad gpx") },
            rememberRoute = { _, _, _ -> error("should not remember failed load") },
            workExecutor = directExecutor,
            callbackExecutor = directExecutor,
            logFailure = { _, _ -> },
        )

        coordinator.load(
            request = RouteLoadRequest(
                routeRef = routeRef,
                displayName = "Broken",
                reversed = false,
                rememberSelection = false,
                fromRestore = true,
            ),
            onOutcome = { outcome = it },
        )

        val failure = outcome as RouteLoadOutcome.Failure
        assertTrue(failure.issueMessage.contains("IllegalArgumentException"))
        assertTrue(failure.issueMessage.contains("bad gpx"))
        assertTrue(failure.clearRememberedRoute)
    }

    @Test
    fun failedNormalLoadKeepsRememberedRouteUntouched() {
        var outcome: RouteLoadOutcome? = null
        val coordinator = RouteLoadCoordinator<String>(
            loadRoute = { _, _, _ -> throw IllegalStateException("parse failed") },
            rememberRoute = { _, _, _ -> error("should not remember failed load") },
            workExecutor = directExecutor,
            callbackExecutor = directExecutor,
            logFailure = { _, _ -> },
        )

        coordinator.load(
            request = RouteLoadRequest(
                routeRef = routeRef,
                displayName = "Broken",
                reversed = false,
                rememberSelection = false,
                fromRestore = false,
            ),
            onOutcome = { outcome = it },
        )

        val failure = outcome as RouteLoadOutcome.Failure
        assertFalse(failure.clearRememberedRoute)
    }
}
