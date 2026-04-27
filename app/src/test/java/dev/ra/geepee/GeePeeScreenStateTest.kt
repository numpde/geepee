package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeePeeScreenStateTest {
    @Test
    fun buildMovementViewState_prefersViewportFocusForLiveView() {
        val viewportFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24211, 36.83407),
            windowWidthMeters = 150.0,
            projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
        )
        val fallbackPoint = GeoPoint(-1.25, 36.83)

        val state = buildMovementViewState(
            movementMode = true,
            viewportFocus = viewportFocus,
            setupBounds = Bounds(-10.0, 10.0, -5.0, 5.0),
            routeScale = RouteScale.TwoHundred,
            currentReferenceGeoPoint = fallbackPoint,
            hasAnalysis = true,
        )

        assertEquals(viewportFocus.projectedBounds, state.tileGridBounds)
        assertEquals(viewportFocus.windowWidthMeters, state.windowWidthMeters, 0.0)
        assertEquals(viewportFocus.centerGeoPoint, state.openInPoint)
        assertEquals(viewportFocus, state.effectiveMapInfoFocus)
    }

    @Test
    fun buildMovementViewState_suppressesMapInfoFocusWithoutAnalysis() {
        val viewportFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24211, 36.83407),
            windowWidthMeters = 150.0,
            projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
        )

        val state = buildMovementViewState(
            movementMode = true,
            viewportFocus = viewportFocus,
            setupBounds = null,
            routeScale = RouteScale.TwoHundred,
            currentReferenceGeoPoint = null,
            hasAnalysis = false,
        )

        assertNull(state.effectiveMapInfoFocus)
        assertEquals(viewportFocus.projectedBounds, state.tileGridBounds)
        assertEquals(viewportFocus.centerGeoPoint, state.openInPoint)
    }

    @Test
    fun buildMovementViewState_usesSetupFallbacksOutsideMovementMode() {
        val setupBounds = Bounds(-10.0, 10.0, -5.0, 5.0)
        val fallbackPoint = GeoPoint(-1.25, 36.83)

        val state = buildMovementViewState(
            movementMode = false,
            viewportFocus = MapInfoFocus(
                centerGeoPoint = GeoPoint(-1.24211, 36.83407),
                windowWidthMeters = 150.0,
                projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
            ),
            setupBounds = setupBounds,
            routeScale = RouteScale.FiveHundred,
            currentReferenceGeoPoint = fallbackPoint,
            hasAnalysis = true,
        )

        assertEquals(setupBounds, state.tileGridBounds)
        assertEquals(RouteScale.FiveHundred.windowWidthMeters, state.windowWidthMeters, 0.0)
        assertEquals(fallbackPoint, state.openInPoint)
        assertNull(state.effectiveMapInfoFocus)
    }

    @Test
    fun previewTileSelectionState_longPressOnCachedTileStartsSelectionMode() {
        val cachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )

        val state = PreviewTileSelectionState().onLongPress(cachedTile)

        assertTrue(state.selectionModeActive)
        assertEquals(setOf(cachedTile.tileId), state.selectedTileIds)
    }

    @Test
    fun previewTileSelectionState_tapOnCachedTileDoesNothingUntilSelectionModeIsActive() {
        val cachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )

        val result = PreviewTileSelectionState().onTap(cachedTile)

        assertEquals(PreviewTileSelectionState(), result.selectionState)
        assertNull(result.downloadRequest)
    }

    @Test
    fun previewTileSelectionState_tapOnUncachedTileRequestsDownloadOutsideSelectionMode() {
        val uncachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 3, y = 4),
            status = null,
            estimatedBytes = 123_000L,
        )

        val result = PreviewTileSelectionState().onTap(uncachedTile)

        assertEquals(PreviewTileSelectionState(), result.selectionState)
        assertEquals(
            PreviewTileDownloadRequest(
                tileId = uncachedTile.tileId,
                estimatedBytes = uncachedTile.estimatedBytes,
            ),
            result.downloadRequest,
        )
    }

    @Test
    fun previewTileSelectionState_tapTogglesCachedTilesWhileSelectionModeIsActive() {
        val firstTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )
        val secondTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 5, y = 6),
            status = TileDownloadStatus.Cached,
        )

        val initialState = PreviewTileSelectionState(setOf(firstTile.tileId))

        val secondTileResult = initialState.onTap(secondTile)
        assertEquals(
            setOf(firstTile.tileId, secondTile.tileId),
            secondTileResult.selectionState.selectedTileIds,
        )
        assertNull(secondTileResult.downloadRequest)

        val deselectResult = secondTileResult.selectionState.onTap(firstTile)
        assertEquals(setOf(secondTile.tileId), deselectResult.selectionState.selectedTileIds)
        assertNull(deselectResult.downloadRequest)
    }

    @Test
    fun previewTileSelectionState_tapOnUncachedTileDoesNothingDuringSelectionMode() {
        val cachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )
        val uncachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 7, y = 8),
            status = null,
            estimatedBytes = 321_000L,
        )

        val result = PreviewTileSelectionState(setOf(cachedTile.tileId)).onTap(uncachedTile)

        assertEquals(setOf(cachedTile.tileId), result.selectionState.selectedTileIds)
        assertNull(result.downloadRequest)
    }

    @Test
    fun previewTileSelectionState_retainCachedDropsTilesThatAreNoLongerCached() {
        val cachedTileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val staleTileId = DownloadTileId(zoom = 10, x = 3, y = 4)

        val state = PreviewTileSelectionState(setOf(cachedTileId, staleTileId)).retainCached(
            tileSnapshots = mapOf(
                cachedTileId to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 100_000L,
                ),
                staleTileId to TileDownloadSnapshot(
                    status = TileDownloadStatus.Downloading,
                    estimatedBytes = 200_000L,
                    downloadedBytes = 50_000L,
                ),
            ),
        )

        assertEquals(setOf(cachedTileId), state.selectedTileIds)
    }
}

private fun previewTile(
    tileId: DownloadTileId,
    status: TileDownloadStatus?,
    estimatedBytes: Long = 180_000L,
): TileGridDisplayTile {
    return TileGridDisplayTile(
        tileId = tileId,
        screenRect = ScreenRect(0f, 0f, 10f, 10f),
        routeMetrics = TileRouteMetrics(
            intersectsRoute = true,
            intersectingEdgeCount = 1,
            intersectingRouteMeters = 100.0,
        ),
        snapshot = status?.let {
            TileDownloadSnapshot(
                status = it,
                estimatedBytes = estimatedBytes,
            )
        },
        selected = false,
        estimatedBytes = estimatedBytes,
        label = null,
    )
}
