package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RouteMapInfoRegressionTest {
    @Test
    fun realOverlayNearbyWayQueryMatchesDirectPathAcrossFocusSweep() {
        val fixture = loadRouteMapInfoFixture()
        val runtimePack = compileTileRuntimePack(fixture.sourcePack)
        val overlay = buildRouteTileOverlay(
            routeModel = fixture.routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )
        val bundle = RouteTileOverlayBundle(runtimePack = runtimePack, overlay = overlay)
        val focusPoints = sampleRoutePointsWithinBounds(
            geoPoints = fixture.geoPoints,
            bounds = fixture.sourcePack.queryBounds,
            maxSamples = 6,
        )
        val widths = listOf(150.0, 300.0, 600.0, 1_000.0)

        focusPoints.forEach { focusPoint ->
            widths.forEach { widthMeters ->
                val focus = buildRouteMapInfoFocus(fixture.routeModel, focusPoint, widthMeters)
                val directNearbyWays = buildRouteNearbyWays(
                    routeModel = fixture.routeModel,
                    packs = listOf(fixture.sourcePack),
                    config = DefaultTileContextConfig,
                    focus = focus,
                )
                val overlayNearbyWays = queryRouteTileOverlayNearbyWays(
                    routeModel = fixture.routeModel,
                    bundle = bundle,
                    focus = focus,
                    config = DefaultTileContextConfig,
                )

                assertNearbyWaySnippetsEquivalent(directNearbyWays, overlayNearbyWays, tolerance = 0.2)
            }
        }
    }

    @Test
    fun realRuntimeNearbyWayQueryMatchesDirectPathAcrossFocusSweep() {
        val fixture = loadRouteMapInfoFixture()
        val runtimePack = compileTileRuntimePack(fixture.sourcePack)
        val focusPoints = sampleRoutePointsWithinBounds(
            geoPoints = fixture.geoPoints,
            bounds = fixture.sourcePack.queryBounds,
            maxSamples = 6,
        )
        val widths = listOf(150.0, 300.0, 600.0, 1_000.0)

        focusPoints.forEach { focusPoint ->
            widths.forEach { widthMeters ->
                val focus = buildRouteMapInfoFocus(fixture.routeModel, focusPoint, widthMeters)
                val directNearbyWays = buildRouteNearbyWays(
                    routeModel = fixture.routeModel,
                    packs = listOf(fixture.sourcePack),
                    config = DefaultTileContextConfig,
                    focus = focus,
                )
                val runtimeNearbyWays = queryTileRuntimeNearbyWays(
                    routeModel = fixture.routeModel,
                    runtimePack = runtimePack,
                    focus = focus,
                    focusHintEdgeIndexes = nearbyWayFocusRouteEdgeIndexes(
                        routeModel = fixture.routeModel,
                        focus = focus,
                        config = DefaultTileContextConfig,
                    ),
                    config = DefaultTileContextConfig,
                )

                assertNearbyWaySnippetsEquivalent(directNearbyWays, runtimeNearbyWays, tolerance = 0.2)
            }
        }
    }

    @Test
    fun overlayNearbyWayQueryDoesNotDropVisibleFeatureAcrossSmallPan() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val runtimePack = compileTileRuntimePack(syntheticPanStabilityTilePack())
        val overlay = buildRouteTileOverlay(
            routeModel = routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )
        val bundle = RouteTileOverlayBundle(runtimePack = runtimePack, overlay = overlay)
        val focusA = buildRouteMapInfoFocus(routeModel, GeoPoint(0.0, 0.0050), 250.0)
        val focusB = buildRouteMapInfoFocus(routeModel, GeoPoint(0.0, 0.0054), 250.0)

        val overlayNearbyWaysA = queryRouteTileOverlayNearbyWays(
            routeModel = routeModel,
            bundle = bundle,
            focus = focusA,
            config = DefaultTileContextConfig,
        )
        val overlayNearbyWaysB = queryRouteTileOverlayNearbyWays(
            routeModel = routeModel,
            bundle = bundle,
            focus = focusB,
            config = DefaultTileContextConfig,
        )
        val runtimeNearbyWaysA = queryTileRuntimeNearbyWays(
            routeModel = routeModel,
            runtimePack = runtimePack,
            focus = focusA,
            focusHintEdgeIndexes = nearbyWayFocusRouteEdgeIndexes(routeModel, focusA, DefaultTileContextConfig),
            config = DefaultTileContextConfig,
        )
        val runtimeNearbyWaysB = queryTileRuntimeNearbyWays(
            routeModel = routeModel,
            runtimePack = runtimePack,
            focus = focusB,
            focusHintEdgeIndexes = nearbyWayFocusRouteEdgeIndexes(routeModel, focusB, DefaultTileContextConfig),
            config = DefaultTileContextConfig,
        )

        assertEquals(listOf("way/branch"), overlayNearbyWaysA.map(RouteNearbyWaySnippet::featureId))
        assertEquals(listOf("way/branch"), overlayNearbyWaysB.map(RouteNearbyWaySnippet::featureId))
        assertEquals(listOf("way/branch"), runtimeNearbyWaysA.map(RouteNearbyWaySnippet::featureId))
        assertEquals(listOf("way/branch"), runtimeNearbyWaysB.map(RouteNearbyWaySnippet::featureId))
    }

    @Test
    fun overlayNearbyWayQueryIsMonotonicAsViewportWidens() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val runtimePack = compileTileRuntimePack(syntheticPanStabilityTilePack())
        val overlay = buildRouteTileOverlay(
            routeModel = routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )
        val bundle = RouteTileOverlayBundle(runtimePack = runtimePack, overlay = overlay)
        val focusPoint = GeoPoint(0.0, 0.0050)

        val widths = listOf(120.0, 250.0, 500.0)
        val visibleFeatureSets = widths.map { widthMeters ->
            val focus = buildRouteMapInfoFocus(routeModel, focusPoint, widthMeters)
            queryRouteTileOverlayNearbyWays(
                routeModel = routeModel,
                bundle = bundle,
                focus = focus,
                config = DefaultTileContextConfig,
            ).map(RouteNearbyWaySnippet::featureId).toSet()
        }

        visibleFeatureSets.zipWithNext().forEach { (smaller, larger) ->
            assertTrue(larger.containsAll(smaller))
        }
    }

    @Test
    fun coordinatorReturnsPartialOverlayResultThenCompletedOverlayResult() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val focusPoint = GeoPoint(0.0, 0.0050)
        val sourcePack = syntheticPanStabilityTilePack().copy(
            tileId = tileIdForGeoPoint(focusPoint, DefaultTileContextConfig.downloadZoom),
        )
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
        val focus = buildRouteMapInfoFocus(routeModel, analysis.nearestGeoPoint, 250.0)
        val tileDownloads = mapOf(
            sourcePack.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                actualBytes = 1L,
                updatedAtMillis = sourcePack.fetchedAtMillis,
            ),
        )

        withRouteMapInfoRepository(sourcePack) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteMapInfoRegressionTest",
            )
            try {
                val latch = CountDownLatch(2)
                val results = mutableListOf<RouteMapInfoState>()
                coordinator.rebuildNearbyWays(
                    routeModel = routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    existingLocalStatus = null,
                    focus = focus,
                    defaultFocusWindowWidthMeters = focus.windowWidthMeters,
                    force = true,
                    onStarted = {},
                    onResult = { rebuilt ->
                        results += rebuilt
                        latch.countDown()
                    },
                )

                assertTrue("Timed out waiting for partial + completed map-info results", latch.await(5, TimeUnit.SECONDS))
                assertEquals(2, results.size)

                val partial = results.first()
                assertEquals(true, partial.localNearbyWays?.nearbyWaysLoading)
                assertEquals(1, partial.localNearbyWays?.downloadedLocalTileCount)
                assertEquals(0, partial.localNearbyWays?.overlayReadyLocalTileCount)
                assertEquals(false, partial.localNearbyWays?.hasVisibleTileData)
                assertTrue(partial.nearbyWays.isEmpty())

                val completed = results.last()
                assertEquals(false, completed.localNearbyWays?.nearbyWaysLoading)
                assertEquals(1, completed.localNearbyWays?.downloadedLocalTileCount)
                assertEquals(1, completed.localNearbyWays?.overlayReadyLocalTileCount)
                assertEquals(true, completed.localNearbyWays?.hasVisibleTileData)
                assertEquals(listOf("way/branch"), completed.nearbyWays.map(RouteNearbyWaySnippet::featureId))
            } finally {
                coordinator.shutdown()
            }
        }
    }

    @Test
    fun coordinatorDedupesRepeatedNearbyWayRequestWithoutForce() {
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
        val tileDownloads = mapOf(
            fixture.sourcePack.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                actualBytes = 1L,
                updatedAtMillis = fixture.sourcePack.fetchedAtMillis,
            ),
        )

        withRouteMapInfoRepository(fixture.sourcePack) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteMapInfoRegressionTest",
            )
            try {
                val focus = buildRouteMapInfoFocus(fixture.routeModel, analysis.nearestGeoPoint, 1_000.0)
                awaitRouteMapInfoNearbyWayRebuild(
                    coordinator = coordinator,
                    routeModel = fixture.routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    focus = focus,
                    force = true,
                )

                var startedCalls = 0
                var resultCalls = 0
                coordinator.rebuildNearbyWays(
                    routeModel = fixture.routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    existingLocalStatus = null,
                    focus = focus,
                    defaultFocusWindowWidthMeters = 1_000.0,
                    force = false,
                    onStarted = { startedCalls++ },
                    onResult = { resultCalls++ },
                )

                assertEquals(0, startedCalls)
                assertEquals(0, resultCalls)
            } finally {
                coordinator.shutdown()
            }
        }
    }

    @Test
    fun coordinatorForceReloadReturnsCachedNearbyWayResultWithoutLoadingState() {
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
        val tileDownloads = mapOf(
            fixture.sourcePack.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                actualBytes = 1L,
                updatedAtMillis = fixture.sourcePack.fetchedAtMillis,
            ),
        )

        withRouteMapInfoRepository(fixture.sourcePack) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteMapInfoRegressionTest",
            )
            try {
                val focus = buildRouteMapInfoFocus(fixture.routeModel, analysis.nearestGeoPoint, 1_000.0)
                val initial = awaitRouteMapInfoNearbyWayRebuild(
                    coordinator = coordinator,
                    routeModel = fixture.routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    focus = focus,
                    force = true,
                )

                var startedCalls = 0
                var result: RouteMapInfoState? = null
                coordinator.rebuildNearbyWays(
                    routeModel = fixture.routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    existingLocalStatus = initial.localNearbyWays,
                    focus = focus,
                    defaultFocusWindowWidthMeters = 1_000.0,
                    force = true,
                    onStarted = { startedCalls++ },
                    onResult = { rebuilt -> result = rebuilt },
                )

                assertEquals(0, startedCalls)
                assertEquals(initial, result)
            } finally {
                coordinator.shutdown()
            }
        }
    }
}

private fun syntheticPanStabilityTilePack(): TileContextPack {
    return TileContextPack(
        tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
        queryBounds = GeoBounds(west = 0.0, south = 0.0, east = 0.01, north = 0.01),
        fetchedAtMillis = 0L,
        features = listOf(
            TileContextFeature(
                featureId = "way/branch",
                geometryKind = TileGeometryKind.Way,
                tags = mapOf("highway" to "path"),
                geometry = listOf(
                    GeoPoint(lat = 0.0, lon = 0.005),
                    GeoPoint(lat = 0.0012, lon = 0.005),
                    GeoPoint(lat = 0.0020, lon = 0.005),
                ),
            ),
            TileContextFeature(
                featureId = "way/on-route",
                geometryKind = TileGeometryKind.Way,
                tags = mapOf("highway" to "cycleway"),
                geometry = listOf(
                    GeoPoint(lat = 0.0, lon = 0.003),
                    GeoPoint(lat = 0.0, lon = 0.007),
                ),
            ),
        ),
    )
}
