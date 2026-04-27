package dev.ra.geepee

import java.util.Locale

private const val DELETE_UNUSED_TILE_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

internal data class TilePrunePolicy(
    val protectedTileIds: Set<DownloadTileId> = emptySet(),
)

internal enum class TileDeleteMode {
    Selected,
    Unused,
}

internal val TileDeleteMode.actionLabel: String
    get() = when (this) {
        TileDeleteMode.Selected -> "Delete selected tiles"
        TileDeleteMode.Unused -> "Delete unused tiles"
    }

internal val TileDeleteMode.confirmTitle: String
    get() = "$actionLabel?"

internal data class TileDeletePlan(
    val mode: TileDeleteMode,
    val tileIds: Set<DownloadTileId> = emptySet(),
    val freedBytes: Long = 0L,
) {
    val tileCount: Int
        get() = tileIds.size
}

internal data class TilePruneResult(
    val deletedTileIds: Set<DownloadTileId> = emptySet(),
    val freedBytes: Long = 0L,
) {
    val deletedTileCount: Int
        get() = deletedTileIds.size
}

internal fun formatStorageMegabytes(
    bytes: Long,
    approximate: Boolean = true,
): String {
    val prefix = if (approximate) "about " else ""
    val megabytes = bytes.coerceAtLeast(0L) / 1_000_000.0
    return String.format(Locale.US, "%s%.1f MB", prefix, megabytes)
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
