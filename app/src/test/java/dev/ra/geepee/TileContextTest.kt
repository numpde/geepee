package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TileContextTest {
    @Test
    fun tilesForRouteUsesConfiguredZoom() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val tileIds = tilesForRoute(
            routeModel = route,
            config = TileContextConfig(downloadZoom = 10),
        )

        assertTrue(tileIds.isNotEmpty())
        assertTrue(tileIds.all { it.zoom == 10 })
    }

    @Test
    fun tileGridRenderModelMarksRouteIntersectingTiles() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            routeTileMetricsById = buildRouteTileMetricsIndex(
                routeModel = route,
                config = TileContextConfig(downloadZoom = 10),
            ),
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = TileContextConfig(downloadZoom = 10),
            tileSnapshots = emptyMap(),
        )

        assertTrue(renderModel.tiles.isNotEmpty())
        assertTrue(renderModel.tiles.count { it.routeMetrics.intersectsRoute } >= 2)
        assertTrue(renderModel.tiles.all { it.label != null })
    }

    @Test
    fun tileGridHitTestingReturnsVisibleTile() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            routeTileMetricsById = buildRouteTileMetricsIndex(
                routeModel = route,
                config = TileContextConfig(downloadZoom = 10),
            ),
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = TileContextConfig(downloadZoom = 10),
            tileSnapshots = emptyMap(),
        )
        val firstTile = renderModel.tiles.first()
        val hitTile = renderModel.tileAt(
            ScreenPoint(
                x = firstTile.screenRect.left + firstTile.screenRect.width / 2f,
                y = firstTile.screenRect.top + firstTile.screenRect.height / 2f,
            ),
        )

        assertNotNull(hitTile)
        assertEquals(firstTile.tileId, hitTile?.tileId)
    }

    @Test
    fun tileGridRenderModelCanFilterToFullyVisibleTiles() {
        val metrics = TileRouteMetrics(
            intersectsRoute = false,
            intersectingEdgeCount = 0,
            intersectingRouteMeters = 0.0,
        )
        val model = TileGridRenderModel(
            tiles = listOf(
                TileGridDisplayTile(
                    tileId = DownloadTileId(zoom = 10, x = 1, y = 1),
                    screenRect = ScreenRect(left = 10f, top = 20f, right = 110f, bottom = 120f),
                    routeMetrics = metrics,
                    snapshot = null,
                    estimatedBytes = 0L,
                    label = null,
                ),
                TileGridDisplayTile(
                    tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
                    screenRect = ScreenRect(left = -5f, top = 20f, right = 95f, bottom = 120f),
                    routeMetrics = metrics,
                    snapshot = null,
                    estimatedBytes = 0L,
                    label = null,
                ),
            ),
        )

        val filtered = model.fullyVisibleWithin(width = 200f, height = 200f)

        assertEquals(1, filtered.tiles.size)
        assertEquals("10/1/1", filtered.tiles.single().tileId.cacheKey)
    }

    @Test
    fun buildRouteTileMetricsIndexContainsOnlyIntersectingTiles() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val metricsByTile = buildRouteTileMetricsIndex(
            routeModel = route,
            config = TileContextConfig(downloadZoom = 10),
        )

        assertTrue(metricsByTile.isNotEmpty())
        assertTrue(metricsByTile.values.all { it.intersectsRoute })
    }

    @Test
    fun normalizeOverpassTilePackKeepsOnlyMinimalSupportedFeatures() {
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 512, y = 512),
            config = DefaultTileContextConfig,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 100,
                      "tags": {
                        "highway": "cycleway",
                        "surface": "asphalt",
                        "lanes": "2",
                        "name": "River Path"
                      },
                      "geometry": [
                        {"lat": 0.0, "lon": 0.0},
                        {"lat": 0.0, "lon": 0.001}
                      ]
                    },
                    {
                      "type": "node",
                      "id": 200,
                      "lat": 0.0004,
                      "lon": 0.0004,
                      "tags": {
                        "amenity": "drinking_water",
                        "name": "Pump",
                        "opening_hours": "24/7"
                      }
                    },
                    {
                      "type": "relation",
                      "id": 300,
                      "tags": {"tourism": "picnic_site"}
                    }
                  ]
                }
            """.trimIndent(),
            fetchedAtMillis = 1234L,
        )

        assertEquals(2, pack.features.size)
        val way = pack.features.first { it.featureId == "way/100" }
        val point = pack.features.first { it.featureId == "node/200" }

        assertEquals(TileGeometryKind.Way, way.geometryKind)
        assertEquals("cycleway", way.tags["highway"])
        assertEquals("asphalt", way.tags["surface"])
        assertFalse(way.tags.containsKey("lanes"))

        assertEquals(TileGeometryKind.Point, point.geometryKind)
        assertEquals("drinking_water", point.tags["amenity"])
        assertEquals("Pump", point.tags["name"])
        assertFalse(point.tags.containsKey("opening_hours"))
        assertEquals(1234L, pack.fetchedAtMillis)
    }

    @Test
    fun normalizeOverpassTilePackIgnoresNullGeometryEntries() {
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 571, y = 357),
            config = DefaultTileContextConfig,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 101,
                      "tags": {
                        "highway": "path"
                      },
                      "geometry": [
                        {"lat": -1.0, "lon": 36.8},
                        null,
                        {"lat": -1.0005, "lon": 36.8005}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            fetchedAtMillis = 4321L,
        )

        assertEquals(1, pack.features.size)
        assertEquals(2, pack.features.single().geometry.size)
    }

    @Test
    fun tileContextPackJsonRoundTrips() {
        val original = TileContextPack(
            tileId = DownloadTileId(zoom = 10, x = 512, y = 512),
            queryBounds = GeoBounds(west = 36.8, south = -1.3, east = 36.9, north = -1.2),
            fetchedAtMillis = 999L,
            features = listOf(
                TileContextFeature(
                    featureId = "node/1",
                    geometryKind = TileGeometryKind.Point,
                    tags = mapOf("amenity" to "toilets"),
                    geometry = listOf(GeoPoint(lat = -1.25, lon = 36.85)),
                ),
            ),
        )

        val restored = tileContextPackFromJson(original.toJsonString())

        assertEquals(original, restored)
    }

    @Test
    fun tileDownloadCancellationRunsCallbacksAndThrowsOnCheck() {
        val cancellation = TileDownloadCancellation()
        var callbackCount = 0

        cancellation.onCancel { callbackCount += 1 }
        cancellation.cancel()
        cancellation.onCancel { callbackCount += 1 }

        assertEquals(2, callbackCount)
        assertThrows(TileDownloadCancelledException::class.java) {
            cancellation.throwIfCancelled()
        }
    }
}
