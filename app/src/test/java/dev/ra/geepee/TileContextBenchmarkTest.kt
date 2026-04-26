package dev.ra.geepee

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
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
                appendLine("TILE_BENCH index_avg_ms=${formatNanosMillis(indexNanos)}")
                appendLine("TILE_BENCH full_frame_avg_ms=${formatNanosMillis(fullOverviewNanos)}")
                appendLine("TILE_BENCH zoomed_frame_avg_ms=${formatNanosMillis(zoomedOverviewNanos)}")
                appendLine("TILE_BENCH full_route_render_avg_ms=${formatNanosMillis(fullRouteRenderNanos)}")
            },
        )
    }
}

private fun benchmarkNanos(
    iterations: Int,
    block: () -> Unit,
): Long {
    val startedAt = System.nanoTime()
    repeat(iterations) {
        block()
    }
    return (System.nanoTime() - startedAt) / iterations.toLong()
}

private fun formatNanosMillis(nanos: Long): String {
    return "%.3f".format(nanos / 1_000_000.0)
}

private fun loadTiszaRouteModel(): RouteModel {
    val routeFile = resolveRepoFileForTileBench("routes/unneplos-tisza-ride.gpx")
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
    return buildRouteModel(listOf(geoPoints))
}

private fun resolveRepoFileForTileBench(relativePath: String): File {
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
    error("Could not locate repo file: $relativePath from $workingDirectory")
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
