package dev.ra.geepee

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val TISZA_START_HAIRPIN_POINT_INDICES = listOf(35, 38, 40, 42, 45, 48, 50, 52, 55)
private const val TISZA_START_HAIRPIN_PRE_APEX_INDEX = 40
private const val TISZA_START_HAIRPIN_APEX_INDEX = 45
private const val TISZA_START_HAIRPIN_RETURN_INDEX = 50

class RouteMatcherTest {
    @Test
    fun matcherReportsHighRouteProbabilityForAccurateOnRouteFix() {
        val route = straightNorthRoute()
        val matcher = RouteMatcher(route)

        val match = matcher.match(
            LocationFix(
                lat = 0.0005,
                lon = 0.0,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 1_000L,
            ),
        )

        assertEquals(RouteAdherence.OnRoute, match.belief.adherence)
        assertTrue(match.belief.routeProbability > 0.8)
        assertTrue(match.belief.offRouteProbability < 0.2)
    }

    @Test
    fun matcherReportsHighOffRouteProbabilityForAccurateFarFix() {
        val route = straightNorthRoute()
        val matcher = RouteMatcher(route)

        val match = matcher.match(
            LocationFix(
                lat = 0.0005,
                lon = 0.0010,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 1_000L,
            ),
        )

        assertEquals(RouteAdherence.OffRoute, match.belief.adherence)
        assertTrue(match.belief.routeProbability < 0.2)
        assertTrue(match.belief.offRouteProbability > 0.8)
    }

    @Test
    fun matcherKeepsRouteCandidateConfidenceSeparateFromRouteAdherence() {
        val route = straightNorthRoute()
        val matcher = RouteMatcher(route)

        val match = matcher.match(
            LocationFix(
                lat = 0.0005,
                lon = 0.0010,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 1_000L,
            ),
        )

        assertEquals(1.0f, match.hypotheses.sumOf { it.confidence.toDouble() }.toFloat(), 0.001f)
        assertTrue(match.belief.routeProbability < 0.2)
    }

    @Test
    fun matcherTreatsPoorAccuracyFarFixAsUncertain() {
        val route = straightNorthRoute()
        val matcher = RouteMatcher(route)

        val match = matcher.match(
            LocationFix(
                lat = 0.0005,
                lon = 0.00055,
                accuracyMeters = 120f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 1_000L,
            ),
        )

        assertEquals(RouteAdherence.Uncertain, match.belief.adherence)
        assertTrue(match.belief.routeProbability in 0.2..0.8)
        assertTrue(match.belief.offRouteProbability in 0.2..0.8)
    }

    @Test
    fun matcherReacquiresRouteAfterAccurateOffRouteFix() {
        val route = straightNorthRoute()
        val matcher = RouteMatcher(route)

        matcher.match(
            LocationFix(
                lat = 0.0002,
                lon = 0.0,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 1_000L,
            ),
        )
        val offRoute = matcher.match(
            LocationFix(
                lat = 0.0004,
                lon = 0.0010,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 3_000L,
            ),
        )
        val reacquired = matcher.match(
            LocationFix(
                lat = 0.0006,
                lon = 0.0,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 5_000L,
            ),
        )

        assertEquals(RouteAdherence.OffRoute, offRoute.belief.adherence)
        assertEquals(RouteAdherence.OnRoute, reacquired.belief.adherence)
        assertTrue(reacquired.belief.routeProbability > 0.8)
    }

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
        val firstMatch = matcher.match(firstFix).analysis

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
        val matchedResult = matcher.match(ambiguousFix)
        val matched = matchedResult.analysis

        assertNotEquals(rawNearest.nearestEdgeIndex, matched.nearestEdgeIndex)
        assertTrue(matched.nearestEdgeIndex <= firstMatch.nearestEdgeIndex)
        assertTrue(matched.routeMeters > firstMatch.routeMeters)
        assertTrue(matched.routeMeters < rawNearest.routeMeters)
        assertEquals(1.0f, matchedResult.hypotheses.sumOf { it.confidence.toDouble() }.toFloat(), 0.001f)
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

        val match1 = matcher.match(firstLegFix).analysis
        val match2 = matcher.match(connectorFix).analysis
        val match3 = matcher.match(returnLegFix).analysis

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

