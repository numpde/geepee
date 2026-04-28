package dev.ra.geepee

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class RouteMapInfoFixture(
    val sourcePack: TileContextPack,
    val routeModel: RouteModel,
    val geoPoints: List<GeoPoint>,
)

internal data class RouteMapInfoRouteFixture(
    val geoPoints: List<GeoPoint>,
    val routeModel: RouteModel,
    val routeMetersByIndex: List<Double>,
)

internal fun loadRouteMapInfoFixture(
    tileFixturePath: String = "tile-context/10-571-356-local.json",
): RouteMapInfoFixture {
    val routeFixture = loadRouteMapInfoRouteFixture()
    return RouteMapInfoFixture(
        sourcePack = loadRouteMapInfoTileFixture(tileFixturePath),
        geoPoints = routeFixture.geoPoints,
        routeModel = routeFixture.routeModel,
    )
}

internal fun loadRouteMapInfoRouteFixture(): RouteMapInfoRouteFixture {
    val geoPoints = loadRouteMapInfoGeoPoints()
    val routeModel = buildRouteModel(listOf(geoPoints))
    require(geoPoints.size >= 2) { "Expected at least two GPX points in the Tisza fixture." }
    return RouteMapInfoRouteFixture(
        geoPoints = geoPoints,
        routeModel = routeModel,
        routeMetersByIndex = buildRouteMetersByIndex(routeModel),
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

internal fun buildRouteMetersByIndex(routeModel: RouteModel): List<Double> {
    return routeModel.segments.flatMap { segment ->
        segment.cumulativeMeters.map { segment.offsetMeters + it }
    }
}

internal fun buildRouteFixtureLocationFix(
    geoPoints: List<GeoPoint>,
    index: Int,
    timestampMillis: Long,
    accuracyMeters: Float = 4f,
    speedMetersPerSecond: Float = 4f,
    bearingAccuracyDegrees: Float = 8f,
): LocationFix {
    val point = geoPoints[index]
    val previousPoint = geoPoints[maxOf(0, index - 1)]
    val nextPoint = geoPoints[minOf(geoPoints.lastIndex, index + 1)]
    return LocationFix(
        lat = point.lat,
        lon = point.lon,
        accuracyMeters = accuracyMeters,
        headingDegrees = bearingDegreesBetweenGeoPoints(previousPoint, nextPoint).toFloat(),
        speedMetersPerSecond = speedMetersPerSecond,
        timestampMillis = timestampMillis,
        bearingAccuracyDegrees = bearingAccuracyDegrees,
    )
}

internal fun distanceBetweenGeoPointsMeters(start: GeoPoint, end: GeoPoint): Double {
    val startLatRadians = Math.toRadians(start.lat)
    val endLatRadians = Math.toRadians(end.lat)
    val deltaX = Math.toRadians(end.lon - start.lon) * cos((startLatRadians + endLatRadians) / 2.0)
    val deltaY = endLatRadians - startLatRadians
    return hypot(deltaX, deltaY) * 6_371_000.0
}

internal fun GeoPoint.offsetByMeters(
    eastMeters: Double,
    northMeters: Double,
): GeoPoint {
    val latRadians = Math.toRadians(lat)
    val deltaLat = Math.toDegrees(northMeters / 6_371_000.0)
    val deltaLon = Math.toDegrees(eastMeters / (6_371_000.0 * cos(latRadians)))
    return GeoPoint(
        lat = lat + deltaLat,
        lon = lon + deltaLon,
    )
}

internal fun bearingDegreesBetweenGeoPoints(start: GeoPoint, end: GeoPoint): Double {
    val startLat = Math.toRadians(start.lat)
    val endLat = Math.toRadians(end.lat)
    val deltaLon = Math.toRadians(end.lon - start.lon)
    val y = sin(deltaLon) * cos(endLat)
    val x = cos(startLat) * sin(endLat) - sin(startLat) * cos(endLat) * cos(deltaLon)
    val bearing = Math.toDegrees(atan2(y, x))
    return ((bearing % 360.0) + 360.0) % 360.0
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
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
): List<RoutePoi> {
    val latch = CountDownLatch(1)
    var result: List<RoutePoi>? = null
    coordinator.rebuildRouteContext(
        routeModel = routeModel,
        tileDownloads = tileDownloads,
    ) { rebuilt ->
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
