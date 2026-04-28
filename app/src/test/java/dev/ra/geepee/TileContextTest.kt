package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TileContextTest {
    @Test
    fun tilesForRouteUsesConfiguredZoom() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val tileIds = tilesForRoute(
            routeModel = route,
            config = TileContextConfig(downloadZoom = 10),
        )

        assertTrue(tileIds.isNotEmpty())
        assertTrue(tileIds.all { it.zoom == 10 })
    }

    @Test
    fun tileGridRenderModelMarksRouteIntersectingTiles() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = TileContextConfig(downloadZoom = 10),
            tileSnapshots = emptyMap(),
        )

        assertTrue(renderModel.tiles.isNotEmpty())
        assertTrue(renderModel.tiles.count { it.routeMetrics.intersectsRoute } >= 2)
        assertTrue(renderModel.tiles.all { it.label != null })
    }

    @Test
    fun tileGridRenderModelHidesOffRouteUndownloadedTilesButKeepsCachedOnes() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )
        val config = TileContextConfig(
            downloadZoom = 10,
            resolutionPolicy = TileResolutionPolicy(
                displayZoomBands = listOf(
                    TileDisplayZoomBand(minimumWindowWidthMeters = 0.0, displayZoom = 10),
                ),
                minimumDataZoom = 12,
                dataZoomOffsetFromDisplay = 1,
                maximumDataZoom = 16,
            ),
        )
        val routeMetrics = buildRouteTileMetricsIndex(
            routeModel = route,
            config = config,
        )
        val visibleBounds = Bounds(
            minX = route.bounds.minX,
            maxX = route.bounds.maxX,
            minY = route.bounds.minY - 40_000.0,
            maxY = route.bounds.maxY + 40_000.0,
        )
        val visibleTileIds = tilesIntersectingProjectedBounds(
            projection = route.projection,
            bounds = visibleBounds,
            zoom = config.downloadZoom,
        )
        val offRouteTileId = requireNotNull(
            visibleTileIds.firstOrNull { tileId -> routeMetrics[tileId] == null },
        ) {
            "Expected a viewport tile that does not intersect the route"
        }

        val withoutCache = buildTileGridRenderModel(
            routeModel = route,
            bounds = visibleBounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = emptyMap(),
        )
        val withCachedOffRouteTile = buildTileGridRenderModel(
            routeModel = route,
            bounds = visibleBounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = mapOf(
                offRouteTileId to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 123L,
                ),
            ),
        )

        assertFalse(withoutCache.tiles.any { it.tileId == offRouteTileId })
        assertTrue(withCachedOffRouteTile.tiles.any { it.tileId == offRouteTileId })
    }

    @Test
    fun tileGridHitTestingReturnsVisibleTile() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = TileContextConfig(downloadZoom = 10),
            tileSnapshots = emptyMap(),
        )
        val firstTile = renderModel.tiles.first()
        val hitTile = renderModel.tileAt(
            ScreenPoint(
                x = firstTile.screenRect.left + firstTile.screenRect.width / 2f,
                y = firstTile.screenRect.top + firstTile.screenRect.height / 2f,
            ),
        )

        assertNotNull(hitTile)
        assertEquals(firstTile.tileId, hitTile?.tileId)
    }

    @Test
    fun tileGridRenderModelUsesViewProxyOutlineForOversizedRouteTile() {
        val anchorTile = DownloadTileId(zoom = 10, x = 512, y = 512)
        val anchorBounds = tileGeoBounds(anchorTile)
        val centerLat = (anchorBounds.south + anchorBounds.north) / 2.0
        val centerLon = (anchorBounds.west + anchorBounds.east) / 2.0
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = centerLat, lon = centerLon - 0.0001),
                    GeoPoint(lat = centerLat, lon = centerLon + 0.0001),
                ),
            ),
        )
        val config = TileContextConfig(
            downloadZoom = 10,
            resolutionPolicy = TileResolutionPolicy(
                displayZoomBands = listOf(
                    TileDisplayZoomBand(minimumWindowWidthMeters = 0.0, displayZoom = 10),
                ),
                minimumDataZoom = 12,
                dataZoomOffsetFromDisplay = 1,
                maximumDataZoom = 16,
            ),
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 400f,
            canvasHeight = 240f,
            config = config,
            tileSnapshots = emptyMap(),
        )

        val tile = renderModel.tiles.single()
        assertEquals(anchorTile, tile.tileId)
        assertEquals(TileGridOutlineStyle.ViewProxyDashed, tile.outlineStyle)
        assertTrue(tile.screenRect.left > 0f)
        assertTrue(tile.screenRect.top > 0f)
        assertTrue(tile.screenRect.right < 400f)
        assertTrue(tile.screenRect.bottom < 240f)
        assertEquals(
            tile.tileId,
            renderModel.tileAt(
                ScreenPoint(
                    x = tile.screenRect.left + tile.screenRect.width / 2f,
                    y = tile.screenRect.top + tile.screenRect.height / 2f,
                ),
            )?.tileId,
        )
    }

    @Test
    fun viewportProxyOutlineIsSuppressedWhenRealTileEdgeTouchesViewBoundary() {
        val routeMetrics = TileRouteMetrics(
            intersectsRoute = true,
            intersectingEdgeCount = 1,
            intersectingRouteMeters = 100.0,
        )

        assertFalse(
            shouldUseViewportProxyOutline(
                screenRect = ScreenRect(
                    left = 0f,
                    top = -50f,
                    right = 450f,
                    bottom = 290f,
                ),
                routeMetrics = routeMetrics,
                canvasWidth = 400f,
                canvasHeight = 240f,
            ),
        )
    }

    @Test
    fun viewportProxyOutlineIsSuppressedWhenRealTileCornerTouchesViewBoundary() {
        val routeMetrics = TileRouteMetrics(
            intersectsRoute = true,
            intersectingEdgeCount = 1,
            intersectingRouteMeters = 100.0,
        )

        assertFalse(
            shouldUseViewportProxyOutline(
                screenRect = ScreenRect(
                    left = 0f,
                    top = 0f,
                    right = 450f,
                    bottom = 290f,
                ),
                routeMetrics = routeMetrics,
                canvasWidth = 400f,
                canvasHeight = 240f,
            ),
        )
    }

    @Test
    fun viewportProxyOutlineRequiresStrictOverflowPastAllViewEdges() {
        val routeMetrics = TileRouteMetrics(
            intersectsRoute = true,
            intersectingEdgeCount = 1,
            intersectingRouteMeters = 100.0,
        )

        assertTrue(
            shouldUseViewportProxyOutline(
                screenRect = ScreenRect(
                    left = -10f,
                    top = -10f,
                    right = 410f,
                    bottom = 250f,
                ),
                routeMetrics = routeMetrics,
                canvasWidth = 400f,
                canvasHeight = 240f,
            ),
        )
    }

    @Test
    fun tileGridRenderModelCanFilterToFullyVisibleTiles() {
        val metrics = TileRouteMetrics(
            intersectsRoute = false,
            intersectingEdgeCount = 0,
            intersectingRouteMeters = 0.0,
        )
        val model = TileGridRenderModel(
            tiles = listOf(
                TileGridDisplayTile(
                    tileId = DownloadTileId(zoom = 10, x = 1, y = 1),
                    screenRect = ScreenRect(left = 10f, top = 20f, right = 110f, bottom = 120f),
                    routeMetrics = metrics,
                    downloadState = null,
                    progressFraction = null,
                    representedCoverage = TileGridRepresentedCoverage(emptyList()),
                    downloadRequests = emptyList(),
                    estimatedBytes = 0L,
                    label = null,
                ),
                TileGridDisplayTile(
                    tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
                    screenRect = ScreenRect(left = -5f, top = 20f, right = 95f, bottom = 120f),
                    routeMetrics = metrics,
                    downloadState = null,
                    progressFraction = null,
                    representedCoverage = TileGridRepresentedCoverage(emptyList()),
                    downloadRequests = emptyList(),
                    estimatedBytes = 0L,
                    label = null,
                ),
            ),
        )

        val filtered = model.fullyVisibleWithin(width = 200f, height = 200f)

        assertEquals(1, filtered.tiles.size)
        assertEquals("10/1/1", filtered.tiles.single().tileId.cacheKey)
    }

    @Test
    fun tileGridRenderModelMarksSelectedTiles() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )
        val config = TileContextConfig(downloadZoom = 10)
        val baseModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = emptyMap(),
        )
        val selectedTile = baseModel.tiles.first { it.downloadRequests.isNotEmpty() }
        val selectedSnapshots = selectedTile.downloadRequests.associate { request ->
            request.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = request.estimatedBytes,
                actualBytes = request.estimatedBytes,
            )
        }
        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = selectedSnapshots,
            selectedTileIds = selectedSnapshots.keys,
        )

        assertEquals(1, renderModel.tiles.count { it.selected })
        assertTrue(renderModel.tiles.any { it.tileId == selectedTile.tileId && it.selected })
    }

    @Test
    fun tileGridRenderModelMarksPartiallySelectedTiles() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )
        val config = TileContextConfig(downloadZoom = 10)
        val baseModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = emptyMap(),
        )
        val selectedTile = baseModel.tiles.first { it.downloadRequests.size >= 2 }
        val cachedRequests = selectedTile.downloadRequests.take(2)
        val selectedRequest = cachedRequests.first()
        val cachedSnapshots = cachedRequests.associate { request ->
            request.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = request.estimatedBytes,
                actualBytes = request.estimatedBytes,
            )
        }
        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = cachedSnapshots,
            selectedTileIds = setOf(selectedRequest.tileId),
        )

        val renderedTile = renderModel.tiles.first { it.tileId == selectedTile.tileId }
        assertEquals(TileGridSelectionState.PartiallySelected, renderedTile.selectionState)
        assertFalse(renderedTile.selected)
        assertEquals(setOf(selectedRequest.tileId), renderedTile.selectedCachedTileIds)
        assertEquals(1, renderedTile.selectedCoverageRects.size)
    }

    @Test
    fun tileResolutionPolicyUsesConfiguredDisplayAndDataZoomLadder() {
        val policy = TileResolutionPolicy()

        assertEquals(TileResolution(displayZoom = 10, dataZoom = 12), resolveTileResolution(3_000.0, policy))
        assertEquals(TileResolution(displayZoom = 11, dataZoom = 12), resolveTileResolution(1_500.0, policy))
        assertEquals(TileResolution(displayZoom = 12, dataZoom = 13), resolveTileResolution(600.0, policy))
        assertEquals(TileResolution(displayZoom = 13, dataZoom = 14), resolveTileResolution(180.0, policy))
        assertEquals(TileResolution(displayZoom = 14, dataZoom = 15), resolveTileResolution(60.0, policy))
    }

    @Test
    fun tileGridRenderModelDownloadsFinerDataChildrenThanDisplayedMacroTile() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )
        val config = TileContextConfig(downloadZoom = 10)

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = emptyMap(),
        )
        val displayTile = renderModel.tiles.first { it.routeMetrics.intersectsRoute }

        assertTrue(displayTile.downloadRequests.isNotEmpty())
        assertTrue(displayTile.downloadRequests.all { request -> request.tileId.zoom > displayTile.tileId.zoom })
    }

    @Test
    fun childTileScreenRectWithinDisplayTileMapsChildQuadrant() {
        val displayTileId = DownloadTileId(zoom = 10, x = 100, y = 200)
        val displayRect = ScreenRect(left = 20f, top = 40f, right = 180f, bottom = 200f)
        val childTileRect = childTileScreenRectWithinDisplayTile(
            displayTileId = displayTileId,
            displayScreenRect = displayRect,
            childTileId = DownloadTileId(zoom = 11, x = 201, y = 401),
        )

        assertEquals(100f, childTileRect.left, 0.001f)
        assertEquals(120f, childTileRect.top, 0.001f)
        assertEquals(180f, childTileRect.right, 0.001f)
        assertEquals(200f, childTileRect.bottom, 0.001f)
    }

    @Test
    fun tileGridRenderModelShowsCachedCoverageRectsForPartialTile() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )
        val config = TileContextConfig(downloadZoom = 10)
        val baseModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = emptyMap(),
        )
        val partialTile = baseModel.tiles.first { it.downloadRequests.size >= 2 }
        val cachedRequest = partialTile.downloadRequests.first()

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 1200f,
            canvasHeight = 800f,
            config = config,
            tileSnapshots = mapOf(
                cachedRequest.tileId to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = cachedRequest.estimatedBytes,
                    actualBytes = cachedRequest.estimatedBytes,
                ),
            ),
        )
        val renderedPartialTile = renderModel.tiles.first { it.tileId == partialTile.tileId }

        assertEquals(TileGridDownloadState.Partial, renderedPartialTile.downloadState)
        assertEquals(setOf(cachedRequest.tileId), renderedPartialTile.cachedTileIds)
        assertEquals(1, renderedPartialTile.cachedCoverageRects.size)
        assertTrue(renderedPartialTile.label == null)
        val coverageRect = renderedPartialTile.cachedCoverageRects.single()
        val epsilon = 0.001f
        assertTrue(coverageRect.left + epsilon >= renderedPartialTile.screenRect.left)
        assertTrue(coverageRect.top + epsilon >= renderedPartialTile.screenRect.top)
        assertTrue(coverageRect.right <= renderedPartialTile.screenRect.right + epsilon)
        assertTrue(coverageRect.bottom <= renderedPartialTile.screenRect.bottom + epsilon)
    }

    @Test
    fun tileGridRenderModelDoesNotPaintOffscreenCachedChildInsideViewportProxyTile() {
        val anchorTile = DownloadTileId(zoom = 10, x = 512, y = 512)
        val anchorBounds = tileGeoBounds(anchorTile)
        val centerLat = (anchorBounds.south + anchorBounds.north) / 2.0
        val centerLon = (anchorBounds.west + anchorBounds.east) / 2.0
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = centerLat, lon = centerLon - 0.0001),
                    GeoPoint(lat = centerLat, lon = centerLon + 0.0001),
                ),
            ),
        )
        val config = TileContextConfig(
            downloadZoom = 10,
            resolutionPolicy = TileResolutionPolicy(
                displayZoomBands = listOf(
                    TileDisplayZoomBand(minimumWindowWidthMeters = 0.0, displayZoom = 10),
                ),
                minimumDataZoom = 12,
                dataZoomOffsetFromDisplay = 1,
                maximumDataZoom = 16,
            ),
        )
        val offscreenCachedChild = DownloadTileId(
            zoom = 12,
            x = anchorTile.x shl 2,
            y = anchorTile.y shl 2,
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 400f,
            canvasHeight = 240f,
            config = config,
            tileSnapshots = mapOf(
                offscreenCachedChild to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    actualBytes = 1L,
                ),
            ),
        )

        val tile = renderModel.tiles.single()
        assertEquals(TileGridOutlineStyle.ViewProxyDashed, tile.outlineStyle)
        assertTrue(tile.cachedTileIds.isEmpty())
        assertTrue(tile.cachedCoverageRects.isEmpty())
        assertTrue(tile.downloadRequests.isNotEmpty())
        assertEquals(null, tile.downloadState)
    }

    @Test
    fun proxyDisplayTileRepresentsOnlyVisibleChildRequests() {
        val anchorTile = DownloadTileId(zoom = 10, x = 512, y = 512)
        val anchorBounds = tileGeoBounds(anchorTile)
        val centerLat = (anchorBounds.south + anchorBounds.north) / 2.0
        val centerLon = (anchorBounds.west + anchorBounds.east) / 2.0
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = centerLat, lon = centerLon - 0.0001),
                    GeoPoint(lat = centerLat, lon = centerLon + 0.0001),
                ),
            ),
        )
        val config = TileContextConfig(
            downloadZoom = 10,
            resolutionPolicy = TileResolutionPolicy(
                displayZoomBands = listOf(
                    TileDisplayZoomBand(minimumWindowWidthMeters = 0.0, displayZoom = 10),
                ),
                minimumDataZoom = 12,
                dataZoomOffsetFromDisplay = 1,
                maximumDataZoom = 16,
            ),
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 400f,
            canvasHeight = 240f,
            config = config,
            tileSnapshots = emptyMap(),
        )

        val tile = renderModel.tiles.single()
        assertEquals(TileGridOutlineStyle.ViewProxyDashed, tile.outlineStyle)
        assertTrue(tile.downloadRequests.isNotEmpty())
        tile.downloadRequests.forEach { request ->
            val childRect = projectedBoundsToScreenRect(
                projectedBounds = projectedBoundsForGeoBounds(
                    tileGeoBounds(request.tileId),
                    route.projection,
                ),
                viewBounds = route.bounds,
                canvasWidth = 400f,
                canvasHeight = 240f,
            )
            assertTrue(childRect.intersects(tile.screenRect))
        }
    }

    @Test
    fun proxyDisplayTileIsNotSelectedByHiddenCachedChild() {
        val anchorTile = DownloadTileId(zoom = 10, x = 512, y = 512)
        val anchorBounds = tileGeoBounds(anchorTile)
        val centerLat = (anchorBounds.south + anchorBounds.north) / 2.0
        val centerLon = (anchorBounds.west + anchorBounds.east) / 2.0
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = centerLat, lon = centerLon - 0.0001),
                    GeoPoint(lat = centerLat, lon = centerLon + 0.0001),
                ),
            ),
        )
        val config = TileContextConfig(
            downloadZoom = 10,
            resolutionPolicy = TileResolutionPolicy(
                displayZoomBands = listOf(
                    TileDisplayZoomBand(minimumWindowWidthMeters = 0.0, displayZoom = 10),
                ),
                minimumDataZoom = 12,
                dataZoomOffsetFromDisplay = 1,
                maximumDataZoom = 16,
            ),
        )
        val offscreenCachedChild = DownloadTileId(
            zoom = 12,
            x = anchorTile.x shl 2,
            y = anchorTile.y shl 2,
        )

        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 400f,
            canvasHeight = 240f,
            config = config,
            tileSnapshots = mapOf(
                offscreenCachedChild to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    actualBytes = 1L,
                ),
            ),
            selectedTileIds = setOf(offscreenCachedChild),
        )

        val tile = renderModel.tiles.single()
        assertFalse(tile.selected)
        assertTrue(tile.cachedTileIds.isEmpty())
    }

    @Test
    fun displayTileShowsCachedCoverageFromCoarserParentFallback() {
        val anchorTile = DownloadTileId(zoom = 10, x = 512, y = 512)
        val anchorBounds = tileGeoBounds(anchorTile)
        val centerLat = (anchorBounds.south + anchorBounds.north) / 2.0
        val centerLon = (anchorBounds.west + anchorBounds.east) / 2.0
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = centerLat, lon = centerLon - 0.0001),
                    GeoPoint(lat = centerLat, lon = centerLon + 0.0001),
                ),
            ),
        )
        val config = TileContextConfig(
            downloadZoom = 10,
            resolutionPolicy = TileResolutionPolicy(
                displayZoomBands = listOf(
                    TileDisplayZoomBand(minimumWindowWidthMeters = 0.0, displayZoom = 11),
                ),
                minimumDataZoom = 12,
                dataZoomOffsetFromDisplay = 1,
                maximumDataZoom = 16,
            ),
        )
        val renderModel = buildTileGridRenderModel(
            routeModel = route,
            bounds = route.bounds,
            canvasWidth = 400f,
            canvasHeight = 240f,
            config = config,
            tileSnapshots = mapOf(
                anchorTile to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 1L,
                    actualBytes = 1L,
                ),
            ),
            selectedTileIds = setOf(anchorTile),
        )

        val tile = renderModel.tiles.first { renderedTile ->
            renderedTile.cachedTileIds.contains(anchorTile)
        }
        assertTrue(tile.cachedTileIds.contains(anchorTile))
        assertEquals(1, tile.cachedCoverageRects.size)
        assertEquals(TileGridDownloadState.Partial, tile.downloadState)
        assertTrue(tile.selected)
    }

    @Test
    fun downloadingSnapshotCanReachFullProgress() {
        val snapshot = TileDownloadSnapshot(
            status = TileDownloadStatus.Downloading,
            estimatedBytes = 100L,
            downloadedBytes = 100L,
            actualBytes = 100L,
        )

        assertEquals(1.0f, snapshot.progressFraction ?: 0f, 0.0001f)
    }

    @Test
    fun oversizedTileDownloadFailsBeforeBufferingUnboundedData() {
        ensureTileDownloadWithinSizeLimit(
            byteCount = 1024L,
            limitBytes = 2048L,
        )

        assertThrows(java.io.IOException::class.java) {
            ensureTileDownloadWithinSizeLimit(
                byteCount = 2049L,
                limitBytes = 2048L,
            )
        }
    }

    @Test
    fun buildRouteTileMetricsIndexContainsOnlyIntersectingTiles() {
        val route = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.5),
                ),
            ),
        )

        val metricsByTile = buildRouteTileMetricsIndex(
            routeModel = route,
            config = TileContextConfig(downloadZoom = 10),
        )

        assertTrue(metricsByTile.isNotEmpty())
        assertTrue(metricsByTile.values.all { it.intersectsRoute })
    }

    @Test
    fun normalizeOverpassTilePackKeepsOnlyMinimalSupportedFeatures() {
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 512, y = 512),
            config = DefaultTileContextConfig,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 100,
                      "tags": {
                        "highway": "cycleway",
                        "surface": "asphalt",
                        "lanes": "2",
                        "name": "River Path"
                      },
                      "geometry": [
                        {"lat": 0.0, "lon": 0.0},
                        {"lat": 0.0, "lon": 0.001}
                      ]
                    },
                    {
                      "type": "node",
                      "id": 200,
                      "lat": 0.0004,
                      "lon": 0.0004,
                      "tags": {
                        "amenity": "drinking_water",
                        "name": "Pump",
                        "opening_hours": "24/7"
                      }
                    },
                    {
                      "type": "relation",
                      "id": 300,
                      "tags": {"tourism": "picnic_site"}
                    }
                  ]
                }
            """.trimIndent(),
            fetchedAtMillis = 1234L,
        )

        assertEquals(2, pack.features.size)
        val way = pack.features.first { it.featureId == "way/100" }
        val point = pack.features.first { it.featureId == "node/200" }

        assertEquals(TileGeometryKind.Way, way.geometryKind)
        assertEquals("cycleway", way.tags["highway"])
        assertEquals("asphalt", way.tags["surface"])
        assertFalse(way.tags.containsKey("lanes"))

        assertEquals(TileGeometryKind.Point, point.geometryKind)
        assertEquals("drinking_water", point.tags["amenity"])
        assertEquals("Pump", point.tags["name"])
        assertFalse(point.tags.containsKey("opening_hours"))
        assertEquals(1234L, pack.fetchedAtMillis)
    }

    @Test
    fun normalizeOverpassTilePackIgnoresNullGeometryEntries() {
        val pack = normalizeOverpassTilePack(
            tileId = DownloadTileId(zoom = 10, x = 571, y = 357),
            config = DefaultTileContextConfig,
            overpassJson = """
                {
                  "elements": [
                    {
                      "type": "way",
                      "id": 101,
                      "tags": {
                        "highway": "path"
                      },
                      "geometry": [
                        {"lat": -1.0, "lon": 36.8},
                        null,
                        {"lat": -1.0005, "lon": 36.8005}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            fetchedAtMillis = 4321L,
        )

        assertEquals(1, pack.features.size)
        assertEquals(2, pack.features.single().geometry.size)
    }

    @Test
    fun tileContextPackJsonRoundTrips() {
        val original = TileContextPack(
            tileId = DownloadTileId(zoom = 10, x = 512, y = 512),
            queryBounds = GeoBounds(west = 36.8, south = -1.3, east = 36.9, north = -1.2),
            fetchedAtMillis = 999L,
            features = listOf(
                TileContextFeature(
                    featureId = "node/1",
                    geometryKind = TileGeometryKind.Point,
                    tags = mapOf("amenity" to "toilets"),
                    geometry = listOf(GeoPoint(lat = -1.25, lon = 36.85)),
                ),
            ),
        )

        val restored = tileContextPackFromJson(original.toJsonString())

        assertEquals(original, restored)
    }

    @Test
    fun tileDownloadCancellationRunsCallbacksAndThrowsOnCheck() {
        val cancellation = TileDownloadCancellation()
        var callbackCount = 0

        cancellation.onCancel { callbackCount += 1 }
        cancellation.cancel()
        cancellation.onCancel { callbackCount += 1 }

        assertEquals(2, callbackCount)
        assertThrows(TileDownloadCancelledException::class.java) {
            cancellation.throwIfCancelled()
        }
    }
}
