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
        return ResolvedPreviewTileSelectionState(normalizedState.selectedTileIds)
    }

    fun onTap(
        tile: TileGridDisplayTile?,
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileTapResult {
        val normalizedState = retainCached(tileSnapshots)
        if (tile == null) {
            return PreviewTileTapResult(normalizedState)
        }
        return if (normalizedState.resolve(tileSnapshots).selectionModeActive) {
            PreviewTileTapResult(normalizedState.toggleCachedTile(tile))
        } else if (tile.isCached) {
            PreviewTileTapResult(normalizedState)
        } else {
            PreviewTileTapResult(
                selectionState = normalizedState,
                downloadRequest = PreviewTileDownloadRequest(
                    tileId = tile.tileId,
                    estimatedBytes = tile.estimatedBytes,
                ),
            )
        }
    }

    fun onLongPress(
        tile: TileGridDisplayTile?,
        tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    ): PreviewTileSelectionState {
        return retainCached(tileSnapshots).toggleCachedTile(tile)
    }

    fun clear(): PreviewTileSelectionState = PreviewTileSelectionState()

    private fun toggleCachedTile(tile: TileGridDisplayTile?): PreviewTileSelectionState {
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
    val selectedTileIds: Set<DownloadTileId>,
) {
    val selectionModeActive: Boolean
        get() = selectedTileIds.isNotEmpty()

    val deleteTilesActionLabel: String
        get() = if (selectionModeActive) {
            "Delete selected tiles"
        } else {
            "Delete unused tiles"
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
