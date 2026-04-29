package dev.ra.geepee

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val SELECTED_TILE_FILL_ALPHA = 0.06f
private const val PARTIALLY_SELECTED_TILE_FILL_ALPHA = 0.035f
private const val DOWNLOADING_TILE_FILL_ALPHA = 0.12f
private const val CACHED_TILE_FILL_ALPHA = 0.045f
private const val PARTIAL_TILE_FILL_ALPHA = 0.028f
private const val TOO_LARGE_TILE_FILL_ALPHA = 0.055f
private const val ERROR_TILE_FILL_ALPHA = 0.1f
private const val DOWNLOADING_COVERAGE_FILL_ALPHA = 0.1f
private const val CACHED_COVERAGE_FILL_ALPHA = 0.075f
private const val SELECTED_COVERAGE_FILL_ALPHA = 0.12f
private const val PARTIALLY_SELECTED_COVERAGE_FILL_ALPHA = 0.1f
private const val SELECTED_TILE_BORDER_ALPHA = 0.76f
private const val PARTIALLY_SELECTED_TILE_BORDER_ALPHA = 0.58f
private const val DOWNLOADING_TILE_BORDER_ALPHA = 0.48f
private const val PREVIEW_CACHED_TILE_BORDER_ALPHA = 0.32f
private const val LIVE_CACHED_TILE_BORDER_ALPHA = 0.22f
private const val PREVIEW_PARTIAL_TILE_BORDER_ALPHA = 0.24f
private const val LIVE_PARTIAL_TILE_BORDER_ALPHA = 0.18f
private const val TOO_LARGE_TILE_BORDER_ALPHA = 0.56f
private const val ERROR_TILE_BORDER_ALPHA = 0.54f
private const val PREVIEW_ROUTE_TILE_BORDER_ALPHA = 0.14f
private const val LIVE_ROUTE_TILE_BORDER_ALPHA = 0.08f
private const val PREVIEW_EMPTY_TILE_BORDER_ALPHA = 0.06f
private const val LIVE_EMPTY_TILE_BORDER_ALPHA = 0.035f

internal enum class TileGridVisualStyle(
    val showsFills: Boolean,
    val showsProgress: Boolean,
    val showsLabels: Boolean,
) {
    Preview(
        showsFills = true,
        showsProgress = true,
        showsLabels = true,
    ),
    LiveOverlay(
        showsFills = false,
        showsProgress = false,
        showsLabels = false,
    ),
}

internal data class TileGridCellPaint(
    val routeFill: Color,
    val stateFill: Color,
    val cachedCoverageFill: Color,
    val selectedCoverageFill: Color,
    val borderColor: Color,
    val borderWidthDp: Float,
)

@Composable
internal fun TileGridCanvas(
    model: TileGridRenderModel,
    visualStyle: TileGridVisualStyle = TileGridVisualStyle.Preview,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val density = LocalDensity.current
    val labelPaint = remember(density, colors.ink) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.ink.copy(alpha = 0.68f).toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 12.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Canvas(modifier = modifier) {
        model.tiles.forEach { tile ->
            drawTileCell(
                tile = tile,
                colors = colors,
                labelPaint = labelPaint,
                visualStyle = visualStyle,
            )
        }
    }
}

