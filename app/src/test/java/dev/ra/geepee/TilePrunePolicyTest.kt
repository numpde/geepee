package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePrunePolicyTest {
    @Test
    fun deleteUnusedProtectedTileIds_keepsRouteWarmTilesViewportTilesAndActiveDownloads() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(47.8392, 21.0667),
                    GeoPoint(47.8420, 21.0667),
                ),
            ),
        )
        val routeTile = requireNotNull(tilesForRoute(routeModel, DefaultTileContextConfig).singleOrNull()) {
            "Expected synthetic route to stay within one download tile"
        }
        val routeNeighborTile = DownloadTileId(
            zoom = routeTile.zoom,
            x = routeTile.x + 1,
            y = routeTile.y,
        )
        val viewportTile = DownloadTileId(
            zoom = routeTile.zoom,
            x = routeTile.x + 2,
            y = routeTile.y,
        )
        val downloadingTile = DownloadTileId(
            zoom = routeTile.zoom,
            x = routeTile.x + 9,
            y = routeTile.y + 9,
        )
        val recentCachedTile = DownloadTileId(
            zoom = routeTile.zoom,
            x = routeTile.x + 10,
            y = routeTile.y + 10,
        )
        val unusedCachedTile = DownloadTileId(
            zoom = routeTile.zoom,
            x = routeTile.x + 12,
            y = routeTile.y + 12,
        )
        val nowMillis = 1_000_000_000L
        val staleAccessMillis = 1L
        val viewportBounds = tileGeoBounds(viewportTile)
        val viewportCenter = GeoPoint(
            lat = (viewportBounds.south + viewportBounds.north) / 2.0,
            lon = (viewportBounds.west + viewportBounds.east) / 2.0,
        )
        val focus = buildRouteMapInfoFocus(
            routeModel = routeModel,
            focusPoint = viewportCenter,
            widthMeters = 300.0,
        )
        val tileDownloads = mapOf(
            routeTile to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                lastAccessedAtMillis = staleAccessMillis,
            ),
            routeNeighborTile to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                lastAccessedAtMillis = staleAccessMillis,
            ),
            viewportTile to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                lastAccessedAtMillis = staleAccessMillis,
            ),
            downloadingTile to TileDownloadSnapshot(
                status = TileDownloadStatus.Downloading,
                estimatedBytes = 1L,
            ),
            recentCachedTile to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                lastAccessedAtMillis = nowMillis,
            ),
            unusedCachedTile to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = 1L,
                lastAccessedAtMillis = staleAccessMillis,
            ),
        )

        val policy = buildDeleteUnusedTilePrunePolicy(
            routeModel = routeModel,
            currentMapInfoFocus = focus,
            tileDownloads = tileDownloads,
            config = DefaultTileContextConfig,
            nowMillis = nowMillis,
        )
        val protectedTileIds = policy.protectedTileIds

        assertTrue(protectedTileIds.contains(routeTile))
        assertTrue(protectedTileIds.contains(routeNeighborTile))
        assertTrue(protectedTileIds.contains(viewportTile))
        assertTrue(protectedTileIds.contains(downloadingTile))
        assertTrue(protectedTileIds.contains(recentCachedTile))
        assertFalse(protectedTileIds.contains(unusedCachedTile))
        assertEquals(5, protectedTileIds.size)
    }
}
