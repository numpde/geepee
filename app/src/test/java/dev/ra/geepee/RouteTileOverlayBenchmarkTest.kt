package dev.ra.geepee

import org.junit.Test

class RouteTileOverlayBenchmarkTest {
    @Test
    fun benchmarkRealTileOverlayCompileAgainstDirectContext() {
        requireBenchmarkOptIn()
        val routeModel = loadRouteMapInfoRouteModel()
        val sourcePack = loadRouteMapInfoTileFixture("tile-context/10-571-356-local.json")
        val runtimePack = compileTileRuntimePack(sourcePack)

        val directContextNanos = benchmarkNanos(iterations = 10) {
            buildRouteContext(
                routeModel = routeModel,
                packs = listOf(sourcePack),
                config = DefaultTileContextConfig,
            )
        }
        val overlayCompileNanos = benchmarkNanos(iterations = 10) {
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
        val overlayEncodeNanos = benchmarkNanos(iterations = 50) {
            routeTileOverlayToByteArray(overlay)
        }
        val encoded = routeTileOverlayToByteArray(overlay)
        val overlayDecodeNanos = benchmarkNanos(iterations = 50) {
            routeTileOverlayFromByteArray(encoded)
        }

        println(
            buildString {
                appendLine("ROUTE_TILE_OVERLAY_BENCH pois=${overlay.context.pois.size} nearbyWays=${overlay.context.nearbyWays.size} leafEntries=${overlay.leafEntries.size}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH direct_context_avg_ms=${formatBenchmarkMillis(directContextNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_compile_avg_ms=${formatBenchmarkMillis(overlayCompileNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_encode_avg_ms=${formatBenchmarkMillis(overlayEncodeNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_decode_avg_ms=${formatBenchmarkMillis(overlayDecodeNanos)}")
                appendLine("ROUTE_TILE_OVERLAY_BENCH overlay_bytes=${encoded.size}")
            },
        )
    }
}
