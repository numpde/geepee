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
        var remembered: Pair<String, String>? = null
        var outcome: RouteLoadOutcome? = null
        val coordinator = RouteLoadCoordinator<String>(
            loadRoute = { _, _ ->
                LoadedRoute(
                    model = RouteModel(
                        projection = Projection(0.0, 0.0, 1.0),
                        segments = emptyList(),
                        edges = emptyList(),
                        spatialIndex = RouteSpatialIndex(1.0, emptyMap()),
                        pointCount = 0,
                        totalLengthMeters = 0.0,
                        bounds = Bounds(0.0, 0.0, 0.0, 0.0),
                    ),
                    displayName = "Tisza",
                )
            },
            rememberRoute = { uri, routeName -> remembered = uri to routeName },
            workExecutor = directExecutor,
            callbackExecutor = directExecutor,
            logFailure = { _, _ -> },
        )

        coordinator.load(
            request = RouteLoadRequest(
                routeRef = routeRef,
                displayName = null,
                rememberSelection = true,
                fromRestore = false,
            ),
            onOutcome = { outcome = it },
        )

        assertEquals(routeRef to "Tisza", remembered)
        assertTrue(outcome is RouteLoadOutcome.Success)
    }

    @Test
    fun failedRestoreLoadAsksCallerToClearRememberedRoute() {
        var outcome: RouteLoadOutcome? = null
        val coordinator = RouteLoadCoordinator<String>(
            loadRoute = { _, _ -> throw IllegalArgumentException("bad gpx") },
            rememberRoute = { _, _ -> error("should not remember failed load") },
            workExecutor = directExecutor,
            callbackExecutor = directExecutor,
            logFailure = { _, _ -> },
        )

        coordinator.load(
            request = RouteLoadRequest(
                routeRef = routeRef,
                displayName = "Broken",
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
            loadRoute = { _, _ -> throw IllegalStateException("parse failed") },
            rememberRoute = { _, _ -> error("should not remember failed load") },
            workExecutor = directExecutor,
            callbackExecutor = directExecutor,
            logFailure = { _, _ -> },
        )

        coordinator.load(
            request = RouteLoadRequest(
                routeRef = routeRef,
                displayName = "Broken",
                rememberSelection = false,
                fromRestore = false,
            ),
            onOutcome = { outcome = it },
        )

        val failure = outcome as RouteLoadOutcome.Failure
        assertFalse(failure.clearRememberedRoute)
    }
}
