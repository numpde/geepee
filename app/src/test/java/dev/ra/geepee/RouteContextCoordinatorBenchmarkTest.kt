package dev.ra.geepee

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
                    awaitRouteMapInfoRouteContextRebuild(coordinator, routeModel)
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
                awaitRouteMapInfoRouteContextRebuild(coordinator, routeModel)
                benchmarkNanos(iterations = 100) {
                    awaitRouteMapInfoRouteContextRebuild(coordinator, routeModel)
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
        val focus = buildRouteMapInfoFocus(
            routeModel = routeModel,
            focusPoint = analysis.nearestGeoPoint,
            widthMeters = 1_000.0,
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
                    awaitRouteMapInfoNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = routeModel,
                        analysis = analysis,
                        tileDownloads = tileDownloads,
                        focus = focus,
                        force = true,
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
                awaitRouteMapInfoNearbyWayRebuild(
                    coordinator = coordinator,
                    routeModel = routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    focus = focus,
                    force = true,
                )
                benchmarkNanos(iterations = 100) {
                    awaitRouteMapInfoNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = routeModel,
                        analysis = analysis,
                        tileDownloads = tileDownloads,
                        focus = focus,
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
