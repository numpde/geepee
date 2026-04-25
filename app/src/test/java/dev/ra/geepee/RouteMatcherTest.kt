package dev.ra.geepee

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val TISZA_START_HAIRPIN_POINT_INDICES = listOf(35, 38, 40, 42, 45, 48, 50, 52, 55)
private const val TISZA_START_HAIRPIN_PRE_APEX_INDEX = 40
private const val TISZA_START_HAIRPIN_APEX_INDEX = 45
private const val TISZA_START_HAIRPIN_RETURN_INDEX = 50

class RouteMatcherTest {
    @Test
    fun matcherKeepsHairpinProgressOnCurrentLegDespiteNearerReturnLeg() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0010, lon = 0.0),
                    GeoPoint(lat = 0.0010, lon = 0.00008),
                    GeoPoint(lat = 0.0, lon = 0.00008),
                ),
            ),
        )
        val matcher = RouteMatcher(route)

        val firstFix = LocationFix(
            lat = 0.00025,
            lon = 0.000005,
            accuracyMeters = 5f,
            headingDegrees = 0f,
            speedMetersPerSecond = 4f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val firstMatch = matcher.match(firstFix)

        val ambiguousFix = LocationFix(
            lat = 0.00055,
            lon = 0.000055,
            accuracyMeters = 5f,
            headingDegrees = 0f,
            speedMetersPerSecond = 4f,
            timestampMillis = 3_000L,
            bearingAccuracyDegrees = 8f,
        )
        val rawNearest = analyzeLocationAgainstModel(
            model = route,
            fix = ambiguousFix,
            previousNearestEdgeIndex = firstMatch.nearestEdgeIndex,
        )
        val matched = matcher.match(ambiguousFix)

        assertNotEquals(rawNearest.nearestEdgeIndex, matched.nearestEdgeIndex)
        assertTrue(matched.nearestEdgeIndex <= firstMatch.nearestEdgeIndex)
        assertTrue(matched.routeMeters > firstMatch.routeMeters)
        assertTrue(matched.routeMeters < rawNearest.routeMeters)
    }

    @Test
    fun matcherSwitchesToReturnLegAfterTopConnector() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0010, lon = 0.0),
                    GeoPoint(lat = 0.0010, lon = 0.00008),
                    GeoPoint(lat = 0.0, lon = 0.00008),
                ),
            ),
        )
        val matcher = RouteMatcher(route)

        val firstLegFix = LocationFix(
            lat = 0.00082,
            lon = 0.000004,
            accuracyMeters = 5f,
            headingDegrees = 0f,
            speedMetersPerSecond = 4f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val connectorFix = LocationFix(
            lat = 0.00101,
            lon = 0.00004,
            accuracyMeters = 5f,
            headingDegrees = 90f,
            speedMetersPerSecond = 3f,
            timestampMillis = 3_000L,
            bearingAccuracyDegrees = 10f,
        )
        val returnLegFix = LocationFix(
            lat = 0.00076,
            lon = 0.000078,
            accuracyMeters = 5f,
            headingDegrees = 180f,
            speedMetersPerSecond = 4f,
            timestampMillis = 5_000L,
            bearingAccuracyDegrees = 8f,
        )

        val match1 = matcher.match(firstLegFix)
        val match2 = matcher.match(connectorFix)
        val match3 = matcher.match(returnLegFix)

        assertTrue(match2.routeMeters > match1.routeMeters)
        assertTrue(match3.routeMeters > match2.routeMeters)
        assertTrue(match3.nearestEdgeIndex > match1.nearestEdgeIndex)
    }

    @Test
    fun matcherBreaksContinuityWhenMatchedPointFallsFarOffFix() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0015, lon = 0.0),
                    GeoPoint(lat = 0.0015, lon = 0.00036),
                    GeoPoint(lat = 0.0, lon = 0.00036),
                ),
            ),
        )
        val matcher = RouteMatcher(route)

        val firstFix = LocationFix(
            lat = 0.00030,
            lon = 0.0,
            accuracyMeters = 5f,
            headingDegrees = 0f,
            speedMetersPerSecond = 3f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val secondFix = LocationFix(
            lat = 0.00032,
            lon = 0.00036,
            accuracyMeters = 5f,
            headingDegrees = 180f,
            speedMetersPerSecond = 3f,
            timestampMillis = 3_000L,
            bearingAccuracyDegrees = 8f,
        )

        val firstMatch = matcher.match(firstFix)
        val rawNearest = analyzeLocationAgainstModel(
            model = route,
            fix = secondFix,
            previousNearestEdgeIndex = firstMatch.nearestEdgeIndex,
        )
        val secondMatch = matcher.match(secondFix)

        assertEquals(rawNearest.nearestEdgeIndex, secondMatch.nearestEdgeIndex)
        assertTrue(secondMatch.offRouteMeters <= rawNearest.offRouteMeters + 0.5)
        assertTrue(secondMatch.nearestEdgeIndex > firstMatch.nearestEdgeIndex)
    }

    @Test
    fun matcherSwitchesToNearbyParallelBranchWhenContinuityLagsBehind() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0040, lon = 0.0),
                    GeoPoint(lat = 0.0040, lon = 0.00025),
                    GeoPoint(lat = 0.0, lon = 0.00025),
                ),
            ),
        )
        val matcher = RouteMatcher(route)

        val firstFix = LocationFix(
            lat = 0.0022,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 0f,
            speedMetersPerSecond = 2.2f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val secondFix = LocationFix(
            lat = 0.0021,
            lon = 0.00025,
            accuracyMeters = 4f,
            headingDegrees = 180f,
            speedMetersPerSecond = 2.2f,
            timestampMillis = 2_000L,
            bearingAccuracyDegrees = 8f,
        )

        val firstMatch = matcher.match(firstFix)
        val rawNearest = analyzeLocationAgainstModel(
            model = route,
            fix = secondFix,
            previousNearestEdgeIndex = firstMatch.nearestEdgeIndex,
        )
        val secondMatch = matcher.match(secondFix)

        assertEquals(rawNearest.nearestEdgeIndex, secondMatch.nearestEdgeIndex)
        assertTrue(secondMatch.offRouteMeters < 8.0)
        assertTrue(secondMatch.routeMeters > firstMatch.routeMeters + 300.0)
    }

    @Test
    fun trimmingStillKeepsRawNearestCandidateAvailableForContinuityBreak() {
        val meterToLon = 1.0 / 111_111.0
        val segments = (0..14).map { index ->
            val lon = index * 0.5 * meterToLon
            listOf(
                GeoPoint(lat = 0.0, lon = lon),
                GeoPoint(lat = 0.00018, lon = lon),
            )
        }
        val route = buildRouteModel(segments)
        val matcher = RouteMatcher(
            route,
            config = RouteMatcherConfig(
                maxCandidatesPerFix = 3,
                minSigmaMeters = 1.0,
                preliminaryContinuityScaleMeters = 1.0,
                continuityBreakDistanceMeters = 2.5,
                continuityBreakGapMeters = 0.3,
                continuityBreakNearestMeters = 0.8,
                continuityBreakAccuracyMultiplier = 1.0,
            ),
        )

        val firstFix = LocationFix(
            lat = 0.00009,
            lon = 0.0,
            accuracyMeters = 3f,
            headingDegrees = 0f,
            speedMetersPerSecond = 1.5f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val secondFix = LocationFix(
            lat = 0.00009,
            lon = 14 * 0.5 * meterToLon,
            accuracyMeters = 3f,
            headingDegrees = 0f,
            speedMetersPerSecond = 1.5f,
            timestampMillis = 2_000L,
            bearingAccuracyDegrees = 8f,
        )

        val firstMatch = matcher.match(firstFix)
        val rawNearest = analyzeLocationAgainstModel(
            model = route,
            fix = secondFix,
            previousNearestEdgeIndex = firstMatch.nearestEdgeIndex,
        )
        val secondMatch = matcher.match(secondFix)

        assertEquals(rawNearest.nearestEdgeIndex, secondMatch.nearestEdgeIndex)
        assertTrue(secondMatch.offRouteMeters < 0.5)
        assertTrue(secondMatch.routeMeters > firstMatch.routeMeters + 50.0)
    }

    @Test
    fun matcherKeepsEquivalentLoopStartMatchesAliveUntilDirectionDisambiguates() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0015, lon = 0.0),
                    GeoPoint(lat = 0.0015, lon = 0.0015),
                    GeoPoint(lat = 0.0, lon = 0.0015),
                    GeoPoint(lat = 0.0, lon = 0.0),
                ),
            ),
        )
        val matcher = RouteMatcher(route)

        val ambiguousStartFix = LocationFix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 0f,
            speedMetersPerSecond = 2.5f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 8f,
        )
        val firstForwardFix = LocationFix(
            lat = 0.00035,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 0f,
            speedMetersPerSecond = 2.5f,
            timestampMillis = 3_000L,
            bearingAccuracyDegrees = 8f,
        )
        val secondForwardFix = LocationFix(
            lat = 0.00070,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 0f,
            speedMetersPerSecond = 2.5f,
            timestampMillis = 5_000L,
            bearingAccuracyDegrees = 8f,
        )

        val startMatch = matcher.match(ambiguousStartFix)
        val firstForwardMatch = matcher.match(firstForwardFix)
        val secondForwardMatch = matcher.match(secondForwardFix)

        assertTrue(startMatch.offRouteMeters < 1.0)
        assertTrue(firstForwardMatch.offRouteMeters < 2.0)
        assertTrue(secondForwardMatch.offRouteMeters < 2.0)
        assertTrue(firstForwardMatch.routeMeters < route.totalLengthMeters / 2.0)
        assertTrue(secondForwardMatch.routeMeters < route.totalLengthMeters / 2.0)
        assertTrue(secondForwardMatch.routeMeters > firstForwardMatch.routeMeters)
    }

    @Test
    fun matcherHandlesRealTiszaStartHairpinNearOverlap() {
        val fixture = loadTiszaFixture()
        val nearOverlapMeters = distanceBetweenGeoPoints(
            fixture.geoPoints[TISZA_START_HAIRPIN_PRE_APEX_INDEX],
            fixture.geoPoints[TISZA_START_HAIRPIN_RETURN_INDEX],
        )
        assertTrue("Start hairpin should revisit almost the same place.", nearOverlapMeters < 8.0)

        assertHairpinRun(
            run = fixture.runTiszaStartHairpin { pointIndex, timestampMillis ->
                fixture.fixAt(index = pointIndex, timestampMillis = timestampMillis)
            },
            preApexToleranceMeters = 14.0,
            returnToleranceMeters = 14.0,
            apexSlackMeters = 4.0,
            monotonicSlackMeters = 0.5,
            message = "Matcher should keep progressing through the real start hairpin.",
        )
    }

    @Test
    fun matcherHandlesNoisyRealTiszaStartHairpin() {
        val fixture = loadTiszaFixture()
        val noiseSource = Random(17)

        assertHairpinRun(
            run = fixture.runTiszaStartHairpin { pointIndex, timestampMillis ->
                fixture.noisyFixAt(
                    index = pointIndex,
                    timestampMillis = timestampMillis,
                    random = noiseSource,
                    sigmaMeters = 3.0,
                )
            },
            preApexToleranceMeters = 20.0,
            returnToleranceMeters = 20.0,
            apexSlackMeters = 8.0,
            monotonicSlackMeters = 1.0,
            message = "Matcher should stay monotonic through noisy hairpin fixes.",
        )
    }

    @Test
    fun matcherHandlesStressNoisyRealTiszaStartHairpinWithOutliers() {
        val fixture = loadTiszaFixture()
        val noiseSource = Random(71)
        val outlierOffsetsByIndex = mapOf(
            42 to OffsetMeters(east = 8.0, north = -10.0),
            45 to OffsetMeters(east = 14.0, north = 6.0),
            48 to OffsetMeters(east = -10.0, north = 8.0),
        )

        assertHairpinRun(
            run = fixture.runTiszaStartHairpin { pointIndex, timestampMillis ->
                fixture.noisyFixAt(
                    index = pointIndex,
                    timestampMillis = timestampMillis,
                    random = noiseSource,
                    sigmaMeters = 5.0,
                    extraOffsetMeters = outlierOffsetsByIndex[pointIndex],
                )
            },
            preApexToleranceMeters = 28.0,
            returnToleranceMeters = 28.0,
            apexSlackMeters = 12.0,
            monotonicSlackMeters = 3.0,
            message = "Matcher should not jump backward materially even with outlier hairpin fixes.",
        )
    }
}

