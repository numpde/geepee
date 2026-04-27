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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    val state = tile.snapshot?.status

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
        when {
            tile.selected -> colors.routeAhead.copy(alpha = 0.09f)
            else -> when (state) {
                TileDownloadStatus.Downloading -> colors.routeAhead.copy(alpha = 0.12f)
                TileDownloadStatus.Cached -> colors.onRoute.copy(alpha = 0.1f)
                TileDownloadStatus.Error -> colors.offRoute.copy(alpha = 0.1f)
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

    val borderColor = when {
        tile.selected -> colors.routeAhead.copy(alpha = 0.72f)
        else -> when (state) {
            TileDownloadStatus.Downloading -> colors.routeAhead.copy(alpha = 0.48f)
            TileDownloadStatus.Cached -> colors.onRoute.copy(alpha = 0.52f)
            TileDownloadStatus.Error -> colors.offRoute.copy(alpha = 0.54f)
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
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = when (visualStyle) {
                TileGridVisualStyle.Preview -> when {
                    tile.selected -> 2.2.dp.toPx()
                    tile.routeMetrics.intersectsRoute -> 1.5.dp.toPx()
                    else -> 0.8.dp.toPx()
                }
                TileGridVisualStyle.LiveOverlay -> if (tile.routeMetrics.intersectsRoute) 1.dp.toPx() else 0.7.dp.toPx()
            },
        ),
    )

    if (showProgress) {
        tile.snapshot?.takeIf { it.status == TileDownloadStatus.Downloading }?.progressFraction?.let { fraction ->
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
