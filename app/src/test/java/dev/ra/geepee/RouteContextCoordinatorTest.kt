package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteContextCoordinatorTest {
    @Test
    fun resolveNearbyWayQueryFocus_defaultsToMatchedRoutePointWithoutExplicitFocus() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 1.0),
                    GeoPoint(1.0, 1.0),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = 0.1,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val resolved = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = null,
            config = DefaultTileContextConfig,
            defaultFocusWindowWidthMeters = 200.0,
        )

        assertEquals(analysis.nearestGeoPoint, resolved.focus.centerGeoPoint)
        assertEquals(200.0, resolved.focus.windowWidthMeters, 0.0)
        val expectedResolution = resolveTileResolution(
            windowWidthMeters = 200.0,
            policy = DefaultTileContextConfig.resolutionPolicy,
        )
        assertTrue(
            resolved.localTileIds.contains(
                tileIdForGeoPoint(analysis.nearestGeoPoint, expectedResolution.dataZoom),
            ),
        )
        assertEquals(
            nearbyWayFocusBounds(
                routeModel = routeModel,
                focusGeoPoint = analysis.nearestGeoPoint,
                focusWindowWidthMeters = 200.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ),
            resolved.focus.projectedBounds,
        )
    }

    @Test
    fun resolveNearbyWayQueryFocus_usesExplicitViewportCenterInsteadOfMatchedRoutePoint() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 1.0),
                    GeoPoint(1.0, 1.0),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = 0.1,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val explicitFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(0.9, 1.0),
            windowWidthMeters = 150.0,
            projectedBounds = nearbyWayFocusBounds(
                routeModel = routeModel,
                focusGeoPoint = GeoPoint(0.9, 1.0),
                focusWindowWidthMeters = 150.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ) ?: routeModel.bounds,
        )

        val resolved = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = explicitFocus,
            config = DefaultTileContextConfig,
            defaultFocusWindowWidthMeters = 200.0,
        )

        assertEquals(explicitFocus.centerGeoPoint, resolved.focus.centerGeoPoint)
        assertEquals(explicitFocus.windowWidthMeters, resolved.focus.windowWidthMeters, 0.0)
        val expectedResolution = resolveTileResolution(
            windowWidthMeters = explicitFocus.windowWidthMeters,
            policy = DefaultTileContextConfig.resolutionPolicy,
        )
        assertTrue(
            resolved.localTileIds.contains(
                tileIdForGeoPoint(explicitFocus.centerGeoPoint, expectedResolution.dataZoom),
            ),
        )
    }

    @Test
    fun nearbyWayQueryCacheKey_isStableWithinFocusBucket() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.01),
                ),
            ),
        )
        val baseFocus = NearbyWayQueryFocus(
            focus = MapInfoFocus(
                centerGeoPoint = GeoPoint(0.0, 0.0020),
                windowWidthMeters = 100.0,
                projectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            ),
            localTileIds = emptySet(),
            expandedProjectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            dataZoom = DefaultTileContextConfig.downloadZoom,
        )
        val nearbyFocus = baseFocus.copy(
            focus = baseFocus.focus.copy(
                centerGeoPoint = GeoPoint(0.0, 0.00204),
            ),
        )
        val revisions = listOf(
            NearbyWayLoadedTileRevision(
                tileId = tileIdForGeoPoint(baseFocus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                updatedAtMillis = 123L,
            ),
        )

        val baseKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = baseFocus,
            tileCoverage = NearbyWayTileCoverage(
                localTileIds = emptySet(),
                loadedTileCoverages = revisions.map { revision ->
                    NearbyWayLoadedTileCoverage(
                        tileRevision = revision,
                        coveredLocalTileIds = emptySet(),
                    )
                },
            ),
        )
        val nearbyKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = nearbyFocus,
            tileCoverage = NearbyWayTileCoverage(
                localTileIds = emptySet(),
                loadedTileCoverages = revisions.map { revision ->
                    NearbyWayLoadedTileCoverage(
                        tileRevision = revision,
                        coveredLocalTileIds = emptySet(),
                    )
                },
            ),
        )

        assertEquals(baseKey, nearbyKey)
    }

    @Test
    fun nearbyWayQueryCacheKey_changesWhenLoadedTileRevisionChanges() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.01),
                ),
            ),
        )
        val focus = NearbyWayQueryFocus(
            focus = MapInfoFocus(
                centerGeoPoint = GeoPoint(0.0, 0.0020),
                windowWidthMeters = 100.0,
                projectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            ),
            localTileIds = emptySet(),
            expandedProjectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            dataZoom = DefaultTileContextConfig.downloadZoom,
        )

        val firstKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = focus,
            tileCoverage = NearbyWayTileCoverage(
                localTileIds = emptySet(),
                loadedTileCoverages = listOf(
                    NearbyWayLoadedTileCoverage(
                        tileRevision = NearbyWayLoadedTileRevision(
                            tileId = tileIdForGeoPoint(focus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                            updatedAtMillis = 123L,
                        ),
                        coveredLocalTileIds = emptySet(),
                    ),
                ),
            ),
        )
        val secondKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = focus,
            tileCoverage = NearbyWayTileCoverage(
                localTileIds = emptySet(),
                loadedTileCoverages = listOf(
                    NearbyWayLoadedTileCoverage(
                        tileRevision = NearbyWayLoadedTileRevision(
                            tileId = tileIdForGeoPoint(focus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                            updatedAtMillis = 456L,
                        ),
                        coveredLocalTileIds = emptySet(),
                    ),
                ),
            ),
        )

        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun resolveNearbyWayQueryFocus_usesViewportBoundsForLocalTileCoverage() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.5),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = 0.05,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val projectedBounds = projectedBoundsForGeoBounds(
            bounds = GeoBounds(
                west = 0.0,
                south = -0.01,
                east = 0.4,
                north = 0.01,
            ),
            projection = routeModel.projection,
        )
        val explicitFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(0.0, 0.2),
            windowWidthMeters = 100.0,
            projectedBounds = projectedBounds,
        )

        val resolved = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = explicitFocus,
            config = DefaultTileContextConfig,
            defaultFocusWindowWidthMeters = 200.0,
        )

        val expectedResolution = resolveTileResolution(
            windowWidthMeters = explicitFocus.windowWidthMeters,
            policy = DefaultTileContextConfig.resolutionPolicy,
        )
        assertEquals(projectedBounds, resolved.focus.projectedBounds)
        assertEquals(
            tilesIntersectingProjectedBounds(
                projection = routeModel.projection,
                bounds = expandBounds(
                    projectedBounds,
                    DefaultTileContextConfig.wayHaloMeters + DefaultTileContextConfig.nearbyWayContinuationMeters,
                ),
                zoom = expectedResolution.dataZoom,
            ).toSet(),
            resolved.localTileIds,
        )
    }

    @Test
    fun nearbyWayRuntimeHintEdgeIndexes_areDerivedFromExpandedFocusBounds() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.5),
                    GeoPoint(0.5, 0.5),
                ),
            ),
        )
        val focus = MapInfoFocus(
            centerGeoPoint = GeoPoint(0.0, 0.2),
            windowWidthMeters = 100.0,
            projectedBounds = projectedBoundsForGeoBounds(
                bounds = GeoBounds(
                    west = 0.0,
                    south = -0.01,
                    east = 0.4,
                    north = 0.01,
                ),
                projection = routeModel.projection,
            ),
        )

        assertEquals(
            routeEdgeIndexesIntersectingBounds(
                model = routeModel,
                bounds = expandedNearbyWayMapInfoBounds(
                    focus = focus,
                    config = DefaultTileContextConfig,
                ),
            ),
            nearbyWayFocusRouteEdgeIndexes(
                routeModel = routeModel,
                focus = focus,
                config = DefaultTileContextConfig,
            ),
        )
    }

    @Test
    fun routeMapInfoWarmTileIds_includeCachedNeighborTilesAroundRoute() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(47.8392, 21.0667),
                    GeoPoint(47.8420, 21.0667),
                ),
            ),
        )
        val routeTiles = tilesForRoute(routeModel, DefaultTileContextConfig)
        val routeTile = requireNotNull(routeTiles.singleOrNull()) {
            "Expected synthetic route to stay within one download tile"
        }
        val cachedNeighborTile = DownloadTileId(
            zoom = routeTile.zoom,
            x = routeTile.x + 1,
            y = routeTile.y,
        )
        val unrelatedTile = DownloadTileId(
            zoom = routeTile.zoom,
            x = routeTile.x + 5,
            y = routeTile.y + 5,
        )

        val warmTileIds = routeMapInfoWarmTileIds(
            routeModel = routeModel,
            cachedTileIds = setOf(routeTile, cachedNeighborTile, unrelatedTile),
            config = DefaultTileContextConfig,
        )

        assertEquals(setOf(routeTile, cachedNeighborTile), warmTileIds)
    }

    @Test
    fun buildNearbyWayTileCoverage_keepsOnlyCachedTilesSortedByTileId() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.5),
                ),
            ),
        )
        val tileA = DownloadTileId(zoom = 10, x = 10, y = 20)
        val tileB = DownloadTileId(zoom = 10, x = 9, y = 20)
        val tileC = DownloadTileId(zoom = 10, x = 11, y = 20)
        val queryBounds = listOf(tileA, tileB, tileC)
            .map { tileId -> projectedBoundsForGeoBounds(tileGeoBounds(tileId), routeModel.projection) }
            .let { tileBounds ->
                Bounds(
                    minX = tileBounds.minOf(Bounds::minX),
                    maxX = tileBounds.maxOf(Bounds::maxX),
                    minY = tileBounds.minOf(Bounds::minY),
                    maxY = tileBounds.maxOf(Bounds::maxY),
                )
            }
        val queryFocus = NearbyWayQueryFocus(
            focus = MapInfoFocus(
                centerGeoPoint = GeoPoint(0.0, 0.0),
                windowWidthMeters = 100.0,
                projectedBounds = queryBounds,
            ),
            localTileIds = linkedSetOf(tileA, tileB, tileC),
            expandedProjectedBounds = queryBounds,
            dataZoom = 10,
        )

        val coverage = buildNearbyWayTileCoverage(
            queryFocus = queryFocus,
            tileDownloads = mapOf(
                tileA to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    updatedAtMillis = 20L,
                ),
                tileB to TileDownloadSnapshot(
                    status = TileDownloadStatus.Error,
                    estimatedBytes = 1L,
                    updatedAtMillis = 10L,
                ),
                tileC to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    updatedAtMillis = 30L,
                ),
            ),
        )

        assertEquals(setOf(tileA, tileB, tileC), coverage.localTileIds)
        assertEquals(listOf(tileA, tileC).sortedBy(DownloadTileId::cacheKey), coverage.loadedTileIds)
        assertEquals(3, coverage.localTileCount)
        assertEquals(2, coverage.loadedLocalTileCount)
    }

    @Test
    fun buildNearbyWayTileCoverage_keepsIntersectingCoarserCachedTileForFinerQueryZoom() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.5),
                ),
            ),
        )
        val coarseTile = DownloadTileId(zoom = 10, x = 10, y = 20)
        val queryBounds = projectedBoundsForGeoBounds(tileGeoBounds(coarseTile), routeModel.projection)
        val fineLocalTileIds = buildSet {
            for (x in (coarseTile.x shl 2) until ((coarseTile.x + 1) shl 2)) {
                for (y in (coarseTile.y shl 2) until ((coarseTile.y + 1) shl 2)) {
                    add(DownloadTileId(zoom = 12, x = x, y = y))
                }
            }
        }
        val queryFocus = NearbyWayQueryFocus(
            focus = MapInfoFocus(
                centerGeoPoint = GeoPoint(0.0, 0.0),
                windowWidthMeters = 100.0,
                projectedBounds = queryBounds,
            ),
            localTileIds = fineLocalTileIds,
            expandedProjectedBounds = queryBounds,
            dataZoom = 12,
        )

        val coverage = buildNearbyWayTileCoverage(
            queryFocus = queryFocus,
            tileDownloads = mapOf(
                coarseTile to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    updatedAtMillis = 20L,
                ),
            ),
        )

        assertEquals(fineLocalTileIds, coverage.localTileIds)
        assertEquals(listOf(coarseTile), coverage.loadedTileIds)
        assertEquals(fineLocalTileIds.size, coverage.loadedLocalTileCount)
    }

    @Test
    fun buildNearbyWayTileCoverage_prefersFinerCachedChildOverCoarserParent() {
        val parentTile = DownloadTileId(zoom = 10, x = 10, y = 20)
        val childTile = DownloadTileId(zoom = 12, x = (parentTile.x shl 2) + 1, y = (parentTile.y shl 2) + 2)
        val fineLocalTileIds = buildSet {
            for (x in (parentTile.x shl 2) until ((parentTile.x + 1) shl 2)) {
                for (y in (parentTile.y shl 2) until ((parentTile.y + 1) shl 2)) {
                    add(DownloadTileId(zoom = 12, x = x, y = y))
                }
            }
        }
        val queryFocus = NearbyWayQueryFocus(
            focus = MapInfoFocus(
                centerGeoPoint = GeoPoint(0.0, 0.0),
                windowWidthMeters = 100.0,
                projectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            ),
            localTileIds = fineLocalTileIds,
            expandedProjectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            dataZoom = 12,
        )

        val coverage = buildNearbyWayTileCoverage(
            queryFocus = queryFocus,
            tileDownloads = mapOf(
                parentTile to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    updatedAtMillis = 10L,
                ),
                childTile to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    updatedAtMillis = 20L,
                ),
            ),
        )

        assertEquals(listOf(parentTile, childTile).sortedBy(DownloadTileId::cacheKey), coverage.loadedTileIds)
        assertEquals(fineLocalTileIds.size, coverage.loadedLocalTileCount)
        assertEquals(setOf(childTile), coverage.loadedTileCoverages.single { it.tileRevision.tileId == childTile }.coveredLocalTileIds)
        assertEquals(
            fineLocalTileIds - childTile,
            coverage.loadedTileCoverages.single { it.tileRevision.tileId == parentTile }.coveredLocalTileIds,
        )
    }

    @Test
    fun nearbyWayTileCoverage_buildsConsistentStatusViews() {
        val coverage = NearbyWayTileCoverage(
            localTileIds = setOf(
                DownloadTileId(zoom = 10, x = 1, y = 1),
                DownloadTileId(zoom = 10, x = 2, y = 1),
            ),
            loadedTileCoverages = listOf(
                NearbyWayLoadedTileCoverage(
                    tileRevision = NearbyWayLoadedTileRevision(
                        tileId = DownloadTileId(zoom = 10, x = 2, y = 1),
                        updatedAtMillis = 123L,
                    ),
                    coveredLocalTileIds = setOf(DownloadTileId(zoom = 10, x = 2, y = 1)),
                ),
            ),
        )

        val loading = coverage.loadingStatus(existingNearbyWayCount = 4)
        assertEquals(2, loading.localTileCount)
        assertEquals(1, loading.downloadedLocalTileCount)
        assertEquals(0, loading.overlayReadyLocalTileCount)
        assertEquals(0, loading.nearbyWayCount)
        assertEquals(true, loading.nearbyWaysLoading)

        val resolved = coverage.resolvedMapInfo(
            nearbyWays = emptyList(),
            overlayReadyLocalTileCount = 1,
        )
        assertEquals(2, resolved.localNearbyWays?.localTileCount)
        assertEquals(1, resolved.localNearbyWays?.downloadedLocalTileCount)
        assertEquals(1, resolved.localNearbyWays?.overlayReadyLocalTileCount)
        assertTrue(resolved.nearbyWays.isEmpty())

        val failed = coverage.failedMapInfo("Boom")
        assertEquals(2, failed.localNearbyWays?.localTileCount)
        assertEquals(1, failed.localNearbyWays?.downloadedLocalTileCount)
        assertEquals(0, failed.localNearbyWays?.overlayReadyLocalTileCount)
        assertEquals("Boom", failed.localNearbyWays?.errorMessage)
        assertEquals(false, failed.localNearbyWays?.hasVisibleTileData)
    }
}