private data class RouteFixture(
    val geoPoints: List<GeoPoint>,
    val routeModel: RouteModel,
    val routeMetersByIndex: List<Double>,
)

private data class HairpinRun(
    val fixture: RouteFixture,
    val matchesByPointIndex: Map<Int, RouteAnalysis>,
) {
    val orderedMatches: List<RouteAnalysis>
        get() = TISZA_START_HAIRPIN_POINT_INDICES.map(matchesByPointIndex::getValue)

    val preApexMatch: RouteAnalysis
        get() = matchesByPointIndex.getValue(TISZA_START_HAIRPIN_PRE_APEX_INDEX)

    val apexMatch: RouteAnalysis
        get() = matchesByPointIndex.getValue(TISZA_START_HAIRPIN_APEX_INDEX)

    val returnLegMatch: RouteAnalysis
        get() = matchesByPointIndex.getValue(TISZA_START_HAIRPIN_RETURN_INDEX)
}

private data class OffsetMeters(
    val east: Double,
    val north: Double,
)

private fun loadTiszaFixture(): RouteFixture {
    val routeFile = resolveRepoFile("routes/unneplos-tisza-ride.gpx")
    // `GpxParser` depends on android.util.Xml, which is unavailable in plain JVM unit tests.
    // Keep parsing here minimal, then hand the result to `buildRouteModel()` as the source of
    // truth for all route geometry.
    val document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(routeFile)
    val trackPoints = document.getElementsByTagNameNS("*", "trkpt")
    val geoPoints = buildList(trackPoints.length) {
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
    val routeModel = buildRouteModel(listOf(geoPoints))

    require(geoPoints.size >= 2) { "Expected at least two GPX points in the Tisza fixture." }

    return RouteFixture(
        geoPoints = geoPoints,
        routeModel = routeModel,
        routeMetersByIndex = routeModel.segments
            .flatMap { segment ->
                segment.cumulativeMeters.map { segment.offsetMeters + it }
            },
    )
}

private fun RouteFixture.runTiszaStartHairpin(
    buildFix: (pointIndex: Int, timestampMillis: Long) -> LocationFix,
): HairpinRun {
    val matcher = RouteMatcher(routeModel)
    val matchesByPointIndex = buildMap {
        TISZA_START_HAIRPIN_POINT_INDICES.forEachIndexed { step, pointIndex ->
            put(
                pointIndex,
                matcher.match(
                    buildFix(
                        pointIndex,
                        1_000L + (step * 2_000L),
                    ),
                ),
            )
        }
    }
    return HairpinRun(
        fixture = this,
        matchesByPointIndex = matchesByPointIndex,
    )
}

private fun RouteFixture.fixAt(
    index: Int,
    timestampMillis: Long,
): LocationFix {
    val point = geoPoints[index]
    val previousPoint = geoPoints[maxOf(0, index - 1)]
    val nextPoint = geoPoints[minOf(geoPoints.lastIndex, index + 1)]
    return LocationFix(
        lat = point.lat,
        lon = point.lon,
        accuracyMeters = 4f,
        headingDegrees = bearingDegrees(previousPoint, nextPoint).toFloat(),
        speedMetersPerSecond = 4f,
        timestampMillis = timestampMillis,
        bearingAccuracyDegrees = 8f,
    )
}

private fun RouteFixture.noisyFixAt(
    index: Int,
    timestampMillis: Long,
    random: Random,
    sigmaMeters: Double,
    extraOffsetMeters: OffsetMeters? = null,
): LocationFix {
    val point = geoPoints[index]
    val previousPoint = geoPoints[maxOf(0, index - 1)]
    val nextPoint = geoPoints[minOf(geoPoints.lastIndex, index + 1)]
    val noisyPoint = point.offsetByMeters(
        eastMeters = gaussianMeters(random, sigmaMeters) + (extraOffsetMeters?.east ?: 0.0),
        northMeters = gaussianMeters(random, sigmaMeters) + (extraOffsetMeters?.north ?: 0.0),
    )
    return LocationFix(
        lat = noisyPoint.lat,
        lon = noisyPoint.lon,
        accuracyMeters = maxOf(
            4f,
            (sigmaMeters * 1.5).toFloat(),
            (((extraOffsetMeters?.let { hypot(it.east, it.north) } ?: 0.0) + sigmaMeters) * 0.9).toFloat(),
        ),
        headingDegrees = bearingDegrees(previousPoint, nextPoint).toFloat(),
        speedMetersPerSecond = 4f,
        timestampMillis = timestampMillis,
        bearingAccuracyDegrees = 10f,
    )
}

private fun resolveRepoFile(relativePath: String): File {
    val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
        "Expected a working directory for repo fixture lookup."
    }
    var current: File? = File(workingDirectory).absoluteFile
    repeat(8) {
        val candidate = current?.resolve(relativePath)
        if (candidate?.isFile == true) {
            return candidate
        }
        current = current?.parentFile
    }
    fail("Could not locate repo file: $relativePath from $workingDirectory")
    error("unreachable")
}

