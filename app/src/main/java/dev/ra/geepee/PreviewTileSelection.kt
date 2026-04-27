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
            resolvedSelection = selectionState.resolve(tileSnapshots),
        )
    }

    fun onTap(
        tile: TileGridDisplayTile?,
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileTapTransition {
        val tapResult = selectionState.resolve(tileSnapshots).onTap(tile)
        return PreviewTileTapTransition(
            uiState = copy(selectionState = tapResult.selectionState),
            downloadRequest = tapResult.downloadRequest,
        )
    }

    fun onLongPress(
        tile: TileGridDisplayTile?,
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileUiState {
        return copy(
            selectionState = selectionState.resolve(tileSnapshots).onLongPress(tile),
        )
    }

    fun requestDelete(
        buildPlan: (Set<DownloadTileId>) -> TileDeletePlan,
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileUiState {
        val selectedTileIds = selectionState.resolve(tileSnapshots).selectedTileIds
        return copy(pendingDeletePlan = buildPlan(selectedTileIds))
    }

    fun dismissDelete(): PreviewTileUiState = copy(pendingDeletePlan = null)

    fun confirmDelete(): PreviewTileDeleteConfirmation? {
        val plan = pendingDeletePlan ?: return null
        return PreviewTileDeleteConfirmation(
            plan = plan,
            nextState = copy(
                selectionState = selectionState.clear(),
                pendingDeletePlan = null,
            ),
        )
    }
}

internal data class PreviewTileSelectionState(
    val selectedTileIds: Set<DownloadTileId> = emptySet(),
) {
    fun retainCached(
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileSelectionState {
        val retainedTileIds = selectedTileIds.filterTo(linkedSetOf()) { tileId ->
            tileSnapshots[tileId]?.status == TileDownloadStatus.Cached
        }
        return if (retainedTileIds == selectedTileIds) {
            this
        } else {
            copy(selectedTileIds = retainedTileIds)
        }
    }

    fun resolve(
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): ResolvedPreviewTileSelectionState {
        val normalizedState = retainCached(tileSnapshots)
        return ResolvedPreviewTileSelectionState(normalizedState)
    }

    fun clear(): PreviewTileSelectionState = PreviewTileSelectionState()

    internal fun toggleCachedTile(tile: TileGridDisplayTile?): PreviewTileSelectionState {
        if (tile?.isCached != true) {
            return this
        }
        val nextSelectedTileIds = selectedTileIds.toMutableSet().also { tileIds ->
            if (!tileIds.add(tile.tileId)) {
                tileIds.remove(tile.tileId)
            }
        }.toSet()
        return copy(selectedTileIds = nextSelectedTileIds)
    }
}

internal data class ResolvedPreviewTileSelectionState(
    private val selectionState: PreviewTileSelectionState,
) {
    val selectedTileIds: Set<DownloadTileId>
        get() = selectionState.selectedTileIds

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

    fun onTap(tile: TileGridDisplayTile?): PreviewTileTapResult {
        if (tile == null) {
            return PreviewTileTapResult(selectionState)
        }
        return if (selectionModeActive) {
            PreviewTileTapResult(selectionState.toggleCachedTile(tile))
        } else if (tile.isCached) {
            PreviewTileTapResult(selectionState)
        } else {
            PreviewTileTapResult(
                selectionState = selectionState,
                downloadRequest = PreviewTileDownloadRequest(
                    tileId = tile.tileId,
                    estimatedBytes = tile.estimatedBytes,
                ),
            )
        }
    }

    fun onLongPress(tile: TileGridDisplayTile?): PreviewTileSelectionState {
        return selectionState.toggleCachedTile(tile)
    }
}

internal data class ResolvedPreviewTileUiState(
    private val uiState: PreviewTileUiState,
    private val resolvedSelection: ResolvedPreviewTileSelectionState,
) {
    val selectedTileIds: Set<DownloadTileId>
        get() = resolvedSelection.selectedTileIds

    val deleteTilesActionLabel: String
        get() = resolvedSelection.deleteTilesActionLabel

    val pendingDeletePlan: TileDeletePlan?
        get() = uiState.pendingDeletePlan
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
    val tileId: DownloadTileId,
    val estimatedBytes: Long,
)

private val TileGridDisplayTile.isCached: Boolean
    get() = snapshot?.status == TileDownloadStatus.Cached
