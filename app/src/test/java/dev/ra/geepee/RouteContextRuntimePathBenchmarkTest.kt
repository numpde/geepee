package dev.ra.geepee

import java.io.File
import java.nio.file.Files
import org.junit.Test

class RouteContextRuntimePathBenchmarkTest {
    @Test
    fun benchmarkRepositoryBackedOverlayAndLocalNearbyWayPath() {
        requireBenchmarkOptIn()
        val sourcePack = loadRuntimePathTileFixture("tile-context/10-571-356-local.json")
        val routeModel = loadRuntimePathRouteModel()
        val focusPoint = loadRuntimePathGeoPoints().getValue(6_854)
        val focus = MapInfoFocus(
            centerGeoPoint = focusPoint,
            windowWidthMeters = 1_000.0,
            projectedBounds = nearbyWayFocusBounds(
                routeModel = routeModel,
                focusGeoPoint = focusPoint,
                focusWindowWidthMeters = 1_000.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ) ?: routeModel.bounds,
        )

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
                    focus = focus,
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
    return loadRouteMapInfoTileFixture(path)
}

private fun loadRuntimePathRouteModel(): RouteModel {
    return loadRouteMapInfoRouteModel()
}

private fun loadRuntimePathGeoPoints(): Map<Int, GeoPoint> {
    return loadRouteMapInfoGeoPointsByIndex()
}