private fun distanceBetweenGeoPoints(start: GeoPoint, end: GeoPoint): Double {
    val startLatRadians = Math.toRadians(start.lat)
    val endLatRadians = Math.toRadians(end.lat)
    val deltaX = Math.toRadians(end.lon - start.lon) * cos((startLatRadians + endLatRadians) / 2.0)
    val deltaY = endLatRadians - startLatRadians
    return hypot(deltaX, deltaY) * 6_371_000.0
}

private fun GeoPoint.offsetByMeters(
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

private fun bearingDegrees(start: GeoPoint, end: GeoPoint): Double {
    val deltaLatRadians = Math.toRadians(end.lat - start.lat)
    val deltaLonRadians = Math.toRadians(end.lon - start.lon)
    return normalizeDegrees(Math.toDegrees(atan2(deltaLonRadians, deltaLatRadians)))
}

private fun gaussianMeters(
    random: Random,
    sigmaMeters: Double,
): Double {
    val u1 = random.nextDouble().coerceAtLeast(1e-12)
    val u2 = random.nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
        kotlin.math.cos(2.0 * Math.PI * u2) * sigmaMeters
}

private fun normalizeDegrees(degrees: Double): Double {
    return ((degrees % 360.0) + 360.0) % 360.0
}

private fun assertHairpinRun(
    run: HairpinRun,
    preApexToleranceMeters: Double,
    returnToleranceMeters: Double,
    apexSlackMeters: Double,
    monotonicSlackMeters: Double,
    message: String,
) {
    assertWithinMeters(
        expectedMeters = run.fixture.routeMetersByIndex[TISZA_START_HAIRPIN_PRE_APEX_INDEX],
        actualMeters = run.preApexMatch.routeMeters,
        toleranceMeters = preApexToleranceMeters,
    )
    assertWithinMeters(
        expectedMeters = run.fixture.routeMetersByIndex[TISZA_START_HAIRPIN_RETURN_INDEX],
        actualMeters = run.returnLegMatch.routeMeters,
        toleranceMeters = returnToleranceMeters,
    )
    assertTrue(
        run.preApexMatch.routeMeters < run.fixture.routeMetersByIndex[TISZA_START_HAIRPIN_APEX_INDEX] + apexSlackMeters,
    )
    assertTrue(
        run.returnLegMatch.routeMeters > run.fixture.routeMetersByIndex[TISZA_START_HAIRPIN_APEX_INDEX] - apexSlackMeters,
    )
    assertTrue(run.returnLegMatch.routeMeters > run.apexMatch.routeMeters)
    assertTrue(
        message,
        run.orderedMatches.zipWithNext().all { (left, right) ->
            right.routeMeters + monotonicSlackMeters >= left.routeMeters
        },
    )
}

private fun assertWithinMeters(
    expectedMeters: Double,
    actualMeters: Double,
    toleranceMeters: Double,
) {
    assertTrue(
        "Expected $actualMeters to be within $toleranceMeters m of $expectedMeters",
        abs(actualMeters - expectedMeters) <= toleranceMeters,
    )
}
