package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteContextPipelineTest {
    @Test
    fun nearbyWayPipelineCarriesBranchFromOverpassJsonToVisibleScreenPolyline() {
        val routeModel = straightRouteModel()
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
            config = DefaultTileContextConfig,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 100,
                      "tags": {
                        "highway": "path"
                      },
                      "geometry": [
                        {"lat": 0.0, "lon": 0.005},
                        {"lat": 0.0003, "lon": 0.005},
                        {"lat": 0.0006, "lon": 0.005}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        )

        val context = buildRouteContext(
            routeModel = routeModel,
            packs = listOf(pack),
            config = DefaultTileContextConfig,
        )
        val render = buildRouteRenderModel(
            routeModel = routeModel,
            analysis = analysisAt(routeModel, lon = 0.005),
            nearbyWays = context.nearbyWays,
            localWindowWidthMeters = 220.0,
            canvasWidth = 1080f,
            canvasHeight = 1920f,
        )

        assertEquals(listOf("way/100"), context.nearbyWays.map(RouteNearbyWaySnippet::featureId))
        assertEquals(1, render.nearbyWayPolylines.size)
        assertTrue(render.nearbyWayPolylines.single().size >= 2)

        val polyline = render.nearbyWayPolylines.single()
        val xSpan = polyline.maxOf { it.x } - polyline.minOf { it.x }
        val ySpan = polyline.maxOf { it.y } - polyline.minOf { it.y }

        assertTrue("Expected nearby branch to occupy visible screen height", ySpan > 40f)
        assertTrue("Expected nearby branch to stay mostly vertical in this synthetic setup", ySpan > xSpan * 2f)
    }

    @Test
    fun nearbyWayPipelineDropsOnRouteDuplicateBeforeRendering() {
        val routeModel = straightRouteModel()
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
            config = DefaultTileContextConfig,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 200,
                      "tags": {
                        "highway": "cycleway"
                      },
                      "geometry": [
                        {"lat": 0.0, "lon": 0.003},
                        {"lat": 0.0, "lon": 0.007}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        )

        val context = buildRouteContext(
            routeModel = routeModel,
            packs = listOf(pack),
            config = DefaultTileContextConfig,
        )
        val render = buildRouteRenderModel(
            routeModel = routeModel,
            analysis = analysisAt(routeModel, lon = 0.005),
            nearbyWays = context.nearbyWays,
            localWindowWidthMeters = 220.0,
            canvasWidth = 1080f,
            canvasHeight = 1920f,
        )

        assertTrue(context.nearbyWays.isEmpty())
        assertTrue(render.nearbyWayPolylines.isEmpty())
    }

    @Test
    fun nearbyWayPipelineDropsShallowBranchBelowAngleThreshold() {
        val routeModel = straightRouteModel()
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
            config = DefaultTileContextConfig,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 300,
                      "tags": {
                        "highway": "service"
                      },
                      "geometry": [
                        {"lat": 0.0, "lon": 0.005},
                        {"lat": 0.00008, "lon": 0.0058},
                        {"lat": 0.00015, "lon": 0.0064}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        )

        val context = buildRouteContext(
            routeModel = routeModel,
            packs = listOf(pack),
            config = DefaultTileContextConfig,
        )
        val render = buildRouteRenderModel(
            routeModel = routeModel,
            analysis = analysisAt(routeModel, lon = 0.005),
            nearbyWays = context.nearbyWays,
            localWindowWidthMeters = 220.0,
            canvasWidth = 1080f,
            canvasHeight = 1920f,
        )

        assertTrue(context.nearbyWays.isEmpty())
        assertTrue(render.nearbyWayPolylines.isEmpty())
    }

    @Test
    fun nearbyWayPipelineCanExtendBranchPastInitialHalo() {
        val routeModel = straightRouteModel()
        val config = TileContextConfig(
            wayHaloMeters = 40.0,
            nearbyWayContinuationMeters = 180.0,
        )
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 0, y = 0),
            config = config,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 400,
                      "tags": {
                        "highway": "path"
                      },
                      "geometry": [
                        {"lat": 0.0, "lon": 0.005},
                        {"lat": 0.0006, "lon": 0.005},
                        {"lat": 0.0012, "lon": 0.005}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        )

        val context = buildRouteContext(
            routeModel = routeModel,
            packs = listOf(pack),
            config = config,
        )

        assertEquals(1, context.nearbyWays.size)
        val snippet = context.nearbyWays.single()
        assertTrue(
            "Expected snippet to continue well beyond the initial 40 m halo once it branches from the route",
            snippet.bounds.maxY - snippet.bounds.minY > 100.0,
        )
    }

    private fun straightRouteModel(): RouteModel {
        return buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
    }

    private fun analysisAt(
        routeModel: RouteModel,
        lon: Double,
    ): RouteAnalysis {
        return analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = lon,
                accuracyMeters = null,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
    }
}
