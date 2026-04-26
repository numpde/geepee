package dev.ra.geepee

import java.io.File
import org.junit.Test

class TileRuntimePackBenchmarkTest {
    @Test
    fun benchmarkRealTileRuntimePackCompileAndCodec() {
        requireBenchmarkOptIn()
        val sourcePack = loadBenchmarkRuntimeTileFixture("tile-context/10-571-356-local.json")

        val compileNanos = benchmarkTileRuntimeNanos(iterations = 10) {
            compileTileRuntimePack(sourcePack)
        }
        val compiled = compileTileRuntimePack(sourcePack)

        val encodeNanos = benchmarkTileRuntimeNanos(iterations = 50) {
            tileRuntimePackToByteArray(compiled)
        }
        val encoded = tileRuntimePackToByteArray(compiled)

        val decodeNanos = benchmarkTileRuntimeNanos(iterations = 50) {
            tileRuntimePackFromByteArray(encoded)
        }

        println(
            buildString {
                appendLine("TILE_RUNTIME_BENCH source_features=${sourcePack.features.size}")
                appendLine("TILE_RUNTIME_BENCH segments=${compiled.waySegments.size} points=${compiled.pointFeatures.size} junctions=${compiled.junctions.size} nodes=${compiled.quadtreeNodes.size}")
                appendLine("TILE_RUNTIME_BENCH encoded_bytes=${encoded.size}")
                appendLine("TILE_RUNTIME_BENCH compile_avg_ms=${formatTileRuntimeMillis(compileNanos)}")
                appendLine("TILE_RUNTIME_BENCH encode_avg_ms=${formatTileRuntimeMillis(encodeNanos)}")
                appendLine("TILE_RUNTIME_BENCH decode_avg_ms=${formatTileRuntimeMillis(decodeNanos)}")
            },
        )
    }
}

private fun benchmarkTileRuntimeNanos(
    iterations: Int,
    block: () -> Unit,
): Long {
    val startedAt = System.nanoTime()
    repeat(iterations) {
        block()
    }
    return (System.nanoTime() - startedAt) / iterations.toLong()
}

private fun formatTileRuntimeMillis(nanos: Long): String {
    return "%.3f".format(nanos / 1_000_000.0)
}

private fun loadBenchmarkRuntimeTileFixture(path: String): TileContextPack {
    val resource = requireNotNull(TileRuntimePackBenchmarkTest::class.java.classLoader?.getResource("dev/ra/geepee/$path")) {
        "Missing tile fixture resource: $path"
    }
    return tileContextPackFromJson(File(resource.toURI()).readText())
}
