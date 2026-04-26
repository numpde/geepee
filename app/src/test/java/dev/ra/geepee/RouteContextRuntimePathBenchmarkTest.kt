package dev.ra.geepee

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class RouteContextRuntimePathBenchmarkTest {
    @Test
    fun benchmarkRepositoryBackedOverlayAndLocalNearbyWayPath() {
        requireBenchmarkOptIn()
        val sourcePack = loadRuntimePathTileFixture("tile-context/10-571-356-local.json")
        val routeModel = loadRuntimePathRouteModel()
        val focusPoint = loadRuntimePathGeoPoints().getValue(6_854)

        val coldRuntimeLoadNanos = benchmarkRuntimePathNanos(iterations = 5) {
            withSeededRepository(sourcePack) { repository ->
                requireNotNull(repository.loadRuntimePack(sourcePack.tileId))
            }
        }

        val coldOverlayBuildNanos = benchmarkRuntimePathNanos(iterations = 5) {
            withSeededRepository(sourcePack) { repository ->
                requireNotNull(
                    repository.loadRouteTileOverlayBundle(
                        routeModel = routeModel,
                        tileId = sourcePack.tileId,
                        config = DefaultTileContextConfig,
                    ),
                )
            }
        }

        val warmMetrics = withSeededRepository(sourcePack) { repository ->
            val bundle = requireNotNull(
                repository.loadRouteTileOverlayBundle(
                    routeModel = routeModel,
                    tileId = sourcePack.tileId,
                    config = DefaultTileContextConfig,
                ),
            )
            val warmOverlayLoadNanos = benchmarkRuntimePathNanos(iterations = 100) {
                requireNotNull(
                    repository.loadRouteTileOverlayBundle(
                        routeModel = routeModel,
                        tileId = sourcePack.tileId,
                        config = DefaultTileContextConfig,
                    ),
                )
            }
            val warmNearbyWayQueryNanos = benchmarkRuntimePathNanos(iterations = 100) {
                queryRouteTileOverlayNearbyWays(
                    routeModel = routeModel,
                    bundle = bundle,
                    focusGeoPoint = focusPoint,
                    focusWindowWidthMeters = 1_000.0,
                    config = DefaultTileContextConfig,
                )
            }
            RuntimePathWarmMetrics(
                overlayLoadNanos = warmOverlayLoadNanos,
                nearbyWayQueryNanos = warmNearbyWayQueryNanos,
            )
        }

        println(
            buildString {
                appendLine("RUNTIME_PATH_BENCH tile=${sourcePack.tileId.cacheKey}")
                appendLine("RUNTIME_PATH_BENCH cold_runtime_load_avg_ms=${formatRuntimePathMillis(coldRuntimeLoadNanos)}")
                appendLine("RUNTIME_PATH_BENCH cold_overlay_build_avg_ms=${formatRuntimePathMillis(coldOverlayBuildNanos)}")
                appendLine("RUNTIME_PATH_BENCH warm_overlay_load_avg_ms=${formatRuntimePathMillis(warmMetrics.overlayLoadNanos)}")
                appendLine("RUNTIME_PATH_BENCH warm_nearby_query_avg_ms=${formatRuntimePathMillis(warmMetrics.nearbyWayQueryNanos)}")
            },
        )
    }
}

private data class RuntimePathWarmMetrics(
    val overlayLoadNanos: Long,
    val nearbyWayQueryNanos: Long,
)

private inline fun <T> withSeededRepository(
    sourcePack: TileContextPack,
    block: (TileContextRepository) -> T,
): T {
    val cacheRoot = Files.createTempDirectory("geepee-runtime-path-bench").toFile()
    try {
        TileContextRepository(cacheRoot).storeTilePack(sourcePack)
        return block(TileContextRepository(cacheRoot))
    } finally {
        cacheRoot.deleteRecursively()
    }
}

private fun benchmarkRuntimePathNanos(
    iterations: Int,
    block: () -> Unit,
): Long {
    val startedAt = System.nanoTime()
    repeat(iterations) {
        block()
    }
    return (System.nanoTime() - startedAt) / iterations.toLong()
}

private fun formatRuntimePathMillis(nanos: Long): String {
    return "%.3f".format(nanos / 1_000_000.0)
}

private fun loadRuntimePathTileFixture(path: String): TileContextPack {
    val resource = requireNotNull(RouteContextRuntimePathBenchmarkTest::class.java.classLoader?.getResource("dev/ra/geepee/$path")) {
        "Missing tile fixture resource: $path"
    }
    return tileContextPackFromJson(File(resource.toURI()).readText())
}

private fun loadRuntimePathRouteModel(): RouteModel {
    return buildRouteModel(listOf(loadRuntimePathGeoPoints().values.toList()))
}

private fun loadRuntimePathGeoPoints(): Map<Int, GeoPoint> {
    val routeFile = resolveRuntimePathRepoFile("routes/unneplos-tisza-ride.gpx")
    val document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(routeFile)
    val trackPoints = document.getElementsByTagNameNS("*", "trkpt")
    return buildMap(trackPoints.length) {
        for (index in 0 until trackPoints.length) {
            val node = trackPoints.item(index)
            val attributes = node.attributes
            put(
                index,
                GeoPoint(
                    lat = attributes.getNamedItem("lat").nodeValue.toDouble(),
                    lon = attributes.getNamedItem("lon").nodeValue.toDouble(),
                ),
            )
        }
    }
}

private fun resolveRuntimePathRepoFile(relativePath: String): File {
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
