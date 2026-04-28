package dev.ra.geepee

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue

internal data class RouteMapInfoFixture(
    val sourcePack: TileContextPack,
    val routeModel: RouteModel,
    val geoPoints: List<GeoPoint>,
)

internal fun loadRouteMapInfoFixture(
    tileFixturePath: String = "tile-context/10-571-356-local.json",
): RouteMapInfoFixture {
    val geoPoints = loadRouteMapInfoGeoPoints()
    return RouteMapInfoFixture(
        sourcePack = loadRouteMapInfoTileFixture(tileFixturePath),
        geoPoints = geoPoints,
        routeModel = buildRouteModel(listOf(geoPoints)),
    )
}

internal fun loadRouteMapInfoTileFixture(path: String): TileContextPack {
    val resource = requireNotNull(RouteMapInfoFixture::class.java.classLoader?.getResource("dev/ra/geepee/$path")) {
        "Missing tile fixture resource: $path"
    }
    return tileContextPackFromJson(File(resource.toURI()).readText())
}

internal fun loadRouteMapInfoGeoPoints(): List<GeoPoint> {
    return loadGpxGeoPointsFixture("unneplos-tisza-ride.gpx")
}

internal fun loadRouteMapInfoGeoPointsByIndex(): Map<Int, GeoPoint> {
    return loadRouteMapInfoGeoPoints()
        .mapIndexed { index, point -> index to point }
        .toMap()
}

internal fun loadRouteMapInfoRouteModel(): RouteModel {
    return buildRouteModel(listOf(loadRouteMapInfoGeoPoints()))
}

internal fun buildRouteMapInfoFocus(
    routeModel: RouteModel,
    focusPoint: GeoPoint,
    widthMeters: Double,
    config: TileContextConfig = DefaultTileContextConfig,
): MapInfoFocus {
    return MapInfoFocus(
        centerGeoPoint = focusPoint,
        windowWidthMeters = widthMeters,
        projectedBounds = nearbyWayFocusBounds(
            routeModel = routeModel,
            focusGeoPoint = focusPoint,
            focusWindowWidthMeters = widthMeters,
            haloMeters = config.wayHaloMeters,
            continuationMeters = config.nearbyWayContinuationMeters,
        ) ?: routeModel.bounds,
    )
}

internal fun sampleRoutePointsWithinBounds(
    geoPoints: List<GeoPoint>,
    bounds: GeoBounds,
    maxSamples: Int,
): List<GeoPoint> {
    val pointsWithinBounds = geoPoints.filter { point ->
        point.lat in bounds.south..bounds.north &&
            point.lon in bounds.west..bounds.east
    }
    require(pointsWithinBounds.isNotEmpty()) {
        "Expected route fixture to intersect tile bounds"
    }
    if (pointsWithinBounds.size <= maxSamples) {
        return pointsWithinBounds
    }
    val lastIndex = pointsWithinBounds.lastIndex.toDouble()
    return (0 until maxSamples).map { sampleIndex ->
        val pointIndex = kotlin.math.round((sampleIndex * lastIndex) / (maxSamples - 1)).toInt()
        pointsWithinBounds[pointIndex]
    }.distinct()
}

internal inline fun <T> withRouteMapInfoRepository(
    sourcePack: TileContextPack,
    block: (TileContextRepository) -> T,
): T {
    return withSeededTileContextRepository(
        prefix = "geepee-route-map-info-test",
        sourcePack = sourcePack,
        block = block,
    )
}

internal inline fun <T> withTileContextRepository(
    prefix: String = "geepee-tile-context-test",
    block: (TileContextRepository) -> T,
): T {
    val cacheRoot = Files.createTempDirectory(prefix).toFile()
    try {
        return block(TileContextRepository(cacheRoot))
    } finally {
        cacheRoot.deleteRecursively()
    }
}

internal inline fun <T> withTileContextRepositoryRoot(
    prefix: String = "geepee-tile-context-test",
    block: (File, TileContextRepository) -> T,
): T {
    val cacheRoot = Files.createTempDirectory(prefix).toFile()
    try {
        return block(cacheRoot, TileContextRepository(cacheRoot))
    } finally {
        cacheRoot.deleteRecursively()
    }
}

