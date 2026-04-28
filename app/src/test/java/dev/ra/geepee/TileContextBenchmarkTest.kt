package dev.ra.geepee

import kotlin.math.max
import kotlin.math.min
import org.junit.Test

class TileContextBenchmarkTest {
    @Test
    fun benchmarkTiszaTileOverviewHotPath() {
        requireBenchmarkOptIn()
        val route = loadTiszaRouteModel()
        val config = DefaultTileContextConfig
        val viewportWidth = 1080f
        val viewportHeight = 2340f
        val fullBounds = route.bounds
        val zoomedBounds = shrinkBounds(fullBounds, factor = 0.22)

        repeat(5) {
            buildRouteTileMetricsIndex(route, config)
        }

        val indexNanos = benchmarkNanos(iterations = 20) {
            buildRouteTileMetricsIndex(route, config)
        }
        val routeTileMetricsById = buildRouteTileMetricsIndex(route, config)

        val fullOverviewNanos = benchmarkNanos(iterations = 200) {
            buildTileGridRenderModel(
                routeModel = route,
                routeTileMetricsById = routeTileMetricsById,
                bounds = fullBounds,
                canvasWidth = viewportWidth,
                canvasHeight = viewportHeight,
                config = config,
                tileSnapshots = emptyMap(),
            )
        }
        val fullRouteRenderNanos = benchmarkNanos(iterations = 60) {
            buildRouteRenderModel(
                routeModel = route,
                analysis = null,
                localWindowWidthMeters = RouteScale.Hundred.windowWidthMeters,
                canvasWidth = viewportWidth,
                canvasHeight = viewportHeight,
                includeGradientPolylines = true,
                boundsOverride = fullBounds,
            )
        }
        val zoomedOverviewNanos = benchmarkNanos(iterations = 200) {
            buildTileGridRenderModel(
                routeModel = route,
                routeTileMetricsById = routeTileMetricsById,
                bounds = zoomedBounds,
                canvasWidth = viewportWidth,
                canvasHeight = viewportHeight,
                config = config,
                tileSnapshots = emptyMap(),
            )
        }

        val fullModel = buildTileGridRenderModel(
            routeModel = route,
            routeTileMetricsById = routeTileMetricsById,
            bounds = fullBounds,
            canvasWidth = viewportWidth,
            canvasHeight = viewportHeight,
            config = config,
            tileSnapshots = emptyMap(),
        )
        val zoomedModel = buildTileGridRenderModel(
            routeModel = route,
            routeTileMetricsById = routeTileMetricsById,
            bounds = zoomedBounds,
            canvasWidth = viewportWidth,
            canvasHeight = viewportHeight,
            config = config,
            tileSnapshots = emptyMap(),
        )
        val fullRouteRender = buildRouteRenderModel(
            routeModel = route,
            analysis = null,
            localWindowWidthMeters = RouteScale.Hundred.windowWidthMeters,
            canvasWidth = viewportWidth,
            canvasHeight = viewportHeight,
            includeGradientPolylines = true,
            boundsOverride = fullBounds,
        )
        val simplifiedRouteDrawSegments = fullRouteRender.gradientPolylines.sumOf { polyline ->
            max(0, simplifyGradientPointsForDisplay(polyline.points, tolerancePx = 1.5f).size - 1)
        }

        println(
            buildString {
                appendLine("TILE_BENCH route_points=${route.segments.sumOf { it.points.size }} route_edges=${route.edges.size}")
                appendLine("TILE_BENCH route_intersecting_tiles=${routeTileMetricsById.size}")
                appendLine("TILE_BENCH full_visible_tiles=${fullModel.tiles.size} full_labeled_tiles=${fullModel.tiles.count { it.label != null }}")
                appendLine("TILE_BENCH zoomed_visible_tiles=${zoomedModel.tiles.size} zoomed_labeled_tiles=${zoomedModel.tiles.count { it.label != null }}")
                appendLine("TILE_BENCH full_route_polylines=${fullRouteRender.gradientPolylines.size} full_route_draw_segments=${fullRouteRender.gradientPolylines.sumOf { max(0, it.points.size - 1) }}")
                appendLine("TILE_BENCH simplified_route_draw_segments=$simplifiedRouteDrawSegments")
                appendLine("TILE_BENCH index_avg_ms=${formatBenchmarkMillis(indexNanos)}")
                appendLine("TILE_BENCH full_frame_avg_ms=${formatBenchmarkMillis(fullOverviewNanos)}")
                appendLine("TILE_BENCH zoomed_frame_avg_ms=${formatBenchmarkMillis(zoomedOverviewNanos)}")
                appendLine("TILE_BENCH full_route_render_avg_ms=${formatBenchmarkMillis(fullRouteRenderNanos)}")
            },
        )
    }
}

private fun loadTiszaRouteModel(): RouteModel {
    return loadRouteMapInfoRouteModel()
}

private fun shrinkBounds(
    bounds: Bounds,
    factor: Double,
): Bounds {
    val width = bounds.maxX - bounds.minX
    val height = bounds.maxY - bounds.minY
    val insetX = width * (1.0 - factor) / 2.0
    val insetY = height * (1.0 - factor) / 2.0
    return Bounds(
        minX = min(bounds.maxX, bounds.minX + insetX),
        maxX = max(bounds.minX, bounds.maxX - insetX),
        minY = min(bounds.maxY, bounds.minY + insetY),
        maxY = max(bounds.minY, bounds.maxY - insetY),
    )
}