private fun DrawScope.drawTileCell(
    tile: TileGridDisplayTile,
    colors: GeePeeColors,
    labelPaint: Paint,
    visualStyle: TileGridVisualStyle,
) {
    val rect = tile.screenRect
    val topLeft = Offset(rect.left, rect.top)
    val size = Size(rect.width, rect.height)
    val cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
    val state = tile.downloadState
    val paint = tileGridCellPaint(
        tile = tile,
        colors = colors,
        visualStyle = visualStyle,
    )

    if (paint.routeFill.alpha > 0f) {
        drawRoundRect(
            color = paint.routeFill,
            topLeft = topLeft,
            size = size,
            cornerRadius = cornerRadius,
        )
    }

    if (paint.stateFill.alpha > 0f) {
        drawRoundRect(
            color = paint.stateFill,
            topLeft = topLeft,
            size = size,
            cornerRadius = cornerRadius,
        )
    }

    if (visualStyle.showsFills && tile.cachedCoverageRects.isNotEmpty()) {
        tile.cachedCoverageRects.forEach { coverageRect ->
            drawRect(
                color = paint.cachedCoverageFill,
                topLeft = Offset(coverageRect.left, coverageRect.top),
                size = Size(coverageRect.width, coverageRect.height),
            )
        }
        if (paint.selectedCoverageFill.alpha > 0f) {
            tile.selectedCoverageRects.forEach { coverageRect ->
                drawRect(
                    color = paint.selectedCoverageFill,
                    topLeft = Offset(coverageRect.left, coverageRect.top),
                    size = Size(coverageRect.width, coverageRect.height),
                )
            }
        }
    }

    drawRoundRect(
        color = paint.borderColor,
        topLeft = topLeft,
        size = size,
        cornerRadius = cornerRadius,
        style = Stroke(
            width = paint.borderWidthDp.dp.toPx(),
            pathEffect = if (tile.outlineStyle == TileGridOutlineStyle.ViewProxyDashed) {
                PathEffect.dashPathEffect(
                    intervals = floatArrayOf(16.dp.toPx(), 10.dp.toPx()),
                )
            } else {
                null
            },
        ),
    )

    if (visualStyle.showsProgress) {
        tile.progressFraction?.takeIf { state == TileGridDownloadState.Downloading }?.let { fraction ->
            val inset = 5.dp.toPx()
            val progressHeight = 5.dp.toPx()
            drawRoundRect(
                color = colors.routeAhead.copy(alpha = 0.92f),
                topLeft = Offset(rect.left + inset, rect.bottom - inset - progressHeight),
                size = Size((rect.width - inset * 2f) * fraction.coerceIn(0f, 1f), progressHeight),
                cornerRadius = CornerRadius(progressHeight, progressHeight),
            )
        }
    }

    if (visualStyle.showsLabels) {
        tile.label?.let { label ->
            drawContext.canvas.nativeCanvas.drawText(
                label,
                rect.left + rect.width / 2f,
                rect.top + rect.height / 2f + labelPaint.textSize * 0.35f,
                labelPaint,
            )
        }
    }
}

internal fun tileGridCellPaint(
    tile: TileGridDisplayTile,
    colors: GeePeeColors,
    visualStyle: TileGridVisualStyle,
): TileGridCellPaint {
    val selectionState = tile.selectionState
    return TileGridCellPaint(
        routeFill = routeTileFill(tile = tile, colors = colors, visualStyle = visualStyle),
        stateFill = tileStateFill(
            downloadState = tile.downloadState,
            selectionState = selectionState,
            colors = colors,
            visualStyle = visualStyle,
        ),
        cachedCoverageFill = cachedCoverageFill(
            downloadState = tile.downloadState,
            colors = colors,
            visualStyle = visualStyle,
        ),
        selectedCoverageFill = selectedCoverageFill(
            selectionState = selectionState,
            colors = colors,
            visualStyle = visualStyle,
        ),
        borderColor = tileBorderColor(
            tile = tile,
            selectionState = selectionState,
            colors = colors,
            visualStyle = visualStyle,
        ),
        borderWidthDp = tileBorderWidthDp(
            tile = tile,
            selectionState = selectionState,
            visualStyle = visualStyle,
        ),
    )
}

private fun routeTileFill(
    tile: TileGridDisplayTile,
    colors: GeePeeColors,
    visualStyle: TileGridVisualStyle,
): Color {
    return if (visualStyle.showsFills && tile.routeMetrics.intersectsRoute) {
        colors.ink.copy(alpha = 0.025f)
    } else {
        Color.Transparent
    }
}

private fun tileStateFill(
    downloadState: TileGridDownloadState?,
    selectionState: TileGridSelectionState,
    colors: GeePeeColors,
    visualStyle: TileGridVisualStyle,
): Color {
    if (!visualStyle.showsFills) {
        return Color.Transparent
    }
    return when (selectionState) {
        TileGridSelectionState.FullySelected -> colors.nearbyWay.copy(alpha = SELECTED_TILE_FILL_ALPHA)
        TileGridSelectionState.PartiallySelected -> colors.nearbyWay.copy(alpha = PARTIALLY_SELECTED_TILE_FILL_ALPHA)
        TileGridSelectionState.Unselected -> when (downloadState) {
            TileGridDownloadState.Downloading -> colors.routeAhead.copy(alpha = DOWNLOADING_TILE_FILL_ALPHA)
            TileGridDownloadState.Cached -> colors.onRoute.copy(alpha = CACHED_TILE_FILL_ALPHA)
            TileGridDownloadState.Partial -> colors.onRoute.copy(alpha = PARTIAL_TILE_FILL_ALPHA)
            TileGridDownloadState.TooLarge -> colors.routeAhead.copy(alpha = TOO_LARGE_TILE_FILL_ALPHA)
            TileGridDownloadState.Error -> colors.offRoute.copy(alpha = ERROR_TILE_FILL_ALPHA)
            null -> Color.Transparent
        }
    }
}

