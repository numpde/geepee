package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTileSelectionStateTest {
    @Test
    fun previewTileUiStateRequestDeleteUsesNormalizedSelectedTiles() {
        val cachedTileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val staleTileId = DownloadTileId(zoom = 10, x = 3, y = 4)
        val snapshot = TileDownloadSnapshot(
            status = TileDownloadStatus.Cached,
            estimatedBytes = 100_000L,
        )

        val uiState = PreviewTileUiState(
            selectionState = PreviewTileSelectionState(setOf(cachedTileId, staleTileId)),
        ).requestDelete(
            buildPlan = { selectedTileIds ->
                TileDeletePlan(
                    mode = TileDeleteMode.Selected,
                    tileIds = selectedTileIds,
                    freedBytes = 200_000L,
                )
            },
            tileSnapshots = mapOf(cachedTileId to snapshot),
        )

        assertEquals(setOf(cachedTileId), requireNotNull(uiState.pendingDeletePlan).tileIds)
    }

    @Test
    fun previewTileUiStateConfirmDeleteClearsSelectionAndPlan() {
        val tileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val uiState = PreviewTileUiState(
            selectionState = PreviewTileSelectionState(setOf(tileId)),
            pendingDeletePlan = TileDeletePlan(
                mode = TileDeleteMode.Selected,
                tileIds = setOf(tileId),
                freedBytes = 100_000L,
            ),
        )

        val confirmation = requireNotNull(uiState.confirmDelete())

        assertEquals(setOf(tileId), confirmation.plan.tileIds)
        assertEquals(emptySet<DownloadTileId>(), confirmation.nextState.selectionState.selectedTileIds)
        assertNull(confirmation.nextState.pendingDeletePlan)
    }

    @Test
    fun previewTileUiStateDismissDeleteClearsOnlyPendingPlan() {
        val tileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val uiState = PreviewTileUiState(
            selectionState = PreviewTileSelectionState(setOf(tileId)),
            pendingDeletePlan = TileDeletePlan(
                mode = TileDeleteMode.Selected,
                tileIds = setOf(tileId),
                freedBytes = 100_000L,
            ),
        )

        val dismissedState = uiState.dismissDelete()

        assertEquals(setOf(tileId), dismissedState.selectionState.selectedTileIds)
        assertNull(dismissedState.pendingDeletePlan)
    }

    @Test
    fun resolveUsesUnusedDeleteLabelWhenNothingIsSelected() {
        val resolved = PreviewTileSelectionState().resolve(emptyMap())

        assertEquals(TileDeleteMode.Unused, resolved.deleteMode)
        assertEquals("Delete unused tiles", resolved.deleteTilesActionLabel)
        assertTrue(!resolved.selectionModeActive)
    }

    @Test
    fun resolveUsesSelectedDeleteLabelWhenSelectionRemains() {
        val tileId = DownloadTileId(zoom = 10, x = 1, y = 2)

        val resolved = PreviewTileSelectionState(setOf(tileId)).resolve(
            mapOf(
                tileId to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 100_000L,
                ),
            ),
        )

        assertEquals(TileDeleteMode.Selected, resolved.deleteMode)
        assertEquals("Delete selected tiles", resolved.deleteTilesActionLabel)
        assertTrue(resolved.selectionModeActive)
    }

    @Test
    fun onLongPressOnCachedTileStartsSelectionMode() {
        val cachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )

        val state = PreviewTileSelectionState()
            .resolve(emptyMap())
            .onLongPress(cachedTile)

        assertEquals(setOf(cachedTile.tileId), state.selectedTileIds)
    }

    @Test
    fun onTapOnCachedTileDoesNothingUntilSelectionModeIsActive() {
        val cachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )

        val result = PreviewTileSelectionState()
            .resolve(emptyMap())
            .onTap(cachedTile)

        assertEquals(PreviewTileSelectionState(), result.selectionState)
        assertNull(result.downloadRequest)
    }

    @Test
    fun onTapOnUncachedTileRequestsDownloadOutsideSelectionMode() {
        val uncachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 3, y = 4),
            status = null,
            estimatedBytes = 123_000L,
        )

        val result = PreviewTileSelectionState()
            .resolve(emptyMap())
            .onTap(uncachedTile)

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
    fun onTapTogglesCachedTilesWhileSelectionModeIsActive() {
        val firstTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )
        val secondTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 5, y = 6),
            status = TileDownloadStatus.Cached,
        )

        val initialState = PreviewTileSelectionState(setOf(firstTile.tileId))
        val tileSnapshots = mapOf(
            firstTile.tileId to requireNotNull(firstTile.snapshot),
            secondTile.tileId to requireNotNull(secondTile.snapshot),
        )

        val secondTileResult = initialState
            .resolve(tileSnapshots)
            .onTap(secondTile)
        assertEquals(
            setOf(firstTile.tileId, secondTile.tileId),
            secondTileResult.selectionState.selectedTileIds,
        )
        assertNull(secondTileResult.downloadRequest)

        val deselectResult = secondTileResult.selectionState
            .resolve(tileSnapshots)
            .onTap(firstTile)
        assertEquals(setOf(secondTile.tileId), deselectResult.selectionState.selectedTileIds)
        assertNull(deselectResult.downloadRequest)
    }

    @Test
    fun onTapOnUncachedTileDoesNothingDuringSelectionMode() {
        val cachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 1, y = 2),
            status = TileDownloadStatus.Cached,
        )
        val uncachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 7, y = 8),
            status = null,
            estimatedBytes = 321_000L,
        )

        val result = PreviewTileSelectionState(setOf(cachedTile.tileId))
            .resolve(
                mapOf(
                cachedTile.tileId to requireNotNull(cachedTile.snapshot),
                ),
            )
            .onTap(uncachedTile)

        assertEquals(setOf(cachedTile.tileId), result.selectionState.selectedTileIds)
        assertNull(result.downloadRequest)
    }

    @Test
    fun retainCachedDropsTilesThatAreNoLongerCached() {
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

    @Test
    fun onTapNormalizesStaleSelectionBeforeHandlingTap() {
        val staleTileId = DownloadTileId(zoom = 10, x = 3, y = 4)
        val uncachedTile = previewTile(
            tileId = DownloadTileId(zoom = 10, x = 7, y = 8),
            status = null,
            estimatedBytes = 321_000L,
        )

        val result = PreviewTileSelectionState(setOf(staleTileId))
            .resolve(
                mapOf(
                staleTileId to TileDownloadSnapshot(
                    status = TileDownloadStatus.Downloading,
                    estimatedBytes = 200_000L,
                    downloadedBytes = 50_000L,
                ),
                ),
            )
            .onTap(uncachedTile)

        assertEquals(PreviewTileSelectionState(), result.selectionState)
        assertEquals(
            PreviewTileDownloadRequest(
                tileId = uncachedTile.tileId,
                estimatedBytes = uncachedTile.estimatedBytes,
            ),
            result.downloadRequest,
        )
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
