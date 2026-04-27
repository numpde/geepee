package dev.ra.geepee

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileContextRepositoryTest {
    @Test
    fun runtimePackLoadsReuseInMemoryCache() {
        withRepository { repository ->
            val pack = loadRepositoryTileFixture("tile-context/10-571-356-local.json")
            repository.storeTilePack(pack)

            val first = requireNotNull(repository.loadRuntimePack(pack.tileId))
            val second = requireNotNull(repository.loadRuntimePack(pack.tileId))

            assertSame(first, second)
        }
    }

    @Test
    fun routeTileOverlayLoadsReuseInMemoryCache() {
        withRepository { repository ->
            val pack = loadRepositoryTileFixture("tile-context/10-571-356-local.json")
            repository.storeTilePack(pack)
            val routeModel = loadRepositoryRouteModel()

            val first = requireNotNull(
                repository.loadRouteTileOverlayBundle(routeModel, pack.tileId, DefaultTileContextConfig),
            )
            val second = requireNotNull(
                repository.loadRouteTileOverlayBundle(routeModel, pack.tileId, DefaultTileContextConfig),
            )

            assertSame(first.runtimePack, second.runtimePack)
            assertSame(first.overlay, second.overlay)
        }
    }

    @Test
    fun cachedOnlyOverlayLoadDoesNotBuildMissingOverlay() {
        withRepository { repository ->
            val pack = loadRepositoryTileFixture("tile-context/10-571-356-local.json")
            repository.storeTilePack(pack)
            val routeModel = loadRepositoryRouteModel()

            val cachedOnlyBeforeBuild = repository.peekCachedRouteTileOverlayBundles(
                routeModel = routeModel,
                tileIds = listOf(pack.tileId),
                config = DefaultTileContextConfig,
            )
            val built = repository.loadRouteTileOverlayBundles(
                routeModel = routeModel,
                tileIds = listOf(pack.tileId),
                config = DefaultTileContextConfig,
            )
            val cachedOnlyAfterBuild = repository.peekCachedRouteTileOverlayBundles(
                routeModel = routeModel,
                tileIds = listOf(pack.tileId),
                config = DefaultTileContextConfig,
            )

            assertTrue(cachedOnlyBeforeBuild.isEmpty())
            assertEquals(1, built.size)
            assertEquals(1, cachedOnlyAfterBuild.size)
        }
    }

    @Test
    fun concurrentRouteTileOverlayLoadsShareSingleOverlayBuild() {
        withRepository { repository ->
            val pack = loadRepositoryTileFixture("tile-context/10-571-356-local.json")
            repository.storeTilePack(pack)
            val routeModel = loadRepositoryRouteModel()
            val startLatch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures = List(2) {
                    executor.submit<RouteTileOverlayBundle?> {
                        startLatch.await(5, TimeUnit.SECONDS)
                        repository.loadRouteTileOverlayBundle(routeModel, pack.tileId, DefaultTileContextConfig)
                    }
                }

                startLatch.countDown()
                val first = requireNotNull(futures[0].get(30, TimeUnit.SECONDS))
                val second = requireNotNull(futures[1].get(30, TimeUnit.SECONDS))

                assertSame(first.runtimePack, second.runtimePack)
                assertSame(first.overlay, second.overlay)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun storingNewTilePackInvalidatesRuntimeAndOverlayCaches() {
        withRepository { repository ->
            val originalPack = loadRepositoryTileFixture("tile-context/10-571-356-local.json")
            val routeModel = loadRepositoryRouteModel()
            val routePointInTile = requireNotNull(findRoutePointWithinBounds(routeModel, originalPack.queryBounds)) {
                "Expected Tisza route fixture to intersect tile fixture bounds"
            }
            repository.storeTilePack(originalPack)

            val firstRuntime = requireNotNull(repository.loadRuntimePack(originalPack.tileId))
            val firstOverlay = requireNotNull(
                repository.loadRouteTileOverlayBundle(
                    routeModel = routeModel,
                    tileId = originalPack.tileId,
                    config = DefaultTileContextConfig,
                ),
            )

            val updatedPack = originalPack.copy(
                features = originalPack.features + TileContextFeature(
                    featureId = "test:drinking_water:extra",
                    geometryKind = TileGeometryKind.Point,
                    tags = mapOf("amenity" to "drinking_water"),
                    geometry = listOf(routePointInTile),
                ),
            )
            repository.storeTilePack(updatedPack)

            val secondRuntime = requireNotNull(repository.loadRuntimePack(updatedPack.tileId))
            val secondOverlay = requireNotNull(
                repository.loadRouteTileOverlayBundle(
                    routeModel = routeModel,
                    tileId = updatedPack.tileId,
                    config = DefaultTileContextConfig,
                ),
            )

            assertNotSame(firstRuntime, secondRuntime)
            assertNotSame(firstOverlay.overlay, secondOverlay.overlay)
            assertEquals(firstRuntime.fetchedAtMillis, secondRuntime.fetchedAtMillis)
            assertEquals(firstOverlay.overlay.sourceFetchedAtMillis, secondOverlay.overlay.sourceFetchedAtMillis)
            assertEquals(firstRuntime.pointFeatures.size + 1, secondRuntime.pointFeatures.size)
            assertEquals(firstOverlay.overlay.context.pois.size + 1, secondOverlay.overlay.context.pois.size)
            assertTrue(secondOverlay.overlay.context.pois.any { poi -> poi.featureId == "test:drinking_water:extra" })
        }
    }
}

private inline fun withRepository(block: (TileContextRepository) -> Unit) {
    val cacheRoot = Files.createTempDirectory("geepee-tile-context-repo-test").toFile()
    try {
        block(TileContextRepository(cacheRoot))
    } finally {
        cacheRoot.deleteRecursively()
    }
}

private fun loadRepositoryTileFixture(path: String): TileContextPack {
    val resource = requireNotNull(TileContextRepositoryTest::class.java.classLoader?.getResource("dev/ra/geepee/$path")) {
        "Missing tile fixture resource: $path"
    }
    return tileContextPackFromJson(File(resource.toURI()).readText())
}

private fun loadRepositoryRouteModel(): RouteModel {
    val routeFile = resolveRepositoryRepoFile("routes/unneplos-tisza-ride.gpx")
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

private fun resolveRepositoryRepoFile(relativePath: String): File {
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

private fun findRoutePointWithinBounds(
    routeModel: RouteModel,
    bounds: GeoBounds,
): GeoPoint? {
    return routeModel.segments
        .asSequence()
        .flatMap { segment -> segment.geoPoints.asSequence() }
        .firstOrNull { point ->
            point.lat in bounds.south..bounds.north &&
                point.lon in bounds.west..bounds.east
        }
}
