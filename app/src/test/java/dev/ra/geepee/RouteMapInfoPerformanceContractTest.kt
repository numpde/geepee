package dev.ra.geepee

import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapInfoPerformanceContractTest {
    @Test
    fun warmOverlayQueryStaysMateriallyCheaperThanColdOverlayBuild() {
        requireRouteMapInfoBenchmarkOptIn()
        val fixture = loadRouteMapInfoFixture()
        val focusPoint = sampleRoutePointsWithinBounds(
            geoPoints = fixture.geoPoints,
            bounds = fixture.sourcePack.queryBounds,
            maxSamples = 1,
        ).single()
        val focus = buildRouteMapInfoFocus(
            routeModel = fixture.routeModel,
            focusPoint = focusPoint,
            widthMeters = 1_000.0,
        )

        val coldOverlayBuildNanos = benchmarkRouteMapInfoNanos(iterations = 5) {
            withRouteMapInfoRepository(fixture.sourcePack) { repository ->
                requireNotNull(
                    repository.loadRouteTileOverlayBundle(
                        routeModel = fixture.routeModel,
                        tileId = fixture.sourcePack.tileId,
                        config = DefaultTileContextConfig,
                    ),
                )
            }
        }

        val (warmOverlayLoadNanos, warmOverlayQueryNanos) = withRouteMapInfoRepository(fixture.sourcePack) { repository ->
            val bundle = requireNotNull(
                repository.loadRouteTileOverlayBundle(
                    routeModel = fixture.routeModel,
                    tileId = fixture.sourcePack.tileId,
                    config = DefaultTileContextConfig,
                ),
            )
            val warmLoad = benchmarkRouteMapInfoNanos(iterations = 100) {
                requireNotNull(
                    repository.loadRouteTileOverlayBundle(
                        routeModel = fixture.routeModel,
                        tileId = fixture.sourcePack.tileId,
                        config = DefaultTileContextConfig,
                    ),
                )
            }
            val warmQuery = benchmarkRouteMapInfoNanos(iterations = 100) {
                queryRouteTileOverlayNearbyWays(
                    routeModel = fixture.routeModel,
                    bundle = bundle,
                    focus = focus,
                    config = DefaultTileContextConfig,
                )
            }
            warmLoad to warmQuery
        }

        assertTrue("Expected cold overlay build to stay slower than warm overlay load", coldOverlayBuildNanos > warmOverlayLoadNanos * 10)
        assertTrue("Expected cold overlay build to stay slower than warm overlay query", coldOverlayBuildNanos > warmOverlayQueryNanos * 10)
    }

    @Test
    fun warmCoordinatorNearbyWayRebuildStaysMateriallyCheaperThanCold() {
        requireRouteMapInfoBenchmarkOptIn()
        val fixture = loadRouteMapInfoFixture()
        val focusPoint = sampleRoutePointsWithinBounds(
            geoPoints = fixture.geoPoints,
            bounds = fixture.sourcePack.queryBounds,
            maxSamples = 1,
        ).single()
        val analysis = analyzeLocationAgainstModel(
            model = fixture.routeModel,
            fix = LocationFix(
                lat = focusPoint.lat,
                lon = focusPoint.lon,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val focus = buildRouteMapInfoFocus(
            routeModel = fixture.routeModel,
            focusPoint = analysis.nearestGeoPoint,
            widthMeters = 1_000.0,
        )
        val tileDownloads = mapOf(
            fixture.sourcePack.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                actualBytes = 1L,
                updatedAtMillis = fixture.sourcePack.fetchedAtMillis,
            ),
        )

        val coldNearbyWayNanos = benchmarkRouteMapInfoNanos(iterations = 5) {
            withRouteMapInfoRepository(fixture.sourcePack) { repository ->
                val coordinator = RouteContextCoordinator(
                    tileContextRepository = repository,
                    tileContextConfig = DefaultTileContextConfig,
                    callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                    logTag = "RouteMapInfoPerformanceContractTest",
                )
                try {
                    awaitRouteMapInfoNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = fixture.routeModel,
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

        val warmNearbyWayNanos = withRouteMapInfoRepository(fixture.sourcePack) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteMapInfoPerformanceContractTest",
            )
            try {
                awaitRouteMapInfoNearbyWayRebuild(
                    coordinator = coordinator,
                    routeModel = fixture.routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    focus = focus,
                    force = true,
                )
                benchmarkRouteMapInfoNanos(iterations = 100) {
                    awaitRouteMapInfoNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = fixture.routeModel,
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

        assertTrue(
            "Expected warm nearby-way rebuild to stay materially cheaper than cold rebuild",
            coldNearbyWayNanos > warmNearbyWayNanos * 5,
        )
    }
}
