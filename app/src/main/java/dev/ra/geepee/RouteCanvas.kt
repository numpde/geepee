package dev.ra.geepee

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
internal fun RouteCanvas(
    state: GeePeeUiState,
    toneColor: Color,
    orientationMode: OrientationMode,
    routeScale: RouteScale,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val density = LocalDensity.current
    val connectorLabelPaint = remember(density, colors.ink) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.ink.copy(alpha = 0.92f).toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 14.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Canvas(modifier = modifier) {
        val routeModel = state.routeModel ?: return@Canvas
        val routeRotationDegrees = if (orientationMode == OrientationMode.CourseUp) {
            -(state.compass?.headingDegrees?.toFloat() ?: 0f)
        } else {
            0f
        }
        val renderModel = buildRouteRenderModel(
            routeModel = routeModel,
            analysis = state.analysis,
            historyPoints = state.locationHistoryPoints,
            localWindowWidthMeters = routeScale.windowWidthMeters,
            canvasWidth = size.width,
            canvasHeight = size.height,
            lookAheadFraction = 0.0,
            rotationDegrees = routeRotationDegrees,
        )

        val routeHaloWidth = 16.dp.toPx()
        val routeWidth = 7.dp.toPx()
        val connectorWidth = 3.dp.toPx()
        val userHeadingDegrees = state.compass?.headingDegrees?.let { headingDegrees ->
            headingDegrees + routeRotationDegrees
        }

        val nearest = renderModel.nearestPoint
        val user = renderModel.userPoint
        val edge = renderModel.edgePoint
        val history = renderModel.historyPoints
        val connectorLabel = connectorLabelPlacement(
            analysis = state.analysis,
            nearest = nearest,
            user = user,
            edge = edge,
            offsetPixels = 18.dp.toPx(),
        )
        renderModel.polylines.forEach { polyline ->
            if (polyline.size < 2) {
                return@forEach
            }

            val path = Path().apply {
                moveTo(polyline.first().x, polyline.first().y)
                polyline.drop(1).forEach { point ->
                    lineTo(point.x, point.y)
                }
            }

            drawPath(
                path = path,
                color = colors.line.copy(alpha = 0.12f),
                style = Stroke(width = routeHaloWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                path = path,
                color = colors.line,
                style = Stroke(width = routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        if (nearest != null) {
            drawCircle(
                color = toneColor.copy(alpha = 0.18f),
                radius = 14.dp.toPx(),
                center = Offset(nearest.x, nearest.y),
            )
            drawCircle(
                color = toneColor,
                radius = 6.dp.toPx(),
                center = Offset(nearest.x, nearest.y),
            )
        }

        drawHistoryTrail(
            history = history,
            color = toneColor,
            widthPx = 10.dp.toPx(),
        )

        if (nearest != null && user != null) {
            drawLine(
                color = toneColor.copy(alpha = 0.8f),
                start = Offset(nearest.x, nearest.y),
                end = Offset(user.x, user.y),
                strokeWidth = connectorWidth,
            )
            drawUserMarker(
                center = Offset(user.x, user.y),
                bearingDegrees = userHeadingDegrees,
                fillColor = toneColor,
                haloColor = colors.mist,
            )
        }

        if (nearest != null && edge != null) {
            drawLine(
                color = toneColor.copy(alpha = 0.7f),
                start = Offset(nearest.x, nearest.y),
                end = Offset(edge.x, edge.y),
                strokeWidth = connectorWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)),
            )
            drawCircle(
                color = colors.mist,
                radius = 11.dp.toPx(),
                center = Offset(edge.x, edge.y),
            )
            drawCircle(
                color = toneColor,
                radius = 7.dp.toPx(),
                center = Offset(edge.x, edge.y),
            )
        }

        connectorLabel?.let { label ->
            drawDistanceTag(
                label = label.text,
                center = label.center,
                textPaint = connectorLabelPaint,
                fillColor = colors.mist.copy(alpha = 0.94f),
                borderColor = colors.ink.copy(alpha = 0.1f),
            )
        }
    }
}

private fun DrawScope.drawHistoryTrail(
    history: List<ScreenPoint>,
    color: Color,
    widthPx: Float,
) {
    if (history.size < 2) {
        return
    }

    val trailLengthPx = history
        .zipWithNext()
        .sumOf { (start, end) ->
            hypot(
                (end.x - start.x).toDouble(),
                (end.y - start.y).toDouble(),
            )
        }
        .toFloat()
    val targetVisibleLengthPx = (size.minDimension * 0.16f).coerceAtLeast(widthPx * 10f)
    val visibilityProgress = (trailLengthPx / targetVisibleLengthPx).coerceIn(0f, 1f)
    val oldestAlpha = lerpFloat(start = 0.12f, stop = 0.03f, fraction = visibilityProgress)
    val newestAlpha = lerpFloat(start = 0.5f, stop = 0.24f, fraction = visibilityProgress)
    val trailWidthPx = widthPx * lerpFloat(start = 1.35f, stop = 1f, fraction = visibilityProgress)
    val oldest = history.first()
    val newest = history.last()
    val brush = Brush.linearGradient(
        colors = listOf(
            color.copy(alpha = oldestAlpha),
            color.copy(alpha = newestAlpha),
        ),
        start = Offset(oldest.x, oldest.y),
        end = Offset(newest.x, newest.y),
    )

    drawPath(
        path = buildSmoothHistoryPath(history),
        brush = brush,
        style = Stroke(
            width = trailWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun buildSmoothHistoryPath(history: List<ScreenPoint>): Path {
    val path = Path()
    val first = history.first()
    path.moveTo(first.x, first.y)

    if (history.size == 2) {
        val second = history[1]
        path.lineTo(second.x, second.y)
        return path
    }

    for (index in 1 until history.lastIndex) {
        val current = history[index]
        val next = history[index + 1]
        val midpoint = Offset(
            x = (current.x + next.x) / 2f,
            y = (current.y + next.y) / 2f,
        )
        path.quadraticTo(current.x, current.y, midpoint.x, midpoint.y)
    }

    val penultimate = history[history.lastIndex - 1]
    val last = history.last()
    path.quadraticTo(penultimate.x, penultimate.y, last.x, last.y)
    return path
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun DrawScope.drawUserMarker(
    center: Offset,
    bearingDegrees: Double?,
    fillColor: Color,
    haloColor: Color,
) {
    if (bearingDegrees == null) {
        drawCircle(
            color = haloColor,
            radius = 13.dp.toPx(),
            center = center,
        )
        drawCircle(
            color = fillColor,
            radius = 9.dp.toPx(),
            center = center,
        )
        return
    }

    drawHeadingTriangle(
        center = center,
        bearingDegrees = bearingDegrees,
        length = 18.dp.toPx(),
        baseWidth = 16.dp.toPx(),
        color = haloColor,
    )
    drawHeadingTriangle(
        center = center,
        bearingDegrees = bearingDegrees,
        length = 13.dp.toPx(),
        baseWidth = 11.dp.toPx(),
        color = fillColor,
    )
}

private fun DrawScope.drawHeadingTriangle(
    center: Offset,
    bearingDegrees: Double,
    length: Float,
    baseWidth: Float,
    color: Color,
) {
    val tip = polarPoint(center, length * 0.78f, bearingDegrees)
    val baseCenter = polarPoint(center, length * 0.28f, bearingDegrees + 180.0)
    val left = polarPoint(baseCenter, baseWidth / 2f, bearingDegrees - 90.0)
    val right = polarPoint(baseCenter, baseWidth / 2f, bearingDegrees + 90.0)
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        },
        color = color,
    )
}

@Composable
internal fun HeadingCompass(
    compass: CompassState,
    toneColor: Color,
    orientationMode: OrientationMode,
    onToggleOrientationMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val density = LocalDensity.current
    val labelPaint = remember(density, colors.ink) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.ink.copy(alpha = 0.7f).toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 12.sp.toPx() }
        }
    }

    Canvas(
        modifier = modifier
            .size(84.dp)
            .clickable(onClick = onToggleOrientationMode),
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.42f
        val northReference = 0.0
        val routeReference = compass.routeBearingDegrees
        val headingReference = compass.headingDegrees

        drawCircle(
            color = colors.mist.copy(alpha = 0.96f),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = colors.ink.copy(alpha = 0.12f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )

        drawCompassTick(
            center = center,
            radius = radius,
            bearingDegrees = northReference,
            color = colors.ink.copy(alpha = 0.7f),
            tickLength = 11.dp.toPx(),
            strokeWidth = 2.5.dp.toPx(),
        )

        headingReference?.let { headingDegrees ->
            drawCompassTick(
                center = center,
                radius = radius,
                bearingDegrees = headingDegrees,
                color = colors.ink.copy(alpha = 0.38f),
                tickLength = 16.dp.toPx(),
                strokeWidth = 4.dp.toPx(),
            )
        }

        val northLabelPoint = polarPoint(center, radius - 16.dp.toPx(), northReference)
        drawContext.canvas.nativeCanvas.drawText(
            "N",
            northLabelPoint.x,
            northLabelPoint.y + labelPaint.textSize * 0.35f,
            labelPaint,
        )

        drawCompassArrow(
            center = center,
            radius = radius - 10.dp.toPx(),
            bearingDegrees = routeReference,
            color = toneColor,
        )

        drawCircle(
            color = if (orientationMode == OrientationMode.CourseUp) {
                toneColor.copy(alpha = 0.32f)
            } else {
                colors.ink.copy(alpha = 0.18f)
            },
            radius = 4.dp.toPx(),
            center = center,
        )
    }
}

private data class ConnectorLabelPlacement(
    val text: String,
    val center: Offset,
)

private fun connectorLabelPlacement(
    analysis: RouteAnalysis?,
    nearest: ScreenPoint?,
    user: ScreenPoint?,
    edge: ScreenPoint?,
    offsetPixels: Float,
): ConnectorLabelPlacement? {
    val start = nearest ?: return null
    val end = user ?: edge ?: return null
    val offRouteMeters = analysis?.offRouteMeters ?: return null
    if (offRouteMeters < 8.0) {
        return null
    }

    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (length <= 1f) {
        return null
    }

    val midpoint = Offset(
        x = (start.x + end.x) / 2f,
        y = (start.y + end.y) / 2f,
    )
    val offset = Offset(
        x = (-dy / length) * offsetPixels,
        y = (dx / length) * offsetPixels,
    )
    return ConnectorLabelPlacement(
        text = formatDistance(offRouteMeters),
        center = midpoint + offset,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDistanceTag(
    label: String,
    center: Offset,
    textPaint: Paint,
    fillColor: Color,
    borderColor: Color,
) {
    val horizontalPadding = 10.dp.toPx()
    val verticalPadding = 7.dp.toPx()
    val textWidth = textPaint.measureText(label)
    val textHeight = textPaint.textSize
    val left = center.x - (textWidth / 2f) - horizontalPadding
    val top = center.y - (textHeight / 2f) - verticalPadding
    val width = textWidth + horizontalPadding * 2f
    val height = textHeight + verticalPadding * 2f

    drawRoundRect(
        color = fillColor,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(999f, 999f),
    )
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(999f, 999f),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawContext.canvas.nativeCanvas.drawText(
        label,
        center.x,
        center.y + (textPaint.textSize * 0.35f),
        textPaint,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCompassTick(
    center: Offset,
    radius: Float,
    bearingDegrees: Double,
    color: Color,
    tickLength: Float,
    strokeWidth: Float,
) {
    val outer = polarPoint(center, radius - strokeWidth, bearingDegrees)
    val inner = polarPoint(center, radius - tickLength, bearingDegrees)
    drawLine(
        color = color,
        start = outer,
        end = inner,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCompassArrow(
    center: Offset,
    radius: Float,
    bearingDegrees: Double,
    color: Color,
) {
    val tail = polarPoint(center, radius * 0.18f, bearingDegrees + 180.0)
    val tip = polarPoint(center, radius, bearingDegrees)
    val left = polarPoint(center, radius - 12.dp.toPx(), bearingDegrees - 12.0)
    val right = polarPoint(center, radius - 12.dp.toPx(), bearingDegrees + 12.0)

    drawLine(
        color = color,
        start = tail,
        end = tip,
        strokeWidth = 3.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        },
        color = color,
    )
}

private fun polarPoint(center: Offset, radius: Float, bearingDegrees: Double): Offset {
    val radians = Math.toRadians(bearingDegrees)
    val x = center.x + (sin(radians) * radius).toFloat()
    val y = center.y - (cos(radians) * radius).toFloat()
    return Offset(x, y)
}
