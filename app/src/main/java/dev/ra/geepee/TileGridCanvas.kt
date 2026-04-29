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

internal enum class TileGridVisualStyle {
    Preview,
    LiveOverlay,
}

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
    val selectionState = tile.selectionState

    val showFills = visualStyle == TileGridVisualStyle.Preview
    val showProgress = visualStyle == TileGridVisualStyle.Preview

    val routeFill = if (showFills && tile.routeMetrics.intersectsRoute) {
        colors.ink.copy(alpha = 0.025f)
    } else {
        Color.Transparent
    }
    if (routeFill.alpha > 0f) {
        drawRoundRect(
            color = routeFill,
            topLeft = topLeft,
            size = size,
            cornerRadius = cornerRadius,
        )
    }

    val stateFill = if (showFills) {
        when (selectionState) {
            TileGridSelectionState.FullySelected -> colors.nearbyWay.copy(alpha = 0.06f)
            TileGridSelectionState.PartiallySelected -> colors.nearbyWay.copy(alpha = 0.035f)
            TileGridSelectionState.Unselected -> when (state) {
                TileGridDownloadState.Downloading -> colors.routeAhead.copy(alpha = 0.12f)
                TileGridDownloadState.Cached -> colors.onRoute.copy(alpha = 0.1f)
                TileGridDownloadState.Partial -> colors.onRoute.copy(alpha = 0.06f)
                TileGridDownloadState.TooLarge -> colors.routeAhead.copy(alpha = 0.055f)
                TileGridDownloadState.Error -> colors.offRoute.copy(alpha = 0.1f)
                null -> Color.Transparent
            }
        }
    } else {
        Color.Transparent
    }
    if (stateFill.alpha > 0f) {
        drawRoundRect(
            color = stateFill,
            topLeft = topLeft,
            size = size,
            cornerRadius = cornerRadius,
        )
    }

    if (showFills && tile.cachedCoverageRects.isNotEmpty()) {
        val coverageFill = if (state == TileGridDownloadState.Downloading) {
            colors.onRoute.copy(alpha = 0.18f)
        } else {
            colors.onRoute.copy(alpha = 0.16f)
        }
        tile.cachedCoverageRects.forEach { coverageRect ->
            drawRect(
                color = coverageFill,
                topLeft = Offset(coverageRect.left, coverageRect.top),
                size = Size(coverageRect.width, coverageRect.height),
            )
        }
        val selectedCoverageFill = when (selectionState) {
            TileGridSelectionState.FullySelected -> colors.nearbyWay.copy(alpha = 0.12f)
            TileGridSelectionState.PartiallySelected -> colors.nearbyWay.copy(alpha = 0.1f)
            TileGridSelectionState.Unselected -> Color.Transparent
        }
        if (selectedCoverageFill.alpha > 0f) {
            tile.selectedCoverageRects.forEach { coverageRect ->
                drawRect(
                    color = selectedCoverageFill,
                    topLeft = Offset(coverageRect.left, coverageRect.top),
                    size = Size(coverageRect.width, coverageRect.height),
                )
            }
        }
    }

    val borderColor = when (selectionState) {
        TileGridSelectionState.FullySelected -> colors.nearbyWay.copy(alpha = 0.76f)
        TileGridSelectionState.PartiallySelected -> colors.nearbyWay.copy(alpha = 0.58f)
        TileGridSelectionState.Unselected -> when (state) {
            TileGridDownloadState.Downloading -> colors.routeAhead.copy(alpha = 0.48f)
            TileGridDownloadState.Cached -> colors.onRoute.copy(alpha = 0.52f)
            TileGridDownloadState.Partial -> colors.onRoute.copy(alpha = 0.36f)
            TileGridDownloadState.TooLarge -> colors.routeAhead.copy(alpha = 0.56f)
            TileGridDownloadState.Error -> colors.offRoute.copy(alpha = 0.54f)
            null -> if (tile.routeMetrics.intersectsRoute) {
                colors.ink.copy(alpha = if (visualStyle == TileGridVisualStyle.Preview) 0.14f else 0.08f)
            } else {
                colors.ink.copy(alpha = if (visualStyle == TileGridVisualStyle.Preview) 0.06f else 0.035f)
            }
        }
    }
    drawRoundRect(
        color = borderColor,
        topLeft = topLeft,
        size = size,
        cornerRadius = cornerRadius,
        style = Stroke(
            width = when (visualStyle) {
                TileGridVisualStyle.Preview -> when {
                    selectionState == TileGridSelectionState.FullySelected -> 2.2.dp.toPx()
                    selectionState == TileGridSelectionState.PartiallySelected -> 1.8.dp.toPx()
                    tile.routeMetrics.intersectsRoute -> 1.5.dp.toPx()
                    else -> 0.8.dp.toPx()
                }
                TileGridVisualStyle.LiveOverlay -> if (tile.routeMetrics.intersectsRoute) 1.dp.toPx() else 0.7.dp.toPx()
            },
            pathEffect = if (tile.outlineStyle == TileGridOutlineStyle.ViewProxyDashed) {
                PathEffect.dashPathEffect(
                    intervals = floatArrayOf(16.dp.toPx(), 10.dp.toPx()),
                )
            } else {
                null
            },
        ),
    )

    if (showProgress) {
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

    if (visualStyle == TileGridVisualStyle.Preview) {
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
