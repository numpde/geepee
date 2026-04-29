package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTileSelectionStateTest {
    @Test
    fun resolvedPreviewTileUiStateRequestDeleteUsesNormalizedSelectedTiles() {
        val cachedTileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val staleTileId = DownloadTileId(zoom = 10, x = 3, y = 4)
        val snapshot = TileDownloadSnapshot(
            status = TileDownloadStatus.Cached,
            estimatedBytes = 100_000L,
        )

        val nextState = PreviewTileUiState(
            selectionState = PreviewTileSelectionState(setOf(cachedTileId, staleTileId)),
        ).resolve(
            tileSnapshots = mapOf(cachedTileId to snapshot),
        ).requestDelete(
            buildPlan = { selectedTileIds ->
                TileDeletePlan(
                    mode = TileDeleteMode.Selected,
                    tileIds = selectedTileIds,
                    freedBytes = 200_000L,
                )
            },
        )

        assertEquals(setOf(cachedTileId), requireNotNull(nextState.pendingDeletePlan).tileIds)
    }

    @Test
    fun resolvedPreviewTileUiStateConfirmDeleteClearsSelectionAndPlan() {
        val tileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val nextState = requireNotNull(
            PreviewTileUiState(
                selectionState = PreviewTileSelectionState(setOf(tileId)),
                pendingDeletePlan = TileDeletePlan(
                    mode = TileDeleteMode.Selected,
                    tileIds = setOf(tileId),
                    freedBytes = 100_000L,
                ),
            ).resolve(emptyMap()).confirmDelete(),
        )

        assertEquals(emptySet<DownloadTileId>(), nextState.selectionState.selectedTileIds)
        assertNull(nextState.pendingDeletePlan)
    }

    @Test
    fun resolvedPreviewTileUiStateDismissDeleteClearsOnlyPendingPlan() {
        val tileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val dismissedState = PreviewTileUiState(
            selectionState = PreviewTileSelectionState(setOf(tileId)),
            pendingDeletePlan = TileDeletePlan(
                mode = TileDeleteMode.Selected,
                tileIds = setOf(tileId),
                freedBytes = 100_000L,
            ),
        ).resolve(emptyMap()).dismissDelete()

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

        assertEquals(PreviewTileSelectionState(), result.state)
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

        assertEquals(PreviewTileSelectionState(), result.state)
        assertEquals(
            PreviewTileDownloadRequest(
                tileRequests = uncachedTile.downloadRequests,
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
            firstTile.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = firstTile.estimatedBytes,
            ),
            secondTile.tileId to TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = secondTile.estimatedBytes,
            ),
        )

        val secondTileResult = initialState
            .resolve(tileSnapshots)
            .onTap(secondTile)
        assertEquals(
            setOf(firstTile.tileId, secondTile.tileId),
            secondTileResult.state.selectedTileIds,
        )
        assertNull(secondTileResult.downloadRequest)

        val deselectResult = secondTileResult.state
            .resolve(tileSnapshots)
            .onTap(firstTile)
        assertEquals(setOf(secondTile.tileId), deselectResult.state.selectedTileIds)
        assertNull(deselectResult.downloadRequest)
    }

    @Test
    fun onTapOnPartiallySelectedTileSelectsRemainingCachedCoverageThenClearsIt() {
        val firstTileId = DownloadTileId(zoom = 10, x = 1, y = 2)
        val secondTileId = DownloadTileId(zoom = 10, x = 1, y = 3)
        val tile = TileGridDisplayTile(
            tileId = DownloadTileId(zoom = 9, x = 0, y = 1),
            screenRect = ScreenRect(0f, 0f, 20f, 20f),
            routeMetrics = TileRouteMetrics(
                intersectsRoute = true,
                intersectingEdgeCount = 1,
                intersectingRouteMeters = 100.0,
            ),
            downloadState = TileGridDownloadState.Partial,
            progressFraction = null,
            representedCoverage = TileGridRepresentedCoverage(
                coverageTiles = listOf(
                    TileCoverageRect(firstTileId, ScreenRect(0f, 0f, 10f, 20f)),
                    TileCoverageRect(secondTileId, ScreenRect(10f, 0f, 20f, 20f)),
                ),
                selectedTileIds = setOf(firstTileId),
            ),
            downloadRequests = emptyList(),
            estimatedBytes = 100_000L,
            label = null,
        )
        val tileSnapshots = mapOf(
            firstTileId to TileDownloadSnapshot(status = TileDownloadStatus.Cached, estimatedBytes = 50_000L),
            secondTileId to TileDownloadSnapshot(status = TileDownloadStatus.Cached, estimatedBytes = 50_000L),
        )

        val fullySelected = PreviewTileSelectionState(setOf(firstTileId))
            .resolve(tileSnapshots)
            .onTap(tile)
        assertEquals(setOf(firstTileId, secondTileId), fullySelected.state.selectedTileIds)

        val cleared = fullySelected.state
            .resolve(tileSnapshots)
            .onTap(tile)
        assertEquals(emptySet<DownloadTileId>(), cleared.state.selectedTileIds)
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
                cachedTile.tileId to TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = cachedTile.estimatedBytes,
                ),
                ),
            )
            .onTap(uncachedTile)

        assertEquals(setOf(cachedTile.tileId), result.state.selectedTileIds)
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

        assertEquals(PreviewTileSelectionState(), result.state)
        assertEquals(
            PreviewTileDownloadRequest(
                tileRequests = uncachedTile.downloadRequests,
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
    val downloadRequests = listOf(
        TileDownloadRequest(
            tileId = tileId,
            estimatedBytes = estimatedBytes,
        ),
    )
    return TileGridDisplayTile(
        tileId = tileId,
        screenRect = ScreenRect(0f, 0f, 10f, 10f),
        routeMetrics = TileRouteMetrics(
            intersectsRoute = true,
            intersectingEdgeCount = 1,
            intersectingRouteMeters = 100.0,
        ),
        downloadState = when (status) {
            TileDownloadStatus.Downloading -> TileGridDownloadState.Downloading
            TileDownloadStatus.Cached -> TileGridDownloadState.Cached
            TileDownloadStatus.Error -> TileGridDownloadState.Error
            null -> null
        },
        progressFraction = null,
        representedCoverage = TileGridRepresentedCoverage(
            coverageTiles = if (status == TileDownloadStatus.Cached) {
                listOf(TileCoverageRect(tileId = tileId, screenRect = ScreenRect(0f, 0f, 10f, 10f)))
            } else {
                emptyList()
            },
        ),
        downloadRequests = downloadRequests,
        estimatedBytes = estimatedBytes,
        label = null,
    )
}
