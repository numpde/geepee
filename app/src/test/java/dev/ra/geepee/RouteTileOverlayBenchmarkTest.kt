package dev.ra.geepee

import org.junit.Test

class RouteTileOverlayBenchmarkTest {
    @Test
    fun benchmarkRealTileOverlayCompileAgainstDirectContext() {
        requireBenchmarkOptIn()
        val routeModel = loadRouteOverlayBenchmarkRouteModel()
        val sourcePack = loadRouteOverlayBenchmarkTileFixture("tile-context/10-571-356-local.json")
        val runtimePack = compileTileRuntimePack(sourcePack)

        val directContextNanos = benchmarkRouteTileOverlayNanos(iterations = 10) {
            buildRouteContext(
                routeModel = routeModel,
                packs = listOf(sourcePack),
                config = DefaultTileContextConfig,
            )
        }
        val overlayCompileNanos = benchmarkRouteTileOverlayNanos(iterations = 10) {
            buildRouteTileOverlay(
                routeModel = routeModel,
                runtimePack = runtimePack,
                config = DefaultTileContextConfig,
            )
        }
        val overlay = buildRouteTileOverlay(
            routeModel = routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )
        val overlayEncodeNanos = benchmarkRouteTileOverlayNanos(iterations = 50) {
            routeTileOverlayToByteArray(overlay)
        }
        val encoded = routeTileOverlayToByteArray(overlay)
        val overlayDecodeNanos = benchmarkRouteTileOverlayNanos(iterations = 50) {
            routeTileOverlayFromByteArray(encoded)
        }

        println(
            buildString {
                appendLine("ROUTE_TILE_OVERLAY_BENCH pois=${overlay.context.pois.size} nearbyWays=${overlay.context.nearbyWays.size} leafEntries=${overlay.leafEntries.size}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH direct_context_avg_ms=${formatRouteTileOverlayMillis(directContextNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_compile_avg_ms=${formatRouteTileOverlayMillis(overlayCompileNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_encode_avg_ms=${formatRouteTileOverlayMillis(overlayEncodeNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_decode_avg_ms=${formatRouteTileOverlayMillis(overlayDecodeNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_bytes=${encoded.size}")
            },
        )
    }
}

private fun benchmarkRouteTileOverlayNanos(
    iterations: Int,
    block: () -> Unit,
): Long {
    val startedAt = System.nanoTime()
    repeat(iterations) {
        block()
    }
    return (System.nanoTime() - startedAt) / iterations.toLong()
}

private fun formatRouteTileOverlayMillis(nanos: Long): String {
    return "%.3f".format(nanos / 1_000_000.0)
}

private fun loadRouteOverlayBenchmarkTileFixture(path: String): TileContextPack {
    return loadRouteMapInfoTileFixture(path)
}

private fun loadRouteOverlayBenchmarkRouteModel(): RouteModel {
    return loadRouteMapInfoRouteModel()
}