internal inline fun <T> withSeededTileContextRepository(
    prefix: String = "geepee-tile-context-test",
    sourcePack: TileContextPack,
    block: (TileContextRepository) -> T,
): T {
    return withTileContextRepository(prefix = prefix) { repository ->
        repository.storeTilePack(sourcePack)
        block(repository)
    }
}

internal fun loadGpxGeoPointsFixture(path: String): List<GeoPoint> {
    val resourceStream = requireNotNull(RouteMapInfoFixture::class.java.classLoader?.getResourceAsStream(path)) {
        "Missing GPX fixture resource: $path"
    }
    resourceStream.use { stream ->
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(stream)
        val trackPoints = document.getElementsByTagNameNS("*", "trkpt")
        return buildList(trackPoints.length) {
            for (index in 0 until trackPoints.length) {
                val node = trackPoints.item(index)
                val attributes = node.attributes
                add(
                    GeoPoint(
                        lat = attributes.getNamedItem("lat").nodeValue.toDouble(),
                        lon = attributes.getNamedItem("lon").nodeValue.toDouble(),
                    ),
                )
            }
        }
    }
}

internal fun benchmarkRouteMapInfoNanos(
    iterations: Int,
    block: () -> Unit,
): Long {
    return benchmarkNanos(iterations = iterations, block = block)
}

internal fun requireRouteMapInfoBenchmarkOptIn() {
    val propertyEnabled = System.getProperty("geepee.runBenchmarks")?.toBooleanStrictOrNull() == true
    val envEnabled = System.getenv("GEEPEE_RUN_BENCHMARKS")?.toBooleanStrictOrNull() == true
    assumeTrue(
        "Benchmark tests are opt-in. Set -Dgeepee.runBenchmarks=true or GEEPEE_RUN_BENCHMARKS=true to run them.",
        propertyEnabled || envEnabled,
    )
}

internal fun awaitRouteMapInfoNearbyWayRebuild(
    coordinator: RouteContextCoordinator,
    routeModel: RouteModel,
    analysis: RouteAnalysis,
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
    focus: MapInfoFocus,
    force: Boolean,
): RouteMapInfoState {
    val latch = CountDownLatch(1)
    var result: RouteMapInfoState? = null
    coordinator.rebuildNearbyWays(
        routeModel = routeModel,
        analysis = analysis,
        tileDownloads = tileDownloads,
        existingLocalStatus = null,
        focus = focus,
        defaultFocusWindowWidthMeters = focus.windowWidthMeters,
        force = force,
        onStarted = {},
        onResult = { rebuilt ->
            result = rebuilt
            if (rebuilt.localNearbyWays?.nearbyWaysLoading != true) {
                latch.countDown()
            }
        },
    )
    assumeTrue("Timed out waiting for nearby-way rebuild", latch.await(5, TimeUnit.SECONDS))
    return requireNotNull(result)
}

internal fun awaitRouteMapInfoRouteContextRebuild(
    coordinator: RouteContextCoordinator,
    routeModel: RouteModel,
): List<RoutePoi> {
    val latch = CountDownLatch(1)
    var result: List<RoutePoi>? = null
    coordinator.rebuildRouteContext(routeModel) { rebuilt ->
        result = rebuilt
        latch.countDown()
    }
    assumeTrue("Timed out waiting for route-context rebuild", latch.await(5, TimeUnit.SECONDS))
    return requireNotNull(result)
}

internal fun assertNearbyWaySnippetsEquivalent(
    expected: List<RouteNearbyWaySnippet>,
    actual: List<RouteNearbyWaySnippet>,
    tolerance: Double,
) {
    assertEquals(
        expected.map(RouteNearbyWaySnippet::featureId),
        actual.map(RouteNearbyWaySnippet::featureId),
    )
    expected.zip(actual).forEach { (expectedWay, actualWay) ->
        assertEquals(expectedWay.points.size, actualWay.points.size)
        expectedWay.points.zip(actualWay.points).forEach { (expectedPoint, actualPoint) ->
            assertEquals(expectedPoint.x, actualPoint.x, tolerance)
            assertEquals(expectedPoint.y, actualPoint.y, tolerance)
        }
    }
}
