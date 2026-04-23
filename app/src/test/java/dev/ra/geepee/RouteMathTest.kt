package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMathTest {
    @Test
    fun simpleRouteAnalysisFindsNearestPointAndProgress() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.001),
                ),
            ),
        )

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.0001,
                lon = 0.0005,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        assertTrue(analysis.offRouteMeters in 9.0..13.5)
        assertEquals(route.totalLengthMeters / 2.0, analysis.progressMeters, 3.0)
        assertEquals(route.totalLengthMeters / 2.0, analysis.remainingMeters, 3.0)
    }

    @Test
    fun hintedNearestEdgeSearchMatchesFullSearchOnLargeRoute() {
        val routePoints = (0..2200).map { index ->
            GeoPoint(
                lat = 0.0 + index * 0.00001,
                lon = 0.0 + kotlin.math.sin(index / 90.0) * 0.00008,
            )
        }
        val route = buildRouteModel(listOf(routePoints))

        val previousFix = LocationFix(
            lat = routePoints[1200].lat + 0.00003,
            lon = routePoints[1200].lon,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 0L,
        )
        val previousAnalysis = analyzeLocationAgainstModel(route, previousFix)

        val nextFix = LocationFix(
            lat = routePoints[1210].lat + 0.00003,
            lon = routePoints[1210].lon + 0.000005,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 1L,
        )
        val fullSearch = analyzeLocationAgainstModel(route, nextFix)
        val hintedSearch = analyzeLocationAgainstModel(
            model = route,
            fix = nextFix,
            previousNearestEdgeIndex = previousAnalysis.nearestEdgeIndex,
        )

        assertEquals(fullSearch.nearestEdgeIndex, hintedSearch.nearestEdgeIndex)
        assertEquals(fullSearch.routeMeters, hintedSearch.routeMeters, 0.001)
        assertEquals(fullSearch.offRouteMeters, hintedSearch.offRouteMeters, 0.001)
    }

    @Test
    fun spatialIndexBeatsWrongRouteOrderHintWhenRouteReturnsNearby() {
        val earlyBranch = listOf(
            GeoPoint(lat = 0.0, lon = 0.0),
            GeoPoint(lat = 0.001, lon = 0.001),
        )
        val detour = (1..450).map { index ->
            GeoPoint(
                lat = 0.01 + index * 0.00001,
                lon = 0.02 + index * 0.00001,
            )
        }
        val lateBranch = listOf(
            GeoPoint(lat = 0.001, lon = 0.0),
            GeoPoint(lat = 0.0, lon = 0.001),
        )
        val route = buildRouteModel(listOf(earlyBranch + detour + lateBranch))

        val previousFix = LocationFix(
            lat = 0.0002,
            lon = 0.0002,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 0L,
        )
        val previousAnalysis = analyzeLocationAgainstModel(route, previousFix)

        val nextFix = LocationFix(
            lat = 0.0008,
            lon = 0.0002,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 1L,
        )
        val fullSearch = analyzeLocationAgainstModel(route, nextFix)
        val hintedSearch = analyzeLocationAgainstModel(
            model = route,
            fix = nextFix,
            previousNearestEdgeIndex = previousAnalysis.nearestEdgeIndex,
        )

        assertTrue(previousAnalysis.nearestEdgeIndex < 8)
        assertTrue(fullSearch.nearestEdgeIndex > 440)
        assertEquals(fullSearch.nearestEdgeIndex, hintedSearch.nearestEdgeIndex)
        assertEquals(fullSearch.offRouteMeters, hintedSearch.offRouteMeters, 0.001)
    }

    @Test
    fun fallsBackToFullSearchWhenPointIsFarOutsideIndexedCells() {
        val routePoints = (0..2000).map { index ->
            GeoPoint(
                lat = 0.0,
                lon = index * 0.00001,
            )
        }
        val route = buildRouteModel(listOf(routePoints))

        val previousFix = LocationFix(
            lat = 0.0001,
            lon = routePoints[100].lon,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 0L,
        )
        val previousAnalysis = analyzeLocationAgainstModel(route, previousFix)

        val farFix = LocationFix(
            lat = 0.0065,
            lon = routePoints[1500].lon,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 1L,
        )
        val fullSearch = analyzeLocationAgainstModel(route, farFix)
        val hintedSearch = analyzeLocationAgainstModel(
            model = route,
            fix = farFix,
            previousNearestEdgeIndex = previousAnalysis.nearestEdgeIndex,
        )

        assertEquals(fullSearch.nearestEdgeIndex, hintedSearch.nearestEdgeIndex)
        assertEquals(fullSearch.routeMeters, hintedSearch.routeMeters, 0.001)
        assertEquals(fullSearch.offRouteMeters, hintedSearch.offRouteMeters, 0.001)
    }

    @Test
    fun buildRouteModelChunksLongSegmentsForRenderCulling() {
        val routePoints = (0..400).map { index ->
            GeoPoint(
                lat = 0.0,
                lon = index * 0.00001,
            )
        }
        val route = buildRouteModel(listOf(routePoints))

        assertTrue(route.segments.single().renderChunks.size > 1)

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.0001,
                lon = routePoints[240].lon,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = analysis,
            localWindowWidthMeters = 100.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
        )

        assertTrue(render.polylines.isNotEmpty())
    }

    @Test
    fun renderModelUsesEdgeMarkerWhenUserIsOutsideWindow() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.002),
                ),
            ),
        )

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.003,
                lon = 0.001,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = analysis,
            localWindowWidthMeters = 200.0,
            canvasWidth = 1080f,
            canvasHeight = 1920f,
        )

        assertTrue(render.polylines.isNotEmpty())
        assertNotNull(render.nearestPoint)
        assertNull(render.userPoint)
        assertNotNull(render.edgePoint)
    }

    @Test
    fun renderModelBiasesVisibleWindowForwardAlongRoute() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.001),
                    GeoPoint(lat = 0.0, lon = 0.002),
                    GeoPoint(lat = 0.0, lon = 0.003),
                ),
            ),
        )

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.0001,
                lon = 0.0005,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = analysis,
            localWindowWidthMeters = 100.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            lookAheadFraction = 0.22,
        )

        val visiblePolyline = render.polylines.single()
        val minVisibleX = visiblePolyline.minOf { it.x }
        val maxVisibleX = visiblePolyline.maxOf { it.x }
        val nearestX = render.nearestPoint!!.x

        assertFalse(nearestX <= 0f)
        assertTrue(maxVisibleX - nearestX > nearestX - minVisibleX)
    }

    @Test
    fun renderModelKeepsNearestPointCenteredWithoutLookAhead() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.001),
                    GeoPoint(lat = 0.0, lon = 0.002),
                    GeoPoint(lat = 0.0, lon = 0.003),
                ),
            ),
        )

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.0001,
                lon = 0.0005,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = analysis,
            localWindowWidthMeters = 100.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            lookAheadFraction = 0.0,
        )

        val visiblePolyline = render.polylines.single()
        val minVisibleX = visiblePolyline.minOf { it.x }
        val maxVisibleX = visiblePolyline.maxOf { it.x }
        val nearestX = render.nearestPoint!!.x

        assertTrue(kotlin.math.abs((maxVisibleX - nearestX) - (nearestX - minVisibleX)) < 0.01f)
    }

    @Test
    fun renderModelAppliesScreenRotationForCourseUp() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.002),
                ),
            ),
        )

        val unrotated = buildRouteRenderModel(
            routeModel = route,
            analysis = null,
            localWindowWidthMeters = 200.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            rotationDegrees = 0f,
        )
        val rotated = buildRouteRenderModel(
            routeModel = route,
            analysis = null,
            localWindowWidthMeters = 200.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            rotationDegrees = 90f,
        )

        val unrotatedPolyline = unrotated.polylines.single()
        val rotatedPolyline = rotated.polylines.single()

        val unrotatedYSpan = unrotatedPolyline.maxOf { it.y } - unrotatedPolyline.minOf { it.y }
        val rotatedXSpan = rotatedPolyline.maxOf { it.x } - rotatedPolyline.minOf { it.x }
        val rotatedYSpan = rotatedPolyline.maxOf { it.y } - rotatedPolyline.minOf { it.y }

        assertTrue(unrotatedYSpan < 0.01f)
        assertTrue(rotatedXSpan < 0.01f)
        assertTrue(rotatedYSpan > 100f)
    }

    @Test
    fun renderModelProjectsVisibleHistoryPoints() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.002),
                ),
            ),
        )

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.0001,
                lon = 0.001,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = analysis,
            historyPoints = listOf(
                ProjectedPoint(analysis.point.x - 20.0, analysis.point.y),
                analysis.point,
            ),
            localWindowWidthMeters = 200.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
        )

        assertEquals(2, render.historyPoints.size)
    }

    @Test
    fun routeSpatialIndexCoversEveryEdge() {
        val route = buildRouteModel(
            listOf(
                (0..300).map { index ->
                    GeoPoint(
                        lat = index * 0.00001,
                        lon = kotlin.math.sin(index / 15.0) * 0.0002,
                    )
                },
            ),
        )

        val indexedEdges = route.spatialIndex.cells.values
            .flatMap { cellEdges -> cellEdges.toList() }
            .toSet()

        assertEquals(route.edges.indices.toSet(), indexedEdges)
    }
}