private fun cachedCoverageFill(
    downloadState: TileGridDownloadState?,
    colors: GeePeeColors,
    visualStyle: TileGridVisualStyle,
): Color {
    if (!visualStyle.showsFills) {
        return Color.Transparent
    }
    return colors.onRoute.copy(
        alpha = if (downloadState == TileGridDownloadState.Downloading) {
            DOWNLOADING_COVERAGE_FILL_ALPHA
        } else {
            CACHED_COVERAGE_FILL_ALPHA
        },
    )
}

private fun selectedCoverageFill(
    selectionState: TileGridSelectionState,
    colors: GeePeeColors,
    visualStyle: TileGridVisualStyle,
): Color {
    if (!visualStyle.showsFills) {
        return Color.Transparent
    }
    return when (selectionState) {
        TileGridSelectionState.FullySelected -> colors.nearbyWay.copy(alpha = SELECTED_COVERAGE_FILL_ALPHA)
        TileGridSelectionState.PartiallySelected -> colors.nearbyWay.copy(
            alpha = PARTIALLY_SELECTED_COVERAGE_FILL_ALPHA,
        )
        TileGridSelectionState.Unselected -> Color.Transparent
    }
}

private fun tileBorderColor(
    tile: TileGridDisplayTile,
    selectionState: TileGridSelectionState,
    colors: GeePeeColors,
    visualStyle: TileGridVisualStyle,
): Color {
    return when (selectionState) {
        TileGridSelectionState.FullySelected -> colors.nearbyWay.copy(alpha = SELECTED_TILE_BORDER_ALPHA)
        TileGridSelectionState.PartiallySelected -> colors.nearbyWay.copy(alpha = PARTIALLY_SELECTED_TILE_BORDER_ALPHA)
        TileGridSelectionState.Unselected -> when (tile.downloadState) {
            TileGridDownloadState.Downloading -> colors.routeAhead.copy(alpha = DOWNLOADING_TILE_BORDER_ALPHA)
            TileGridDownloadState.Cached -> colors.onRoute.copy(
                alpha = if (visualStyle == TileGridVisualStyle.Preview) {
                    PREVIEW_CACHED_TILE_BORDER_ALPHA
                } else {
                    LIVE_CACHED_TILE_BORDER_ALPHA
                },
            )
            TileGridDownloadState.Partial -> colors.onRoute.copy(
                alpha = if (visualStyle == TileGridVisualStyle.Preview) {
                    PREVIEW_PARTIAL_TILE_BORDER_ALPHA
                } else {
                    LIVE_PARTIAL_TILE_BORDER_ALPHA
                },
            )
            TileGridDownloadState.TooLarge -> colors.routeAhead.copy(alpha = TOO_LARGE_TILE_BORDER_ALPHA)
            TileGridDownloadState.Error -> colors.offRoute.copy(alpha = ERROR_TILE_BORDER_ALPHA)
            null -> if (tile.routeMetrics.intersectsRoute) {
                colors.ink.copy(
                    alpha = if (visualStyle == TileGridVisualStyle.Preview) {
                        PREVIEW_ROUTE_TILE_BORDER_ALPHA
                    } else {
                        LIVE_ROUTE_TILE_BORDER_ALPHA
                    },
                )
            } else {
                colors.ink.copy(
                    alpha = if (visualStyle == TileGridVisualStyle.Preview) {
                        PREVIEW_EMPTY_TILE_BORDER_ALPHA
                    } else {
                        LIVE_EMPTY_TILE_BORDER_ALPHA
                    },
                )
            }
        }
    }
}

private fun tileBorderWidthDp(
    tile: TileGridDisplayTile,
    selectionState: TileGridSelectionState,
    visualStyle: TileGridVisualStyle,
): Float {
    return when (visualStyle) {
        TileGridVisualStyle.Preview -> when {
            selectionState == TileGridSelectionState.FullySelected -> 2.2f
            selectionState == TileGridSelectionState.PartiallySelected -> 1.8f
            tile.downloadState == TileGridDownloadState.Cached -> 1.15f
            tile.downloadState == TileGridDownloadState.Partial -> 1.05f
            tile.routeMetrics.intersectsRoute -> 1.5f
            else -> 0.8f
        }
        TileGridVisualStyle.LiveOverlay -> when {
            tile.downloadState == TileGridDownloadState.Cached -> 0.9f
            tile.downloadState == TileGridDownloadState.Partial -> 0.8f
            tile.routeMetrics.intersectsRoute -> 1f
            else -> 0.7f
        }
    }
}
