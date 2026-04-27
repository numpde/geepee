package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteContextTest {
    @Test
    fun routeContextStoresPoisAndNearbyWaysIndependently() {
        assertEquals(1, RouteContext(pois = listOf(dummyPoi())).pois.size)
        assertEquals(1, RouteContext(nearbyWays = listOf(dummyNearbyWay())).nearbyWays.size)
        assertTrue(RouteContext().pois.isEmpty())
        assertTrue(RouteContext().nearbyWays.isEmpty())
    }

    @Test
    fun buildRouteContextKeepsNearbyPoisAndDedupesAcrossTilePacks() {
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
                tilePack(
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
                ),
                tilePack(
                    TileContextFeature(
                        featureId = "node/1",
                        geometryKind = TileGeometryKind.Point,
                        tags = mapOf("amenity" to "drinking_water", "name" to "Pump"),
                        geometry = listOf(GeoPoint(lat = 0.0004, lon = 0.003)),
                    ),
                    TileContextFeature(
                        featureId = "node/3",
                        geometryKind = TileGeometryKind.Point,
                        tags = mapOf("tourism" to "picnic_site"),
                        geometry = listOf(GeoPoint(lat = 0.003, lon = 0.005)),
                    ),
                ),
            ),
            config = DefaultTileContextConfig,
        )

        assertEquals(2, context.pois.size)
        assertEquals(listOf("node/1", "node/2"), context.pois.map(RoutePoi::featureId))
        assertEquals(0.003, context.pois.first().geoPoint.lon, 0.000001)
        assertFalse(context.pois.first().projectedPoint.x.isNaN())
    }

    @Test
    fun buildRouteContextExtractsNearbyWaySnippetsButSkipsOnRouteDuplicates() {
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
                tilePack(
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
            ),
            config = DefaultTileContextConfig,
        )

        assertEquals(1, context.nearbyWays.size)
        assertEquals("way/branch", context.nearbyWays.single().featureId)
        assertTrue(context.nearbyWays.single().points.size >= 2)
    }

    @Test
    fun routeContextStateKeepsLocalMapInfoTogether() {
        val status = LocalNearbyWayDebugStatus(
            localTileCount = 4,
            loadedLocalTileCount = 2,
            hasVisibleTileData = true,
            nearbyWaysLoading = true,
        )
        val state = RouteContextState()
            .withPois(listOf(dummyPoi()))
            .withMapInfo(
                RouteMapInfoState()
                    .withNearbyWays(listOf(dummyNearbyWay()))
                    .withStatus(status),
            )

        assertEquals(1, state.pois.size)
        assertEquals(1, state.mapInfo.nearbyWays.size)
        assertEquals(status, state.mapInfo.localNearbyWays)
    }

    @Test
    fun routeMapInfoStateClearsNearbyWayResultWithoutDroppingTileStatus() {
        val status = LocalNearbyWayDebugStatus(
            localTileCount = 4,
            loadedLocalTileCount = 2,
            hasVisibleTileData = true,
            nearbyWaysLoading = true,
            nearbyWayCount = 3,
            errorMessage = "Old",
        )

        val cleared = RouteMapInfoState(
            localNearbyWays = status,
            nearbyWays = listOf(dummyNearbyWay()),
        ).clearNearbyWayResult()

        assertTrue(cleared.nearbyWays.isEmpty())
        assertEquals(
            status.copy(
                nearbyWaysLoading = false,
                nearbyWayCount = 0,
                errorMessage = null,
            ),
            cleared.localNearbyWays,
        )
    }

    @Test
    fun routeMapInfoStateCompletesNearbyWayLoadFromResult() {
        val previous = RouteMapInfoState(
            localNearbyWays = LocalNearbyWayDebugStatus(
                localTileCount = 4,
                loadedLocalTileCount = 1,
                hasVisibleTileData = true,
                nearbyWaysLoading = true,
            ),
        )
        val result = RouteMapInfoState(
            localNearbyWays = LocalNearbyWayDebugStatus(
                localTileCount = 4,
                loadedLocalTileCount = 2,
                hasVisibleTileData = true,
                nearbyWaysLoading = true,
                nearbyWayCount = 1,
            ),
            nearbyWays = listOf(dummyNearbyWay()),
        )

        val completed = previous.completeNearbyWayLoad(result)

        assertEquals(listOf(dummyNearbyWay()), completed.nearbyWays)
        assertEquals(
            result.localNearbyWays?.copy(nearbyWaysLoading = false),
            completed.localNearbyWays,
        )
    }

    private fun tilePack(vararg features: TileContextFeature): TileContextPack {
        return TileContextPack(
            tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
            queryBounds = GeoBounds(west = 0.0, south = 0.0, east = 0.01, north = 0.01),
            fetchedAtMillis = 0L,
            features = features.toList(),
        )
    }

    private fun dummyPoi(): RoutePoi {
        return RoutePoi(
            featureId = "node/test",
            kind = RoutePoiKind.DrinkingWater,
            name = null,
            geoPoint = GeoPoint(lat = 0.0, lon = 0.0),
            projectedPoint = ProjectedPoint(0.0, 0.0),
        )
    }

    private fun dummyNearbyWay(): RouteNearbyWaySnippet {
        return RouteNearbyWaySnippet(
            featureId = "way/test",
            points = listOf(
                ProjectedPoint(0.0, 0.0),
                ProjectedPoint(10.0, 0.0),
            ),
            bounds = Bounds(
                minX = 0.0,
                maxX = 10.0,
                minY = 0.0,
                maxY = 0.0,
            ),
        )
    }
}
