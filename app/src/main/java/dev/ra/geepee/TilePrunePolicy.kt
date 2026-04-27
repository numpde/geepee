package dev.ra.geepee

private const val DELETE_UNUSED_TILE_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

internal data class TilePrunePolicy(
    val protectedTileIds: Set<DownloadTileId> = emptySet(),
)

internal data class TilePruneResult(
    val deletedTileIds: Set<DownloadTileId> = emptySet(),
    val freedBytes: Long = 0L,
) {
    val deletedTileCount: Int
        get() = deletedTileIds.size
}

internal fun buildDeleteUnusedTilePrunePolicy(
    routeModel: RouteModel?,
    currentMapInfoFocus: MapInfoFocus?,
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
    config: TileContextConfig,
    nowMillis: Long = System.currentTimeMillis(),
    retentionMillis: Long = DELETE_UNUSED_TILE_RETENTION_MILLIS,
): TilePrunePolicy {
    return TilePrunePolicy(
        protectedTileIds = deleteUnusedProtectedTileIds(
            routeModel = routeModel,
            currentMapInfoFocus = currentMapInfoFocus,
            tileDownloads = tileDownloads,
            config = config,
            nowMillis = nowMillis,
            retentionMillis = retentionMillis,
        ),
    )
}

private fun deleteUnusedProtectedTileIds(
    routeModel: RouteModel?,
    currentMapInfoFocus: MapInfoFocus?,
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
    config: TileContextConfig,
    nowMillis: Long,
    retentionMillis: Long,
): Set<DownloadTileId> {
    val protectedTileIds = linkedSetOf<DownloadTileId>()
    val recentAccessCutoffMillis = nowMillis - retentionMillis

    protectedTileIds += tileDownloads
        .filterValues { snapshot -> snapshot.status == TileDownloadStatus.Downloading }
        .keys

    protectedTileIds += tileDownloads
        .filterValues { snapshot ->
            snapshot.status == TileDownloadStatus.Cached &&
                snapshot.lastAccessedAtMillis >= recentAccessCutoffMillis
        }
        .keys

    if (routeModel != null) {
        val cachedTileIds = tileDownloads
            .filterValues { snapshot -> snapshot.status == TileDownloadStatus.Cached }
            .keys
            .toSet()
        protectedTileIds += routeMapInfoWarmTileIds(
            routeModel = routeModel,
            cachedTileIds = cachedTileIds,
            config = config,
        )
        currentMapInfoFocus?.let { focus ->
            protectedTileIds += tilesIntersectingProjectedBounds(
                projection = routeModel.projection,
                bounds = expandedNearbyWayMapInfoBounds(
                    focus = focus,
                    config = config,
                ),
                zoom = config.downloadZoom,
            )
        }
    }

    return protectedTileIds
}
