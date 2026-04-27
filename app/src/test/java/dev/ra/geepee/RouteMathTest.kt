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

    @Test(timeout = 1000L)
    fun farOutsideSpatialExtentDoesNotRingScanAcrossTheWorld() {
        val routePoints = (0..2200).map { index ->
            GeoPoint(
                lat = 48.0,
                lon = 22.0 + index * 0.00001,
            )
        }
        val route = buildRouteModel(listOf(routePoints))

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = -1.286389,
                lon = 36.817223,
                accuracyMeters = 12f,
                headingDegrees = 135f,
                speedMetersPerSecond = 0f,
                timestampMillis = 0L,
                bearingAccuracyDegrees = 20f,
            ),
        )

        assertTrue(analysis.nearestEdgeIndex in 0..route.edges.lastIndex)
        assertTrue(analysis.offRouteMeters > 1_000_000.0)
    }

    @Test
    fun multiHintLocalSearchUsesWindowUnionInsteadOfSpanningWholeRoute() {
        val routePoints = (0..2200).map { index ->
            GeoPoint(
                lat = 0.0,
                lon = index * 0.00001,
            )
        }
        val route = buildRouteModel(listOf(routePoints))
        val projectedFix = projectLocationFix(
            model = route,
            fix = LocationFix(
                lat = 0.0,
                lon = routePoints[25].lon,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val candidates = collectRouteCandidates(
            model = route,
            projectedFix = projectedFix,
            previousNearestEdgeIndexes = listOf(25, route.edges.lastIndex - 25),
        )

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.size < 600)
        assertTrue(candidates.any { it.nearestEdgeIndex < 250 })
        assertTrue(candidates.any { it.nearestEdgeIndex > route.edges.lastIndex - 250 })
        assertFalse(candidates.any { it.nearestEdgeIndex in 800..1400 })
    }

    @Test
    fun spatialIndexSearchDoesNotStopAtFirstOccupiedRingWhenCloserEdgeIsNextCellOver() {
        val meterToDegrees = 1.0 / 111_111.0
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 20.0 * meterToDegrees),
                    GeoPoint(lat = 120.0 * meterToDegrees, lon = 20.0 * meterToDegrees),
                ),
                listOf(
                    GeoPoint(lat = 0.0, lon = 161.0 * meterToDegrees),
                    GeoPoint(lat = 120.0 * meterToDegrees, lon = 161.0 * meterToDegrees),
                ),
            ),
        )

        val analysis = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 60.0 * meterToDegrees,
                lon = 159.0 * meterToDegrees,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        assertEquals(1, analysis.nearestEdgeIndex)
        assertTrue(analysis.offRouteMeters < 5.0)
    }

    @Test
    fun renderModelProjectsRoutePoisAtTheirActualLocation() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val context = buildRouteContext(
            routeModel = routeModel,
            packs = listOf(
                TileContextPack(
                    tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
                    queryBounds = GeoBounds(west = 0.0, south = 0.0, east = 0.01, north = 0.01),
                    fetchedAtMillis = 0L,
                    features = listOf(
                        TileContextFeature(
                            featureId = "node/water",
                            geometryKind = TileGeometryKind.Point,
                            tags = mapOf("amenity" to "drinking_water"),
                            geometry = listOf(GeoPoint(lat = 0.0005, lon = 0.005)),
                        ),
                    ),
                ),
            ),
            config = DefaultTileContextConfig,
        )

        val render = buildRouteRenderModel(
            routeModel = routeModel,
            analysis = analyzeLocationAgainstModel(
                model = routeModel,
                fix = LocationFix(
                    lat = 0.0,
                    lon = 0.005,
                    accuracyMeters = 5f,
                    headingDegrees = null,
                    speedMetersPerSecond = null,
                    timestampMillis = 0L,
                ),
            ),
            pois = context.pois,
            localWindowWidthMeters = 220.0,
            canvasWidth = 1080f,
            canvasHeight = 1920f,
        )

        assertEquals(1, render.poiMarkers.size)
        val marker = render.poiMarkers.single()
        assertEquals("node/water", marker.featureId)
        assertEquals(RoutePoiKind.DrinkingWater, marker.kind)
        assertEquals(GeoPoint(lat = 0.0005, lon = 0.005), marker.geoPoint)
        assertTrue(marker.point.y < 960f)
    }

    @Test
    fun routePoiMarkersNearScreenPointReturnsAllNearbyMarkers() {
        val selected = routePoiMarkersNearScreenPoint(
            markers = listOf(
                RoutePoiScreenMarker(
                    featureId = "node/water",
                    kind = RoutePoiKind.DrinkingWater,
                    name = "Pump",
                    geoPoint = GeoPoint(lat = 0.0, lon = 0.0),
                    point = ScreenPoint(x = 100f, y = 100f),
                ),
                RoutePoiScreenMarker(
                    featureId = "node/toilets",
                    kind = RoutePoiKind.Toilets,
                    name = null,
                    geoPoint = GeoPoint(lat = 0.0, lon = 0.0),
                    point = ScreenPoint(x = 118f, y = 104f),
                ),
                RoutePoiScreenMarker(
                    featureId = "node/far",
                    kind = RoutePoiKind.Shelter,
                    name = null,
                    geoPoint = GeoPoint(lat = 0.0, lon = 0.0),
                    point = ScreenPoint(x = 180f, y = 180f),
                ),
            ),
            tap = ScreenPoint(x = 108f, y = 102f),
            maxDistancePx = 24f,
        )

        assertEquals(listOf("node/water", "node/toilets"), selected.map(RoutePoiScreenMarker::featureId))
    }

    @Test
    fun spatialIndexSearchKeepsEqualDistanceAdjacentCellCandidatesAlive() {
        val meterToDegrees = 1.0 / 111_111.0
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 158.0 * meterToDegrees),
                    GeoPoint(lat = 120.0 * meterToDegrees, lon = 158.0 * meterToDegrees),
                ),
                listOf(
                    GeoPoint(lat = 0.0, lon = 160.0 * meterToDegrees),
                    GeoPoint(lat = 120.0 * meterToDegrees, lon = 160.0 * meterToDegrees),
                ),
            ),
        )
        val projectedFix = projectLocationFix(
            model = route,
            fix = LocationFix(
                lat = 60.0 * meterToDegrees,
                lon = 159.0 * meterToDegrees,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val candidates = collectRouteCandidates(
            model = route,
            projectedFix = projectedFix,
        )

        assertTrue(candidates.any { it.nearestEdgeIndex == 0 && it.offRouteMeters < 2.0 })
        assertTrue(candidates.any { it.nearestEdgeIndex == 1 && it.offRouteMeters < 2.0 })
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
            includeGradientPolylines = true,
        )

        assertTrue(render.polylines.isEmpty())
        assertTrue(render.gradientPolylines.isNotEmpty())
    }

    @Test
    fun gradientPolylineUsesExactProgressForClippedVisibleSection() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.001),
                    GeoPoint(lat = 0.0, lon = 0.002),
                    GeoPoint(lat = 0.0, lon = 0.003),
                    GeoPoint(lat = 0.0, lon = 0.004),
                ),
            ),
        )

        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = null,
            localWindowWidthMeters = 100.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            includeGradientPolylines = true,
            boundsOverride = route.segments.single().points.let { points ->
                val minX = points.minOf { it.x }
                val maxX = points.maxOf { it.x }
                val minY = points.minOf { it.y }
                val maxY = points.maxOf { it.y }
                Bounds(
                    minX = minX + (maxX - minX) * 0.25,
                    maxX = minX + (maxX - minX) * 0.75,
                    minY = minY - 1.0,
                    maxY = maxY + 1.0,
                )
            },
        )

        val polyline = render.gradientPolylines.single()
        val first = polyline.points.first()
        val last = polyline.points.last()

        assertTrue(first.progressRatio > 0.2f)
        assertTrue(first.progressRatio < 0.4f)
        assertTrue(last.progressRatio > 0.6f)
        assertTrue(last.progressRatio < 0.8f)
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
    fun renderModelProjectsConfidenceHypothesesIntoScreenSpace() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.004),
                ),
            ),
        )
        val primary = analyzeLocationAgainstModel(
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
        val alternate = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.0001,
                lon = 0.003,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = primary,
            matchHypotheses = listOf(
                RouteMatchDisplayHypothesis(primary, confidence = 0.65f, isPrimary = true),
                RouteMatchDisplayHypothesis(alternate, confidence = 0.35f, isPrimary = false),
            ),
            localWindowWidthMeters = 600.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
        )

        assertEquals(2, render.hypothesisPoints.size)
        assertEquals(0f, render.nearestPointUncertainty, 0.001f)
        assertTrue(render.hypothesisPoints.first().isPrimary)
        assertTrue(render.hypothesisPoints.first().confidence > render.hypothesisPoints.last().confidence)
    }

    @Test
    fun renderModelSuppressesInsignificantAmbiguityMarkers() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.004),
                ),
            ),
        )
        val primary = analyzeLocationAgainstModel(
            model = route,
            fix = LocationFix(
                lat = 0.0001,
                lon = 0.0015,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val nearlySame = primary.copy(
            nearestPoint = ProjectedPoint(
                x = primary.nearestPoint.x + 2.0,
                y = primary.nearestPoint.y,
            ),
        )

        val render = buildRouteRenderModel(
            routeModel = route,
            analysis = primary,
            matchHypotheses = listOf(
                RouteMatchDisplayHypothesis(primary, confidence = 0.88f, isPrimary = true),
                RouteMatchDisplayHypothesis(nearlySame, confidence = 0.12f, isPrimary = false),
            ),
            localWindowWidthMeters = 600.0,
            canvasWidth = 1000f,
            canvasHeight = 1000f,
        )

        assertTrue(render.hypothesisPoints.isEmpty())
        assertTrue(render.nearestPointUncertainty > 0.1f)
    }

    @Test
    fun createRouteViewportFitsContentBoundsToCanvasAspect() {
        val contentBounds = Bounds(
            minX = -40.0,
            maxX = 60.0,
            minY = -20.0,
            maxY = 180.0,
        )

        val viewport = createRouteViewport(
            contentBounds = contentBounds,
            canvasWidth = 1080.0,
            canvasHeight = 1920.0,
        )
        val viewportBounds = routeViewportBounds(
            viewport = viewport,
            canvasWidth = 1080.0,
            canvasHeight = 1920.0,
        )

        assertTrue(viewportBounds.minX <= contentBounds.minX)
        assertTrue(viewportBounds.maxX >= contentBounds.maxX)
        assertTrue(viewportBounds.minY <= contentBounds.minY)
        assertTrue(viewportBounds.maxY >= contentBounds.maxY)
        assertEquals(
            1080.0 / 1920.0,
            (viewportBounds.maxX - viewportBounds.minX) / (viewportBounds.maxY - viewportBounds.minY),
            0.0001,
        )
    }

    @Test
    fun transformRouteViewportZoomsAroundCenteredCentroid() {
        val contentBounds = Bounds(
            minX = -100.0,
            maxX = 100.0,
            minY = -80.0,
            maxY = 80.0,
        )
        val viewport = createRouteViewport(
            contentBounds = contentBounds,
            canvasWidth = 1000.0,
            canvasHeight = 1000.0,
        )

        val transformed = transformRouteViewport(
            viewport = viewport,
            contentBounds = contentBounds,
            canvasWidth = 1000.0,
            canvasHeight = 1000.0,
            centroid = ScreenPoint(500f, 500f),
            pan = ScreenPoint(0f, 0f),
            zoomChange = 2f,
        )

        assertEquals(viewport.centerX, transformed.centerX, 0.001)
        assertEquals(viewport.centerY, transformed.centerY, 0.001)
        assertEquals(viewport.widthMeters / 2.0, transformed.widthMeters, 0.001)
    }

    @Test
    fun transformRouteViewportPansWithFingerAndClampsInsideContent() {
        val contentBounds = Bounds(
            minX = 0.0,
            maxX = 400.0,
            minY = 0.0,
            maxY = 400.0,
        )
        val zoomedViewport = RouteViewport(
            centerX = 200.0,
            centerY = 200.0,
            widthMeters = 160.0,
        )

        val panned = transformRouteViewport(
            viewport = zoomedViewport,
            contentBounds = contentBounds,
            canvasWidth = 1000.0,
            canvasHeight = 1000.0,
            centroid = ScreenPoint(500f, 500f),
            pan = ScreenPoint(150f, 120f),
            zoomChange = 1f,
        )
        val clamped = transformRouteViewport(
            viewport = zoomedViewport,
            contentBounds = contentBounds,
            canvasWidth = 1000.0,
            canvasHeight = 1000.0,
            centroid = ScreenPoint(500f, 500f),
            pan = ScreenPoint(-4_000f, -4_000f),
            zoomChange = 1f,
        )

        assertTrue(panned.centerX < zoomedViewport.centerX)
        assertTrue(panned.centerY > zoomedViewport.centerY)
        assertEquals(320.0, clamped.centerX, 0.001)
        assertEquals(80.0, clamped.centerY, 0.001)
    }

    @Test
    fun transformRouteViewportRespectsRotationAroundCentroid() {
        val contentBounds = Bounds(
            minX = -200.0,
            maxX = 200.0,
            minY = -200.0,
            maxY = 200.0,
        )
        val viewport = RouteViewport(
            centerX = 0.0,
            centerY = 0.0,
            widthMeters = 120.0,
        )
        val centroid = ScreenPoint(300f, 180f)
        val pan = ScreenPoint(75f, -45f)
        val rotationDegrees = 90.0
        val canvasWidth = 1000.0
        val canvasHeight = 1000.0

        val rotatedPan = rotateScreenVectorForTest(pan, angleDegrees = -rotationDegrees)
        val rotatedCentroid = rotateScreenPointForTest(
            point = centroid,
            center = ScreenPoint((canvasWidth / 2.0).toFloat(), (canvasHeight / 2.0).toFloat()),
            rotationDegrees = -rotationDegrees,
        )
        val rotatedTransform = transformRouteViewport(
            viewport = viewport,
            contentBounds = contentBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            centroid = centroid,
            pan = pan,
            zoomChange = 1f,
            rotationDegrees = rotationDegrees,
        )
        val equivalentUnrotatedTransform = transformRouteViewport(
            viewport = viewport,
            contentBounds = contentBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            centroid = rotatedCentroid,
            pan = rotatedPan,
            zoomChange = 1f,
        )

        assertEquals(equivalentUnrotatedTransform.centerX, rotatedTransform.centerX, 0.001)
        assertEquals(equivalentUnrotatedTransform.centerY, rotatedTransform.centerY, 0.001)
        assertEquals(equivalentUnrotatedTransform.widthMeters, rotatedTransform.widthMeters, 0.001)
    }

    @Test
    fun transformRouteViewportRotationMatchesExpectedVectorDirection() {
        val contentBounds = Bounds(
            minX = 0.0,
            maxX = 500.0,
            minY = 0.0,
            maxY = 500.0,
        )
        val viewport = RouteViewport(
            centerX = 250.0,
            centerY = 250.0,
            widthMeters = 200.0,
        )
        val centroid = ScreenPoint(500f, 500f)
        val rotatedTransform = transformRouteViewport(
            viewport = viewport,
            contentBounds = contentBounds,
            canvasWidth = 1000.0,
            canvasHeight = 1000.0,
            centroid = centroid,
            pan = ScreenPoint(80f, 40f),
            zoomChange = 1f,
            rotationDegrees = 90.0,
        )
        val noRotationTransform = transformRouteViewport(
            viewport = viewport,
            contentBounds = contentBounds,
            canvasWidth = 1000.0,
            canvasHeight = 1000.0,
            centroid = centroid,
            pan = rotateScreenVectorForTest(ScreenPoint(80f, 40f), angleDegrees = -90.0),
            zoomChange = 1f,
        )

        assertEquals(noRotationTransform.centerX, rotatedTransform.centerX, 0.001)
        assertEquals(noRotationTransform.centerY, rotatedTransform.centerY, 0.001)
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

    private fun rotateScreenPointForTest(
        point: ScreenPoint,
        center: ScreenPoint,
        rotationDegrees: Double,
    ): ScreenPoint {
        if (rotationDegrees == 0.0) {
            return point
        }
        val radians = Math.toRadians(rotationDegrees)
        val dx = point.x - center.x
        val dy = point.y - center.y
        return ScreenPoint(
            x = (center.x + (dx * kotlin.math.cos(radians) - dy * kotlin.math.sin(radians))).toFloat(),
            y = (center.y + (dx * kotlin.math.sin(radians) + dy * kotlin.math.cos(radians))).toFloat(),
        )
    }

    private fun rotateScreenVectorForTest(
        pan: ScreenPoint,
        angleDegrees: Double,
    ): ScreenPoint {
        if (angleDegrees == 0.0) {
            return pan
        }
        val radians = Math.toRadians(angleDegrees)
        val rotatedX = pan.x * kotlin.math.cos(radians) - pan.y * kotlin.math.sin(radians)
        val rotatedY = pan.x * kotlin.math.sin(radians) + pan.y * kotlin.math.cos(radians)
        return ScreenPoint(rotatedX.toFloat(), rotatedY.toFloat())
    }
}
