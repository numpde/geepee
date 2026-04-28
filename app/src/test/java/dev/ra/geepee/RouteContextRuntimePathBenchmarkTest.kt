package dev.ra.geepee

import java.io.File
import org.junit.Test

class RouteContextRuntimePathBenchmarkTest {
    @Test
    fun benchmarkRepositoryBackedOverlayAndLocalNearbyWayPath() {
        requireBenchmarkOptIn()
        val sourcePack = loadRouteMapInfoTileFixture("tile-context/10-571-356-local.json")
        val routeModel = loadRouteMapInfoRouteModel()
        val focusPoint = loadRouteMapInfoGeoPointsByIndex().getValue(6_854)
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

        val coldRuntimeLoadNanos = benchmarkNanos(iterations = 5) {
            withSeededTileContextRepository(
                prefix = "geepee-runtime-path-bench",
                sourcePack = sourcePack,
            ) { repository ->
                requireNotNull(repository.loadRuntimePack(sourcePack.tileId))
            }
        }

        val coldOverlayBuildNanos = benchmarkNanos(iterations = 5) {
            withSeededTileContextRepository(
                prefix = "geepee-runtime-path-bench",
                sourcePack = sourcePack,
            ) { repository ->
                requireNotNull(
                    repository.loadRouteTileOverlayBundle(
                        routeModel = routeModel,
                        tileId = sourcePack.tileId,
                        config = DefaultTileContextConfig,
                    ),
                )
            }
        }

        val warmMetrics = withSeededTileContextRepository(
            prefix = "geepee-runtime-path-bench",
            sourcePack = sourcePack,
        ) { repository ->
            val bundle = requireNotNull(
                repository.loadRouteTileOverlayBundle(
                    routeModel = routeModel,
                    tileId = sourcePack.tileId,
                    config = DefaultTileContextConfig,
                ),
            )
            val warmOverlayLoadNanos = benchmarkNanos(iterations = 100) {
                requireNotNull(
                    repository.loadRouteTileOverlayBundle(
                        routeModel = routeModel,
                        tileId = sourcePack.tileId,
                        config = DefaultTileContextConfig,
                    ),
                )
            }
            val warmNearbyWayQueryNanos = benchmarkNanos(iterations = 100) {
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
                appendLine("RUNTIME_PATH_BENCH cold_runtime_load_avg_ms=${formatBenchmarkMillis(coldRuntimeLoadNanos)}")
                appendLine("RUNTIME_PATH_BENCH cold_overlay_build_avg_ms=${formatBenchmarkMillis(coldOverlayBuildNanos)}")
                appendLine("RUNTIME_PATH_BENCH warm_overlay_load_avg_ms=${formatBenchmarkMillis(warmMetrics.overlayLoadNanos)}")
                appendLine("RUNTIME_PATH_BENCH warm_nearby_query_avg_ms=${formatBenchmarkMillis(warmMetrics.nearbyWayQueryNanos)}")
            },
        )
    }
}

private data class RuntimePathWarmMetrics(
    val overlayLoadNanos: Long,
    val nearbyWayQueryNanos: Long,
)
