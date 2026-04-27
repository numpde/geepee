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
        assertTrue(
            resolved.localTileIds.contains(
                tileIdForGeoPoint(analysis.nearestGeoPoint, DefaultTileContextConfig.downloadZoom),
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
        assertTrue(
            resolved.localTileIds.contains(
                tileIdForGeoPoint(explicitFocus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
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
            focusRouteEdgeIndexes = emptyList(),
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
                loadedTileRevisions = revisions,
            ),
        )
        val nearbyKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = nearbyFocus,
            tileCoverage = NearbyWayTileCoverage(
                localTileIds = emptySet(),
                loadedTileRevisions = revisions,
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
            focusRouteEdgeIndexes = emptyList(),
        )

        val firstKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = focus,
            tileCoverage = NearbyWayTileCoverage(
                localTileIds = emptySet(),
                loadedTileRevisions = listOf(
                    NearbyWayLoadedTileRevision(
                        tileId = tileIdForGeoPoint(focus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                        updatedAtMillis = 123L,
                    ),
                ),
            ),
        )
        val secondKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = focus,
            tileCoverage = NearbyWayTileCoverage(
                localTileIds = emptySet(),
                loadedTileRevisions = listOf(
                    NearbyWayLoadedTileRevision(
                        tileId = tileIdForGeoPoint(focus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                        updatedAtMillis = 456L,
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

        assertEquals(projectedBounds, resolved.focus.projectedBounds)
        assertEquals(
            tilesIntersectingProjectedBounds(
                projection = routeModel.projection,
                bounds = expandBounds(
                    projectedBounds,
                    DefaultTileContextConfig.wayHaloMeters + DefaultTileContextConfig.nearbyWayContinuationMeters,
                ),
                zoom = DefaultTileContextConfig.downloadZoom,
            ).toSet(),
            resolved.localTileIds,
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
        val tileA = DownloadTileId(zoom = 10, x = 10, y = 20)
        val tileB = DownloadTileId(zoom = 10, x = 9, y = 20)
        val tileC = DownloadTileId(zoom = 10, x = 11, y = 20)

        val coverage = buildNearbyWayTileCoverage(
            localTileIds = linkedSetOf(tileA, tileB, tileC),
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
    fun nearbyWayTileCoverage_buildsConsistentStatusViews() {
        val coverage = NearbyWayTileCoverage(
            localTileIds = setOf(
                DownloadTileId(zoom = 10, x = 1, y = 1),
                DownloadTileId(zoom = 10, x = 2, y = 1),
            ),
            loadedTileRevisions = listOf(
                NearbyWayLoadedTileRevision(
                    tileId = DownloadTileId(zoom = 10, x = 2, y = 1),
                    updatedAtMillis = 123L,
                ),
            ),
        )

        val loading = coverage.loadingStatus(existingNearbyWayCount = 4)
        assertEquals(2, loading.localTileCount)
        assertEquals(1, loading.loadedLocalTileCount)
        assertEquals(4, loading.nearbyWayCount)

        val resolved = coverage.resolvedMapInfo(emptyList())
        assertEquals(2, resolved.localNearbyWays?.localTileCount)
        assertEquals(1, resolved.localNearbyWays?.loadedLocalTileCount)
        assertTrue(resolved.nearbyWays.isEmpty())

        val failed = coverage.failedMapInfo("Boom")
        assertEquals(2, failed.localNearbyWays?.localTileCount)
        assertEquals(1, failed.localNearbyWays?.loadedLocalTileCount)
        assertEquals("Boom", failed.localNearbyWays?.errorMessage)
        assertFalse(failed.localNearbyWays?.hasVisibleTileData == false)
    }
}
