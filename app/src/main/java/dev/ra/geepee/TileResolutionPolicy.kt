package dev.ra.geepee

internal data class TileDisplayZoomBand(
    val minimumWindowWidthMeters: Double,
    val displayZoom: Int,
)

internal data class TileResolutionPolicy(
    val displayZoomBands: List<TileDisplayZoomBand> = defaultTileDisplayZoomBands(),
    val minimumDataZoom: Int = 12,
    val dataZoomOffsetFromDisplay: Int = 1,
    val maximumDataZoom: Int = 16,
)

internal data class TileResolution(
    val displayZoom: Int,
    val dataZoom: Int,
)

internal fun resolveTileResolution(
    windowWidthMeters: Double,
    policy: TileResolutionPolicy,
): TileResolution {
    val resolvedDisplayZoom = policy.displayZoomBands
        .sortedByDescending(TileDisplayZoomBand::minimumWindowWidthMeters)
        .firstOrNull { band -> windowWidthMeters >= band.minimumWindowWidthMeters }
        ?.displayZoom
        ?: policy.displayZoomBands.minOf(TileDisplayZoomBand::displayZoom)
    val resolvedDataZoom = maxOf(
        policy.minimumDataZoom,
        resolvedDisplayZoom + policy.dataZoomOffsetFromDisplay,
    ).coerceAtMost(policy.maximumDataZoom)
    return TileResolution(
        displayZoom = resolvedDisplayZoom,
        dataZoom = resolvedDataZoom,
    )
}

private fun defaultTileDisplayZoomBands(): List<TileDisplayZoomBand> {
    return listOf(
        TileDisplayZoomBand(minimumWindowWidthMeters = 2_500.0, displayZoom = 10),
        TileDisplayZoomBand(minimumWindowWidthMeters = 1_000.0, displayZoom = 11),
        TileDisplayZoomBand(minimumWindowWidthMeters = 350.0, displayZoom = 12),
        TileDisplayZoomBand(minimumWindowWidthMeters = 120.0, displayZoom = 13),
        TileDisplayZoomBand(minimumWindowWidthMeters = 0.0, displayZoom = 14),
    )
}
