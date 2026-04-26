package dev.ra.geepee

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class RouteContextCoordinatorBenchmarkTest {
    @Test
    fun benchmarkRepositoryBackedRouteContextRebuild() {
        requireBenchmarkOptIn()
        val sourcePack = loadCoordinatorBenchmarkTileFixture("tile-context/10-571-356-local.json")
        val routeModel = loadCoordinatorBenchmarkRouteModel()

        val coldRouteContextNanos = benchmarkCoordinatorNanos(iterations = 5) {
            withCoordinatorRepository(sourcePack) { repository ->
                val coordinator = RouteContextCoordinator(
                    tileContextRepository = repository,
                    tileContextConfig = DefaultTileContextConfig,
                    callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                    logTag = "RouteContextCoordinatorBenchmarkTest",
                )
                try {
                    awaitRouteContextRebuild(coordinator, routeModel)
                } finally {
                    coordinator.shutdown()
                }
            }
        }

        val warmRouteContextNanos = withCoordinatorRepository(sourcePack) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteContextCoordinatorBenchmarkTest",
            )
            try {
                awaitRouteContextRebuild(coordinator, routeModel)
                benchmarkCoordinatorNanos(iterations = 100) {
                    awaitRouteContextRebuild(coordinator, routeModel)
                }
            } finally {
                coordinator.shutdown()
            }
        }

        println(
            buildString {
                appendLine("ROUTE_CONTEXT_COORDINATOR_BENCH tile=${sourcePack.tileId.cacheKey}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_BENCH cold_rebuild_avg_ms=${formatCoordinatorMillis(coldRouteContextNanos)}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_BENCH warm_rebuild_avg_ms=${formatCoordinatorMillis(warmRouteContextNanos)}")
            },
        )
    }

    @Test
    fun benchmarkRepositoryBackedNearbyWayRebuild() {
        requireBenchmarkOptIn()
        val sourcePack = loadCoordinatorBenchmarkTileFixture("tile-context/10-571-356-local.json")
        val routeModel = loadCoordinatorBenchmarkRouteModel()
        val focusPoint = loadCoordinatorBenchmarkGeoPoints().getValue(6_854)
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = focusPoint.lat,
                lon = focusPoint.lon,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val tileDownloads = mapOf(
            sourcePack.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                actualBytes = 1L,
                updatedAtMillis = sourcePack.fetchedAtMillis,
            ),
        )

        val coldNearbyWayNanos = benchmarkCoordinatorNanos(iterations = 5) {
            withCoordinatorRepository(sourcePack) { repository ->
                val coordinator = RouteContextCoordinator(
                    tileContextRepository = repository,
                    tileContextConfig = DefaultTileContextConfig,
                    callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                    logTag = "RouteContextCoordinatorBenchmarkTest",
                )
                try {
                    awaitNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = routeModel,
                        analysis = analysis,
                        tileDownloads = tileDownloads,
                        focusWindowWidthMeters = 1_000.0,
                    )
                } finally {
                    coordinator.shutdown()
                }
            }
        }

        val warmNearbyWayNanos = withCoordinatorRepository(sourcePack) { repository ->
            val coordinator = RouteContextCoordinator(
                tileContextRepository = repository,
                tileContextConfig = DefaultTileContextConfig,
                callbackExecutor = java.util.concurrent.Executor { runnable -> runnable.run() },
                logTag = "RouteContextCoordinatorBenchmarkTest",
            )
            try {
                awaitNearbyWayRebuild(
                    coordinator = coordinator,
                    routeModel = routeModel,
                    analysis = analysis,
                    tileDownloads = tileDownloads,
                    focusWindowWidthMeters = 1_000.0,
                )
                benchmarkCoordinatorNanos(iterations = 100) {
                    awaitNearbyWayRebuild(
                        coordinator = coordinator,
                        routeModel = routeModel,
                        analysis = analysis,
                        tileDownloads = tileDownloads,
                        focusWindowWidthMeters = 1_000.0,
                        force = true,
                    )
                }
            } finally {
                coordinator.shutdown()
            }
        }

        println(
            buildString {
                appendLine("ROUTE_CONTEXT_COORDINATOR_NEARBY_BENCH tile=${sourcePack.tileId.cacheKey}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_NEARBY_BENCH cold_rebuild_avg_ms=${formatCoordinatorMillis(coldNearbyWayNanos)}")
                appendLine("ROUTE_CONTEXT_COORDINATOR_NEARBY_BENCH warm_rebuild_avg_ms=${formatCoordinatorMillis(warmNearbyWayNanos)}")
            },
        )
    }
}

private inline fun <T> withCoordinatorRepository(
    sourcePack: TileContextPack,
    block: (TileContextRepository) -> T,
): T {
    val cacheRoot = Files.createTempDirectory("geepee-route-context-coordinator-bench").toFile()
    try {
        TileContextRepository(cacheRoot).storeTilePack(sourcePack)
        return block(TileContextRepository(cacheRoot))
    } finally {
        cacheRoot.deleteRecursively()
    }
}

private fun benchmarkCoordinatorNanos(
    iterations: Int,
    block: () -> Unit,
): Long {
    val startedAt = System.nanoTime()
    repeat(iterations) {
        block()
    }
    return (System.nanoTime() - startedAt) / iterations.toLong()
}

private fun formatCoordinatorMillis(nanos: Long): String {
    return "%.3f".format(nanos / 1_000_000.0)
}

private fun awaitRouteContextRebuild(
    coordinator: RouteContextCoordinator,
    routeModel: RouteModel,
): List<RoutePoi> {
    val latch = CountDownLatch(1)
    var result: List<RoutePoi>? = null
    coordinator.rebuildRouteContext(routeModel) { rebuilt ->
        result = rebuilt
        latch.countDown()
    }
    check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for route-context rebuild" }
    return checkNotNull(result)
}

private fun awaitNearbyWayRebuild(
    coordinator: RouteContextCoordinator,
    routeModel: RouteModel,
    analysis: RouteAnalysis,
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
    focusWindowWidthMeters: Double,
    force: Boolean = true,
): RouteMapInfoState {
    val latch = CountDownLatch(1)
    var result: RouteMapInfoState? = null
    coordinator.rebuildNearbyWays(
        routeModel = routeModel,
        analysis = analysis,
        tileDownloads = tileDownloads,
        existingLocalStatus = null,
        focus = MapInfoFocus(
            centerGeoPoint = analysis.nearestGeoPoint,
            windowWidthMeters = focusWindowWidthMeters,
        ),
        defaultFocusWindowWidthMeters = focusWindowWidthMeters,
        force = force,
        onStarted = {},
        onResult = { rebuilt ->
            result = rebuilt
            latch.countDown()
        },
    )
    check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for nearby-way rebuild" }
    return checkNotNull(result)
}

private fun loadCoordinatorBenchmarkTileFixture(path: String): TileContextPack {
    val resource = requireNotNull(RouteContextCoordinatorBenchmarkTest::class.java.classLoader?.getResource("dev/ra/geepee/$path")) {
        "Missing tile fixture resource: $path"
    }
    return tileContextPackFromJson(File(resource.toURI()).readText())
}

private fun loadCoordinatorBenchmarkRouteModel(): RouteModel {
    return buildRouteModel(listOf(loadCoordinatorBenchmarkGeoPoints().values.toList()))
}

private fun loadCoordinatorBenchmarkGeoPoints(): Map<Int, GeoPoint> {
    val routeFile = resolveCoordinatorBenchmarkRepoFile("routes/unneplos-tisza-ride.gpx")
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

private fun resolveCoordinatorBenchmarkRepoFile(relativePath: String): File {
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
