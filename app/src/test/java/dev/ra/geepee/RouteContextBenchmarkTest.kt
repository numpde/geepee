package dev.ra.geepee

import java.io.File
import org.junit.Test

class RouteContextBenchmarkTest {
    @Test
    fun benchmarkFullTiszaRouteContextAgainstPulledCachedTiles() {
        requireBenchmarkOptIn()
        val routeModel = loadBenchmarkTiszaRouteModel()
        val tileFiles = listOf(
            "/tmp/geepee-all-tiles/569/350.json",
            "/tmp/geepee-all-tiles/569/363.json",
            "/tmp/geepee-all-tiles/571/356.json",
            "/tmp/geepee-all-tiles/571/357.json",
            "/tmp/geepee-all-tiles/572/356.json",
            "/tmp/geepee-all-tiles/576/366.json",
            "/tmp/geepee-all-tiles/577/348.json",
        ).map(::File)

        val parseNanos = benchmarkNanos(iterations = 3) {
            tileFiles.map { tileContextPackFromJson(it.readText()) }
        }
        val packs = tileFiles.map { tileContextPackFromJson(it.readText()) }

        val routeTilesNanos = benchmarkNanos(iterations = 5) {
            tilesForRoute(routeModel, DefaultTileContextConfig)
        }
        val routeTiles = tilesForRoute(routeModel, DefaultTileContextConfig)

        val contextNanos = benchmarkNanos(iterations = 3) {
            buildRouteContext(
                routeModel = routeModel,
                packs = packs,
                config = DefaultTileContextConfig,
            )
        }
        val context = buildRouteContext(
            routeModel = routeModel,
            packs = packs,
            config = DefaultTileContextConfig,
        )

        println(
            buildString {
                appendLine("ROUTE_CONTEXT_BENCH packs=${packs.size}")
                appendLine("ROUTE_CONTEXT_BENCH parse_avg_ms=${formatBenchMillis(parseNanos)}")
                appendLine("ROUTE_CONTEXT_BENCH routeTiles=${routeTiles.size} route_tiles_avg_ms=${formatBenchMillis(routeTilesNanos)}")
                appendLine("ROUTE_CONTEXT_BENCH pois=${context.pois.size} nearbyWays=${context.nearbyWays.size} context_avg_ms=${formatBenchMillis(contextNanos)}")
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

private fun formatBenchMillis(nanos: Long): String {
    return "%.3f".format(nanos / 1_000_000.0)
}

private fun loadBenchmarkTiszaRouteModel(): RouteModel {
    return loadRouteMapInfoRouteModel()
}
