package dev.ra.geepee

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
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
    val resource = requireNotNull(RouteTileOverlayBenchmarkTest::class.java.classLoader?.getResource("dev/ra/geepee/$path")) {
        "Missing tile fixture resource: $path"
    }
    return tileContextPackFromJson(File(resource.toURI()).readText())
}

private fun loadRouteOverlayBenchmarkRouteModel(): RouteModel {
    val routeFile = resolveRouteOverlayBenchmarkRepoFile("routes/unneplos-tisza-ride.gpx")
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

private fun resolveRouteOverlayBenchmarkRepoFile(relativePath: String): File {
    val cwd = File(requireNotNull(System.getProperty("user.dir")))
    var current: File? = cwd.absoluteFile
    repeat(8) {
        val candidate = current?.resolve(relativePath)
        if (candidate?.isFile == true) {
            return candidate
        }
        current = current?.parentFile
    }
    error("Could not locate repo file: $relativePath")
}
