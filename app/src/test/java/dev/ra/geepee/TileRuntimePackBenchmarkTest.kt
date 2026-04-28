package dev.ra.geepee

import org.junit.Test

class TileRuntimePackBenchmarkTest {
    @Test
    fun benchmarkRealTileRuntimePackCompileAndCodec() {
        requireBenchmarkOptIn()
        val sourcePack = loadBenchmarkRuntimeTileFixture("tile-context/10-571-356-local.json")

        val compileNanos = benchmarkNanos(iterations = 10) {
            compileTileRuntimePack(sourcePack)
        }
        val compiled = compileTileRuntimePack(sourcePack)

        val encodeNanos = benchmarkNanos(iterations = 50) {
            tileRuntimePackToByteArray(compiled)
        }
        val encoded = tileRuntimePackToByteArray(compiled)

        val decodeNanos = benchmarkNanos(iterations = 50) {
            tileRuntimePackFromByteArray(encoded)
        }

        println(
            buildString {
                appendLine("TILE_RUNTIME_BENCH source_features=${sourcePack.features.size}")
                appendLine("TILE_RUNTIME_BENCH segments=${compiled.waySegments.size} points=${compiled.pointFeatures.size} junctions=${compiled.junctions.size} nodes=${compiled.quadtreeNodes.size}")
                appendLine("TILE_RUNTIME_BENCH encoded_bytes=${encoded.size}")
                appendLine("TILE_RUNTIME_BENCH compile_avg_ms=${formatBenchmarkMillis(compileNanos)}")
                appendLine("TILE_RUNTIME_BENCH encode_avg_ms=${formatBenchmarkMillis(encodeNanos)}")
                appendLine("TILE_RUNTIME_BENCH decode_avg_ms=${formatBenchmarkMillis(decodeNanos)}")
            },
        )
    }
}

private fun loadBenchmarkRuntimeTileFixture(path: String): TileContextPack {
    return loadRouteMapInfoTileFixture(path)
}