        val firstMatch = matcher.match(firstFix).analysis
        val rawNearest = analyzeLocationAgainstModel(
            model = route,
            fix = secondFix,
            previousNearestEdgeIndex = firstMatch.nearestEdgeIndex,
        )
        val secondMatch = matcher.match(secondFix).analysis

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

        val firstMatch = matcher.match(firstFix).analysis
        val rawNearest = analyzeLocationAgainstModel(
            model = route,
            fix = secondFix,
            previousNearestEdgeIndex = firstMatch.nearestEdgeIndex,
        )
        val secondMatch = matcher.match(secondFix).analysis

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
                beliefConfig = RouteBeliefConfig(minObservationSigmaMeters = 1.0),
                preliminaryContinuityScaleMeters = 1.0,
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

        val firstMatch = matcher.match(firstFix).analysis
        val rawNearest = analyzeLocationAgainstModel(
            model = route,
            fix = secondFix,
            previousNearestEdgeIndex = firstMatch.nearestEdgeIndex,
        )
        val secondMatch = matcher.match(secondFix).analysis

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

        assertTrue(startMatch.analysis.offRouteMeters < 1.0)
        assertEquals(1.0f, startMatch.hypotheses.sumOf { it.confidence.toDouble() }.toFloat(), 0.001f)
        assertTrue(firstForwardMatch.analysis.offRouteMeters < 2.0)
        assertTrue(secondForwardMatch.analysis.offRouteMeters < 2.0)
        assertTrue(firstForwardMatch.analysis.routeMeters < route.totalLengthMeters / 2.0)
        assertTrue(secondForwardMatch.analysis.routeMeters < route.totalLengthMeters / 2.0)
        assertTrue(secondForwardMatch.analysis.routeMeters > firstForwardMatch.analysis.routeMeters)
    }

    @Test
    fun matcherExposesMultipleHypothesesAtFigureEightCrossing() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0010, lon = 0.0010),
                    GeoPoint(lat = 0.0, lon = 0.0010),
                    GeoPoint(lat = 0.0010, lon = 0.0),
                ),
            ),
        )
        val matcher = RouteMatcher(route)

        val crossingFix = LocationFix(
            lat = 0.0005,
            lon = 0.0005,
            accuracyMeters = 4f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 1_000L,
        )

        val match = matcher.match(crossingFix)

        assertTrue(match.analysis.offRouteMeters < 1.0)
        assertTrue(match.hypotheses.size > 1)
        assertEquals(1.0f, match.hypotheses.sumOf { it.confidence.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun matcherHandlesRealTiszaStartHairpinNearOverlap() {
        val fixture = loadRouteMapInfoRouteFixture()
        val nearOverlapMeters = distanceBetweenGeoPointsMeters(
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
        val fixture = loadRouteMapInfoRouteFixture()
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
        val fixture = loadRouteMapInfoRouteFixture()
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

private fun straightNorthRoute(): RouteModel {
    return buildRouteModel(
        listOf(
            listOf(
                GeoPoint(lat = 0.0, lon = 0.0),
                GeoPoint(lat = 0.0010, lon = 0.0),
            ),
        ),
    )
}

private data class HairpinRun(
    val fixture: RouteMapInfoRouteFixture,
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

private fun RouteMapInfoRouteFixture.runTiszaStartHairpin(
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
                ).analysis,
            )
        }
    }
    return HairpinRun(
        fixture = this,
        matchesByPointIndex = matchesByPointIndex,
    )
}

private fun RouteMapInfoRouteFixture.fixAt(
    index: Int,
    timestampMillis: Long,
): LocationFix {
    return buildRouteFixtureLocationFix(
        geoPoints = geoPoints,
        index = index,
        timestampMillis = timestampMillis,
    )
}

private fun RouteMapInfoRouteFixture.noisyFixAt(
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
        headingDegrees = bearingDegreesBetweenGeoPoints(previousPoint, nextPoint).toFloat(),
        speedMetersPerSecond = 4f,
        timestampMillis = timestampMillis,
        bearingAccuracyDegrees = 10f,
    )
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
