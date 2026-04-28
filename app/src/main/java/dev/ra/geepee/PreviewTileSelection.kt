package dev.ra.geepee

internal data class PreviewTileUiState(
    val selectionState: PreviewTileSelectionState = PreviewTileSelectionState(),
    val pendingDeletePlan: TileDeletePlan? = null,
) {
    fun resolve(
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): ResolvedPreviewTileUiState {
        return ResolvedPreviewTileUiState(
            uiState = this,
            selectionState = selectionState.resolve(tileSnapshots),
        )
    }
}

internal data class PreviewTileSelectionState(
    val selectedTileIds: Set<DownloadTileId> = emptySet(),
) {
    fun retainCached(
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileSelectionState {
        val retainedTileIds = tileSnapshots.selectedCachedTileIds(selectedTileIds)
        return if (retainedTileIds == selectedTileIds) {
            this
        } else {
            copy(selectedTileIds = retainedTileIds)
        }
    }

    fun resolve(
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileSelectionState = retainCached(tileSnapshots)

    fun clear(): PreviewTileSelectionState = PreviewTileSelectionState()

    val deleteMode: TileDeleteMode
        get() = if (selectedTileIds.isNotEmpty()) {
            TileDeleteMode.Selected
        } else {
            TileDeleteMode.Unused
        }

    val selectionModeActive: Boolean
        get() = deleteMode == TileDeleteMode.Selected

    val deleteTilesActionLabel: String
        get() = deleteMode.actionLabel

    internal fun toggleCachedTile(tile: TileGridDisplayTile?): PreviewTileSelectionState {
        if (tile?.hasCachedCoverage != true) {
            return this
        }
        return copy(selectedTileIds = tile.toggledSelection(selectedTileIds))
    }

    fun onTap(tile: TileGridDisplayTile?): PreviewTileTapResult {
        if (tile == null) {
            return PreviewTileTapResult(this)
        }
        return if (selectionModeActive) {
            PreviewTileTapResult(toggleCachedTile(tile))
        } else if (tile.hasCachedCoverage) {
            PreviewTileTapResult(this)
        } else if (tile.downloadRequests.isEmpty()) {
            PreviewTileTapResult(this)
        } else {
            PreviewTileTapResult(
                selectionState = this,
                downloadRequest = PreviewTileDownloadRequest(
                    tileRequests = tile.downloadRequests,
                ),
            )
        }
    }

    fun onLongPress(tile: TileGridDisplayTile?): PreviewTileSelectionState {
        return toggleCachedTile(tile)
    }
}

internal data class ResolvedPreviewTileUiState(
    private val uiState: PreviewTileUiState,
    private val selectionState: PreviewTileSelectionState,
) {
    val selectedTileIds: Set<DownloadTileId>
        get() = selectionState.selectedTileIds

    val deleteTilesActionLabel: String
        get() = selectionState.deleteTilesActionLabel

    val pendingDeletePlan: TileDeletePlan?
        get() = uiState.pendingDeletePlan

    fun onTap(tile: TileGridDisplayTile?): PreviewTileTapTransition {
        val tapResult = selectionState.onTap(tile)
        return PreviewTileTapTransition(
            uiState = uiState.copy(selectionState = tapResult.selectionState),
            downloadRequest = tapResult.downloadRequest,
        )
    }

    fun onLongPress(tile: TileGridDisplayTile?): PreviewTileUiState {
        return uiState.copy(
            selectionState = selectionState.onLongPress(tile),
        )
    }

    fun requestDelete(
        buildPlan: (Set<DownloadTileId>) -> TileDeletePlan,
    ): PreviewTileUiState {
        return uiState.copy(
            pendingDeletePlan = buildPlan(selectedTileIds),
        )
    }

    fun dismissDelete(): PreviewTileUiState = uiState.copy(pendingDeletePlan = null)

    fun confirmDelete(): PreviewTileDeleteConfirmation? {
        val plan = uiState.pendingDeletePlan ?: return null
        return PreviewTileDeleteConfirmation(
            plan = plan,
            nextState = uiState.copy(
                selectionState = uiState.selectionState.clear(),
                pendingDeletePlan = null,
            ),
        )
    }
}

internal data class PreviewTileTapResult(
    val selectionState: PreviewTileSelectionState,
    val downloadRequest: PreviewTileDownloadRequest? = null,
)

internal data class PreviewTileTapTransition(
    val uiState: PreviewTileUiState,
    val downloadRequest: PreviewTileDownloadRequest? = null,
)

internal data class PreviewTileDeleteConfirmation(
    val plan: TileDeletePlan,
    val nextState: PreviewTileUiState,
)

internal data class PreviewTileDownloadRequest(
    val tileRequests: List<TileDownloadRequest>,
)
