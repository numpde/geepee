package dev.ra.geepee

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileContextRepositoryTest {
    @Test
    fun deleteTilesRemovesSourceRuntimeOverlayAndManifestEntry() {
        withRepositoryRoot { cacheRoot, repository ->
            val pack = loadRepositoryTileFixture("tile-context/10-571-356-local.json")
            repository.storeTilePack(pack)
            val routeModel = loadRepositoryRouteModel()
            requireNotNull(
                repository.loadRouteTileOverlayBundle(routeModel, pack.tileId, DefaultTileContextConfig),
            )

            val sourceFile = File(cacheRoot, "tiles/${pack.tileId.zoom}/${pack.tileId.x}/${pack.tileId.y}.json")
            val runtimeFile = File(cacheRoot, "runtime/tiles/${pack.tileId.zoom}/${pack.tileId.x}/${pack.tileId.y}.bin")
            val overlayFiles = File(cacheRoot, "route-overlays")
                .walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension == "bin" &&
                        file.name.startsWith("${pack.tileId.y}-")
                }
                .toList()

            assertTrue(sourceFile.exists())
            assertTrue(runtimeFile.exists())
            assertTrue(overlayFiles.isNotEmpty())

            val result = repository.deleteTiles(listOf(pack.tileId))

            assertEquals(setOf(pack.tileId), result.deletedTileIds)
            assertTrue(result.freedBytes > 0L)
            assertTrue(repository.cachedTileSnapshots().isEmpty())
            assertEquals(null, repository.loadTilePack(pack.tileId))
            assertEquals(null, repository.loadRuntimePack(pack.tileId))
            assertTrue(
                repository.peekCachedRouteTileOverlayBundles(
                    routeModel = routeModel,
                    tileIds = listOf(pack.tileId),
                    config = DefaultTileContextConfig,
                ).isEmpty(),
            )
            assertFalse(sourceFile.exists())
            assertFalse(runtimeFile.exists())
            overlayFiles.forEach { overlayFile ->
                assertFalse(overlayFile.exists())
            }
        }
    }

    @Test
    fun pruneTilesKeepsProtectedTilesAndDeletesTheRest() {
        withRepository { repository ->
            val protectedPack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 570, y = 356),
                west = 21.0,
            )
            val unusedPack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 571, y = 356),
                west = 21.02,
            )
            repository.storeTilePack(protectedPack)
            repository.storeTilePack(unusedPack)

            val result = repository.pruneTiles(
                TilePrunePolicy(
                    protectedTileIds = setOf(protectedPack.tileId),
                ),
            )

            assertEquals(setOf(unusedPack.tileId), result.deletedTileIds)
            assertTrue(repository.loadTilePack(protectedPack.tileId) != null)
            assertEquals(null, repository.loadTilePack(unusedPack.tileId))
            assertEquals(setOf(protectedPack.tileId), repository.cachedTileSnapshots().keys)
        }
    }

    @Test
    fun buildTileDeletePlanFallsBackToUnusedPolicyWhenSelectionIsEmpty() {
        withRepositoryRoot { cacheRoot, repository ->
            val protectedPack = loadRepositoryTileFixture("tile-context/10-571-356-local.json")
            val deletablePack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 570, y = 355),
                west = 21.0,
            )
            val routeModel = loadRepositoryRouteModel()
            repository.storeTilePack(protectedPack)
            repository.storeTilePack(deletablePack)
            requireNotNull(
                repository.loadRouteTileOverlayBundle(routeModel, deletablePack.tileId, DefaultTileContextConfig),
            )

            val plan = repository.buildTileDeletePlan(
                selectedTileIds = emptySet(),
                unusedPolicy = TilePrunePolicy(
                    protectedTileIds = setOf(protectedPack.tileId),
                ),
            )

            val expectedBytes = listOf(
                File(cacheRoot, "tiles/${deletablePack.tileId.zoom}/${deletablePack.tileId.x}/${deletablePack.tileId.y}.json"),
                File(cacheRoot, "runtime/tiles/${deletablePack.tileId.zoom}/${deletablePack.tileId.x}/${deletablePack.tileId.y}.bin"),
            ).sumOf(File::length) +
                File(cacheRoot, "route-overlays")
                    .walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            file.extension == "bin" &&
                            file.name.startsWith("${deletablePack.tileId.y}-")
                    }
                    .sumOf(File::length)

            assertEquals(TileDeleteMode.Unused, plan.mode)
            assertEquals(setOf(deletablePack.tileId), plan.tileIds)
            assertEquals(1, plan.tileCount)
            assertEquals(expectedBytes, plan.freedBytes)
        }
    }

    @Test
    fun buildTileDeletePlanPrefersExplicitSelectedCachedTiles() {
        withRepositoryRoot { cacheRoot, repository ->
            val pack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 570, y = 355),
                west = 21.0,
            )
            val extraUnusedPack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 571, y = 355),
                west = 21.02,
            )
            val routeModel = loadRepositoryRouteModel()
            repository.storeTilePack(pack)
            repository.storeTilePack(extraUnusedPack)
            requireNotNull(
                repository.loadRouteTileOverlayBundle(routeModel, pack.tileId, DefaultTileContextConfig),
            )

            val plan = repository.buildTileDeletePlan(
                selectedTileIds = listOf(
                    pack.tileId,
                    DownloadTileId(zoom = 10, x = 999, y = 999),
                ),
                unusedPolicy = TilePrunePolicy(
                    protectedTileIds = setOf(extraUnusedPack.tileId),
                ),
            )

            val expectedBytes = listOf(
                File(cacheRoot, "tiles/${pack.tileId.zoom}/${pack.tileId.x}/${pack.tileId.y}.json"),
                File(cacheRoot, "runtime/tiles/${pack.tileId.zoom}/${pack.tileId.x}/${pack.tileId.y}.bin"),
            ).sumOf(File::length) +
                File(cacheRoot, "route-overlays")
                    .walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            file.extension == "bin" &&
                            file.name.startsWith("${pack.tileId.y}-")
                    }
                    .sumOf(File::length)

            assertEquals(TileDeleteMode.Selected, plan.mode)
            assertEquals(setOf(pack.tileId), plan.tileIds)
            assertEquals(1, plan.tileCount)
            assertEquals(expectedBytes, plan.freedBytes)
        }
    }

    @Test
    fun buildTileDeletePlanFallsBackToUnusedPolicyWhenSelectionHasNoCachedTiles() {
        withRepository { repository ->
            val protectedPack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 570, y = 356),
                west = 21.0,
            )
            val deletablePack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 571, y = 356),
                west = 21.02,
            )
            repository.storeTilePack(protectedPack)
            repository.storeTilePack(deletablePack)

            val plan = repository.buildTileDeletePlan(
                selectedTileIds = setOf(DownloadTileId(zoom = 10, x = 999, y = 999)),
                unusedPolicy = TilePrunePolicy(
                    protectedTileIds = setOf(protectedPack.tileId),
                ),
            )

            assertEquals(TileDeleteMode.Unused, plan.mode)
            assertEquals(setOf(deletablePack.tileId), plan.tileIds)
            assertEquals(1, plan.tileCount)
        }
    }

    @Test
    fun storeTilePackPersistsProvidedDownloadMetadata() {
        withRepository { repository ->
            val pack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 570, y = 356),
                west = 21.0,
            )
            val snapshot = TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1234L,
                actualBytes = 1234L,
                updatedAtMillis = 4567L,
                downloadedAtMillis = 1111L,
                lastAccessedAtMillis = 2222L,
            )

            repository.storeTilePack(pack, snapshot)

            val stored = requireNotNull(repository.cachedTileSnapshots()[pack.tileId])
            assertEquals(TileDownloadStatus.Cached, stored.status)
            assertEquals(1234L, stored.estimatedBytes)
            assertEquals(1111L, stored.downloadedAtMillis)
            assertEquals(2222L, stored.lastAccessedAtMillis)
            assertEquals(4567L, stored.updatedAtMillis)
            assertTrue((stored.actualBytes ?: 0L) > 0L)
        }
    }

    @Test
    fun loadingTileArtifactsTouchesAndPersistsLastAccessTime() {
        withRepositoryRoot { cacheRoot, repository ->
            val pack = syntheticRepositoryPack(
                tileId = DownloadTileId(zoom = 10, x = 570, y = 356),
                west = 21.0,
            )
            repository.storeTilePack(pack)
            val initialSnapshot = requireNotNull(repository.cachedTileSnapshots()[pack.tileId])
            assertEquals(pack.fetchedAtMillis, initialSnapshot.lastAccessedAtMillis)

            requireNotNull(repository.loadRuntimePack(pack.tileId))

            val touchedSnapshot = requireNotNull(repository.cachedTileSnapshots()[pack.tileId])
            assertTrue(touchedSnapshot.lastAccessedAtMillis > initialSnapshot.lastAccessedAtMillis)

            val reloadedRepository = TileContextRepository(cacheRoot)
            val reloadedSnapshot = requireNotNull(reloadedRepository.cachedTileSnapshots()[pack.tileId])
            assertEquals(touchedSnapshot.lastAccessedAtMillis, reloadedSnapshot.lastAccessedAtMillis)
            assertEquals(initialSnapshot.downloadedAtMillis, reloadedSnapshot.downloadedAtMillis)
        }
    }

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

private inline fun withRepositoryRoot(block: (File, TileContextRepository) -> Unit) {
    val cacheRoot = Files.createTempDirectory("geepee-tile-context-repo-test").toFile()
    try {
        block(cacheRoot, TileContextRepository(cacheRoot))
    } finally {
        cacheRoot.deleteRecursively()
    }
}

private fun loadRepositoryTileFixture(path: String): TileContextPack {
    return loadRouteMapInfoTileFixture(path)
}

private fun loadRepositoryRouteModel(): RouteModel {
    return loadRouteMapInfoRouteModel()
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

private fun syntheticRepositoryPack(
    tileId: DownloadTileId,
    west: Double,
): TileContextPack {
    return TileContextPack(
        tileId = tileId,
        queryBounds = GeoBounds(
            west = west,
            south = 47.8,
            east = west + 0.01,
            north = 47.81,
        ),
        fetchedAtMillis = 123L,
        features = listOf(
            TileContextFeature(
                featureId = "node/${tileId.cacheKey}",
                geometryKind = TileGeometryKind.Point,
                tags = mapOf("amenity" to "drinking_water"),
                geometry = listOf(GeoPoint(lat = 47.805, lon = west + 0.005)),
            ),
        ),
    )
}
