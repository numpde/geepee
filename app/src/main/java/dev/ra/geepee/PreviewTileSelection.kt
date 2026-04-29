package dev.ra.geepee

internal data class PreviewTileUiState(
    val selectionState: PreviewTileSelectionState = PreviewTileSelectionState(),
    val pendingDeletePlan: TileDeletePlan? = null,
    val consumedTooLargeRequestKeys: Set<PreviewTileRequestKey> = emptySet(),
) {
    fun resolve(
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileUiState {
        val resolvedSelectionState = selectionState.resolve(tileSnapshots)
        val resolvedTooLargeRequestKeys = consumedTooLargeRequestKeys.filterTo(linkedSetOf()) { requestKey ->
            requestKey.isStillTooLarge(tileSnapshots)
        }
        return if (
            resolvedSelectionState == selectionState &&
            resolvedTooLargeRequestKeys == consumedTooLargeRequestKeys
        ) {
            this
        } else {
            copy(
                selectionState = resolvedSelectionState,
                consumedTooLargeRequestKeys = resolvedTooLargeRequestKeys,
            )
        }
    }

    val selectedTileIds: Set<DownloadTileId>
        get() = selectionState.selectedTileIds

    val deleteTilesActionLabel: String
        get() = selectionState.deleteTilesActionLabel

    fun onTap(tile: TileGridDisplayTile?): PreviewTileTapOutcome<PreviewTileUiState> {
        if (!selectionState.selectionModeActive && tile?.downloadState == TileGridDownloadState.TooLarge) {
            return onTooLargeTileTap(tile)
        }
        val tapResult = selectionState.onTap(tile)
        return PreviewTileTapOutcome(
            state = copy(selectionState = tapResult.state),
            downloadRequest = tapResult.downloadRequest,
        )
    }

    private fun onTooLargeTileTap(tile: TileGridDisplayTile): PreviewTileTapOutcome<PreviewTileUiState> {
        val requestSet = tile.toRequestSet() ?: return PreviewTileTapOutcome(this)
        return if (requestSet.key in consumedTooLargeRequestKeys) {
            PreviewTileTapOutcome(
                state = this,
                downloadRequest = requestSet.downloadRequest,
            )
        } else {
            PreviewTileTapOutcome(
                state = copy(consumedTooLargeRequestKeys = consumedTooLargeRequestKeys + requestSet.key),
                zoomRequest = PreviewTileZoomRequest,
            )
        }
    }

    fun onLongPress(tile: TileGridDisplayTile?): PreviewTileUiState {
        return copy(
            selectionState = selectionState.onLongPress(tile),
        )
    }

    fun requestDelete(
        buildPlan: (Set<DownloadTileId>) -> TileDeletePlan,
    ): PreviewTileUiState {
        return copy(
            pendingDeletePlan = buildPlan(selectedTileIds),
        )
    }

    fun dismissDelete(): PreviewTileUiState = copy(pendingDeletePlan = null)

    fun confirmDelete(): PreviewTileUiState? {
        pendingDeletePlan ?: return null
        return copy(
            selectionState = selectionState.clear(),
            pendingDeletePlan = null,
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

    fun onTap(tile: TileGridDisplayTile?): PreviewTileTapOutcome<PreviewTileSelectionState> {
        if (tile == null) {
            return PreviewTileTapOutcome(this)
        }
        val downloadRequest = tile.toDownloadRequest()
        return if (selectionModeActive) {
            PreviewTileTapOutcome(toggleCachedTile(tile))
        } else if (tile.hasCachedCoverage) {
            PreviewTileTapOutcome(this)
        } else if (downloadRequest == null) {
            PreviewTileTapOutcome(this)
        } else {
            PreviewTileTapOutcome(
                state = this,
                downloadRequest = downloadRequest,
            )
        }
    }

    fun onLongPress(tile: TileGridDisplayTile?): PreviewTileSelectionState {
        return toggleCachedTile(tile)
    }
}

internal data class PreviewTileTapOutcome<T>(
    val state: T,
    val downloadRequest: PreviewTileDownloadRequest? = null,
    val zoomRequest: PreviewTileZoomRequest? = null,
)

internal data class PreviewTileDownloadRequest(
    val tileRequests: List<TileDownloadRequest>,
) {
    companion object {
        fun from(tileRequests: List<TileDownloadRequest>): PreviewTileDownloadRequest? =
            tileRequests
                .takeIf(List<TileDownloadRequest>::isNotEmpty)
                ?.let(::PreviewTileDownloadRequest)
    }
}

internal data object PreviewTileZoomRequest

internal data class PreviewTileRequestKey(
    val tileIds: Set<DownloadTileId>,
) {
    fun isStillTooLarge(tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>): Boolean {
        return tileIds.isNotEmpty() && tileIds.all { tileId ->
            tileSnapshots[tileId]?.isTooLarge == true
        }
    }
}

private class PreviewTileRequestSet private constructor(
    val key: PreviewTileRequestKey,
    val downloadRequest: PreviewTileDownloadRequest,
) {
    companion object {
        fun from(requests: List<TileDownloadRequest>): PreviewTileRequestSet? {
            val downloadRequest = PreviewTileDownloadRequest.from(requests) ?: return null
            val tileIds = downloadRequest.tileRequests.mapTo(linkedSetOf()) { request -> request.tileId }
            return PreviewTileRequestSet(
                key = PreviewTileRequestKey(tileIds),
                downloadRequest = downloadRequest,
            )
        }
    }
}

private fun TileGridDisplayTile.toDownloadRequest(): PreviewTileDownloadRequest? =
    PreviewTileDownloadRequest.from(downloadRequests)

private fun TileGridDisplayTile.toRequestSet(): PreviewTileRequestSet? =
    PreviewTileRequestSet.from(downloadRequests)
