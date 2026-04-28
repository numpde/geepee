package dev.ra.geepee

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class RouteContextCoordinatorBenchmarkTest {
    @Test
    fun benchmarkRepositoryBackedRouteContextRebuild() {
        requireBenchmarkOptIn()
        val sourcePack = loadRouteMapInfoTileFixture("tile-context/10-571-356-local.json")
        val routeModel = loadRouteMapInfoRouteModel()

        val coldRouteContextNanos = benchmarkNanos(iterations = 5) {
            withSeededTileContextRepository(
                prefix = "geepee-route-context-coordinator-bench",
                sourcePack = sourcePack,
            ) { repository ->
                val coordinator = RouteContextCoordinator(
                    tileContextRepository = repository,
                    tileContextConfig = DefaultTileContextConfig,
                    callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                    logTag = "RouteContextCoordinatorBenchmarkTest",
                )
                try {
                    awaitRouteContextRebuild(coordinator, routeModel)
                } finally {
                    coordinator.shutdown()
                }
            }
        }

        val warmRouteContextNanos = withSeededTileContextRepository(
            prefix = "geepee-route-context-coordinator-bench",
            sourcePack = sourcePack,
        ) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteContextCoordinatorBenchmarkTest",
            )
            try {
                awaitRouteContextRebuild(coordinator, routeModel)
                benchmarkNanos(iterations = 100) {
                    awaitRouteContextRebuild(coordinator, routeModel)
                }
            } finally {
                coordinator.shutdown()
            }
        }

        println(
            buildString {
                appendLine("ROUTE_CONTEXT_COORDINATOR_BENCH tile=${sourcePack.tileId.cacheKey}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_BENCH cold_rebuild_avg_ms=${formatBenchmarkMillis(coldRouteContextNanos)}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_BENCH warm_rebuild_avg_ms=${formatBenchmarkMillis(warmRouteContextNanos)}")
            },
        )
    }

    @Test
    fun benchmarkRepositoryBackedNearbyWayRebuild() {
        requireBenchmarkOptIn()
        val sourcePack = loadRouteMapInfoTileFixture("tile-context/10-571-356-local.json")
        val routeModel = loadRouteMapInfoRouteModel()
        val focusPoint = loadRouteMapInfoGeoPointsByIndex().getValue(6_854)
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = focusPoint.lat,
                lon = focusPoint.lon,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val tileDownloads = mapOf(
            sourcePack.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                actualBytes = 1L,
                updatedAtMillis = sourcePack.fetchedAtMillis,
            ),
        )

        val coldNearbyWayNanos = benchmarkNanos(iterations = 5) {
            withSeededTileContextRepository(
                prefix = "geepee-route-context-coordinator-bench",
                sourcePack = sourcePack,
            ) { repository ->
                val coordinator = RouteContextCoordinator(
                    tileContextRepository = repository,
                    tileContextConfig = DefaultTileContextConfig,
                    callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                    logTag = "RouteContextCoordinatorBenchmarkTest",
                )
                try {
                    awaitNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = routeModel,
                        analysis = analysis,
                        tileDownloads = tileDownloads,
                        focusWindowWidthMeters = 1_000.0,
                    )
                } finally {
                    coordinator.shutdown()
                }
            }
        }

        val warmNearbyWayNanos = withSeededTileContextRepository(
            prefix = "geepee-route-context-coordinator-bench",
            sourcePack = sourcePack,
        ) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteContextCoordinatorBenchmarkTest",
            )
            try {
                awaitNearbyWayRebuild(
                    coordinator = coordinator,
                    routeModel = routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    focusWindowWidthMeters = 1_000.0,
                )
                benchmarkNanos(iterations = 100) {
                    awaitNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = routeModel,
                        analysis = analysis,
                        tileDownloads = tileDownloads,
                        focusWindowWidthMeters = 1_000.0,
                        force = true,
                    )
                }
            } finally {
                coordinator.shutdown()
            }
        }

        println(
            buildString {
                appendLine("ROUTE_CONTEXT_COORDINATOR_NEARBY_BENCH tile=${sourcePack.tileId.cacheKey}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_NEARBY_BENCH cold_rebuild_avg_ms=${formatBenchmarkMillis(coldNearbyWayNanos)}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_NEARBY_BENCH warm_rebuild_avg_ms=${formatBenchmarkMillis(warmNearbyWayNanos)}")
            },
        )
    }
}

private fun awaitRouteContextRebuild(
    coordinator: RouteContextCoordinator,
    routeModel: RouteModel,
): List<RoutePoi> {
    val latch = CountDownLatch(1)
    var result: List<RoutePoi>? = null
    coordinator.rebuildRouteContext(routeModel) { rebuilt ->
        result = rebuilt
        latch.countDown()
    }
    check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for route-context rebuild" }
    return checkNotNull(result)
}

private fun awaitNearbyWayRebuild(
    coordinator: RouteContextCoordinator,
    routeModel: RouteModel,
    analysis: RouteAnalysis,
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
    focusWindowWidthMeters: Double,
    force: Boolean = true,
): RouteMapInfoState {
    val latch = CountDownLatch(1)
    var result: RouteMapInfoState? = null
    val focus = buildRouteMapInfoFocus(
        routeModel = routeModel,
        focusPoint = analysis.nearestGeoPoint,
        widthMeters = focusWindowWidthMeters,
    )
    coordinator.rebuildNearbyWays(
        routeModel = routeModel,
        analysis = analysis,
        tileDownloads = tileDownloads,
        existingLocalStatus = null,
        focus = focus,
        defaultFocusWindowWidthMeters = focusWindowWidthMeters,
        force = force,
        onStarted = {},
        onResult = { rebuilt ->
            result = rebuilt
            latch.countDown()
        },
    )
    check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for nearby-way rebuild" }
    return checkNotNull(result)
}
