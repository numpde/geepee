package dev.ra.geepee

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

    val selectionModeActive: Boolean
        get() = selectedTileIds.isNotEmpty()

    val deleteTilesActionLabel: String
        get() = if (selectionModeActive) {
            "Delete selected tiles"
        } else {
            "Delete unused tiles"
        }

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

internal data class PreviewTileTapResult(
    val selectionState: PreviewTileSelectionState,
    val downloadRequest: PreviewTileDownloadRequest? = null,
)

internal data class PreviewTileDownloadRequest(
    val tileId: DownloadTileId,
    val estimatedBytes: Long,
)

private val TileGridDisplayTile.isCached: Boolean
    get() = snapshot?.status == TileDownloadStatus.Cached
