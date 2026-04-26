package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTileOverlayTest {
    @Test
    fun routeTileOverlayMatchesDirectRouteContextForSyntheticTile() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val sourcePack = syntheticOverlayTilePack()
        val runtimePack = compileTileRuntimePack(sourcePack)

        val directContext = buildRouteContext(
            routeModel = routeModel,
            packs = listOf(sourcePack),
            config = DefaultTileContextConfig,
        )
        val overlay = buildRouteTileOverlay(
            routeModel = routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )

        assertRouteContextsEquivalent(directContext, overlay.context)
        assertEquals(listOf("node/1", "node/2"), overlay.context.pois.map(RoutePoi::featureId))
        assertEquals(listOf("way/branch"), overlay.context.nearbyWays.map(RouteNearbyWaySnippet::featureId))
    }

    @Test
    fun routeTileOverlayRoundTripsThroughBinaryCodec() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val overlay = buildRouteTileOverlay(
            routeModel = routeModel,
            runtimePack = compileTileRuntimePack(syntheticOverlayTilePack()),
            config = DefaultTileContextConfig,
        )

        val restored = routeTileOverlayFromByteArray(routeTileOverlayToByteArray(overlay))

        assertEquals(overlay, restored)
    }

    @Test
    fun routeTileOverlayIndexesPoisAndNearbyWaysIntoLeafEntries() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val overlay = buildRouteTileOverlay(
            routeModel = routeModel,
            runtimePack = compileTileRuntimePack(syntheticOverlayTilePack()),
            config = DefaultTileContextConfig,
        )

        assertTrue(overlay.leafEntries.isNotEmpty())
        assertTrue(overlay.leafEntries.any { it.poiIndexes.isNotEmpty() })
        assertTrue(overlay.leafEntries.any { it.nearbyWayIndexes.isNotEmpty() })

        val indexedPoiIds = overlay.leafEntries
            .flatMap(RouteTileOverlayLeafEntry::poiIndexes)
            .distinct()
            .sorted()
        val indexedNearbyWayIds = overlay.leafEntries
            .flatMap(RouteTileOverlayLeafEntry::nearbyWayIndexes)
            .distinct()
            .sorted()

        assertEquals(overlay.context.pois.indices.toList(), indexedPoiIds)
        assertEquals(overlay.context.nearbyWays.indices.toList(), indexedNearbyWayIds)
    }

    @Test
    fun routeTileOverlayLeafQueryMatchesFocusedNearbyWayContextForSyntheticTile() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val sourcePack = syntheticOverlayTilePack()
        val runtimePack = compileTileRuntimePack(sourcePack)
        val overlay = buildRouteTileOverlay(
            routeModel = routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )
        val bundle = RouteTileOverlayBundle(runtimePack = runtimePack, overlay = overlay)
        val focusPoint = GeoPoint(lat = 0.0, lon = 0.005)

        val directNearbyWays = buildRouteNearbyWays(
            routeModel = routeModel,
            packs = listOf(sourcePack),
            config = DefaultTileContextConfig,
            focusGeoPoint = focusPoint,
            focusWindowWidthMeters = 250.0,
        )
        val overlayNearbyWays = queryRouteTileOverlayNearbyWays(
            routeModel = routeModel,
            bundle = bundle,
            focusGeoPoint = focusPoint,
            focusWindowWidthMeters = 250.0,
            config = DefaultTileContextConfig,
        )

        assertNearbyWaysEquivalent(directNearbyWays, overlayNearbyWays, tolerance = 0.05)
    }

    @Test
    fun runtimePackLeafQueryMatchesFocusedNearbyWayContextForSyntheticTile() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val sourcePack = syntheticOverlayTilePack()
        val runtimePack = compileTileRuntimePack(sourcePack)
        val focusPoint = GeoPoint(lat = 0.0, lon = 0.005)
        val focusNearestEdgeIndex = collectRouteCandidates(
            model = routeModel,
            projectedFix = projectGeoPointToRouteProjection(focusPoint, routeModel.projection),
        ).minByOrNull(RouteAnalysis::offRouteMeters)?.nearestEdgeIndex ?: -1

        val directNearbyWays = buildRouteNearbyWays(
            routeModel = routeModel,
            packs = listOf(sourcePack),
            config = DefaultTileContextConfig,
            focusGeoPoint = focusPoint,
            focusWindowWidthMeters = 250.0,
        )
        val runtimeNearbyWays = queryTileRuntimeNearbyWays(
            routeModel = routeModel,
            runtimePack = runtimePack,
            focusGeoPoint = focusPoint,
            focusNearestEdgeIndex = focusNearestEdgeIndex,
            focusWindowWidthMeters = 250.0,
            config = DefaultTileContextConfig,
        )

        assertNearbyWaysEquivalent(directNearbyWays, runtimeNearbyWays, tolerance = 0.05)
    }

    private fun syntheticOverlayTilePack(): TileContextPack {
        return TileContextPack(
            tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
            queryBounds = GeoBounds(west = 0.0, south = 0.0, east = 0.01, north = 0.01),
            fetchedAtMillis = 0L,
            features = listOf(
                TileContextFeature(
                    featureId = "node/1",
                    geometryKind = TileGeometryKind.Point,
                    tags = mapOf("amenity" to "drinking_water", "name" to "Pump"),
                    geometry = listOf(GeoPoint(lat = 0.0001, lon = 0.003)),
                ),
                TileContextFeature(
                    featureId = "node/2",
                    geometryKind = TileGeometryKind.Point,
                    tags = mapOf("amenity" to "toilets"),
                    geometry = listOf(GeoPoint(lat = 0.0, lon = 0.007)),
                ),
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

    private fun assertRouteContextsEquivalent(
        expected: RouteContext,
        actual: RouteContext,
    ) {
        assertEquals(expected.pois.map(RoutePoi::featureId), actual.pois.map(RoutePoi::featureId))
        expected.pois.zip(actual.pois).forEach { (expectedPoi, actualPoi) ->
            assertEquals(expectedPoi.kind, actualPoi.kind)
            assertEquals(expectedPoi.name, actualPoi.name)
            assertEquals(expectedPoi.geoPoint.lat, actualPoi.geoPoint.lat, 1e-4)
            assertEquals(expectedPoi.geoPoint.lon, actualPoi.geoPoint.lon, 1e-4)
            assertEquals(expectedPoi.projectedPoint.x, actualPoi.projectedPoint.x, 0.05)
            assertEquals(expectedPoi.projectedPoint.y, actualPoi.projectedPoint.y, 0.05)
        }

        assertEquals(
            expected.nearbyWays.map(RouteNearbyWaySnippet::featureId),
            actual.nearbyWays.map(RouteNearbyWaySnippet::featureId),
        )
        expected.nearbyWays.zip(actual.nearbyWays).forEach { (expectedWay, actualWay) ->
            assertEquals(expectedWay.points.size, actualWay.points.size)
            expectedWay.points.zip(actualWay.points).forEach { (expectedPoint, actualPoint) ->
                assertEquals(expectedPoint.x, actualPoint.x, 0.05)
                assertEquals(expectedPoint.y, actualPoint.y, 0.05)
            }
            assertEquals(expectedWay.bounds.minX, actualWay.bounds.minX, 0.05)
            assertEquals(expectedWay.bounds.maxX, actualWay.bounds.maxX, 0.05)
            assertEquals(expectedWay.bounds.minY, actualWay.bounds.minY, 0.05)
            assertEquals(expectedWay.bounds.maxY, actualWay.bounds.maxY, 0.05)
        }
    }

    private fun assertNearbyWaysEquivalent(
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
}
