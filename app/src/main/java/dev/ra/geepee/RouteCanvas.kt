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
import androidx.compose.ui.graphics.BlendMode
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
    windowWidthMeters: Double,
    boundsOverride: Bounds? = null,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val sessionRoutePalette = sessionRoutePalette(darkModeEnabled = state.darkModeEnabled, colors = colors)
    val density = LocalDensity.current
    val connectorLabelPaint = remember(density, colors.ink) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.ink.copy(alpha = 0.92f).toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 14.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val routeRibbonPaint = remember { androidx.compose.ui.graphics.Paint() }

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
            matchHypotheses = state.routeMatchHypotheses,
            historyPoints = state.locationHistoryPoints,
            pois = state.mapInfo.pois,
            nearbyWays = state.mapInfo.nearbyWays,
            localWindowWidthMeters = windowWidthMeters,
            canvasWidth = size.width,
            canvasHeight = size.height,
            lookAheadFraction = 0.0,
            rotationDegrees = routeRotationDegrees,
            includeGradientPolylines = true,
            boundsOverride = boundsOverride,
        )

        val routeHaloWidth = 16.dp.toPx()
        val routeWidth = 7.dp.toPx()
        val connectorWidth = 3.dp.toPx()
        val userHeadingDegrees = state.compass?.headingDegrees?.let { headingDegrees ->
            headingDegrees + routeRotationDegrees
        }

        val nearest = renderModel.nearestPoint
        val hypothesisPoints = renderModel.hypothesisPoints
        val nearestPointUncertainty = renderModel.nearestPointUncertainty
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
        val setupGradientMode = !state.sessionRunning
        if (!setupGradientMode && renderModel.nearbyWayPolylines.isNotEmpty()) {
            drawNearbyWays(
                polylines = renderModel.nearbyWayPolylines,
                color = if (state.darkModeEnabled) {
                    colors.nearbyWay.copy(alpha = 0.6f)
                } else {
                    colors.nearbyWay.copy(alpha = 0.52f)
                },
                widthPx = 3.dp.toPx(),
            )
        }
        if (setupGradientMode) {
            mergedDisplayGradientPolylines(
                polylines = renderModel.gradientPolylines,
                simplifyTolerancePx = 1.5f,
                pruneSharpSpikes = true,
            ).forEach { displayPoints ->
                if (displayPoints.size < 2) {
                    return@forEach
                }
                val polylinePath = buildPolylinePath(displayPoints.map(RouteGradientPoint::point))
                drawPath(
                    path = polylinePath,
                    color = colors.line.copy(alpha = 0.08f),
                    style = Stroke(width = routeHaloWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                buildRouteRibbonMesh(
                    points = displayPoints,
                    widthPx = routeWidth,
                )?.let { ribbon ->
                    drawContext.canvas.drawVertices(
                        vertices = ribbon.vertices,
                        blendMode = BlendMode.SrcOver,
                        paint = routeRibbonPaint,
                    )
                }
            }
        } else {
            val currentRouteMeters = state.analysis?.routeMeters
            mergedDisplayGradientPolylines(
                polylines = renderModel.gradientPolylines,
            ).forEach { displayPoints ->
                if (displayPoints.size < 2) {
                    return@forEach
                }

                val path = buildPolylinePath(displayPoints.map(RouteGradientPoint::point))

                drawPath(
                    path = path,
                    color = sessionRoutePalette.baseHalo,
                    style = Stroke(width = routeHaloWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = sessionRoutePalette.baseLine,
                    style = Stroke(width = routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                highlightedSessionSubpaths(
                    points = displayPoints,
                    currentRouteMeters = currentRouteMeters,
                    totalRouteMeters = routeModel.totalLengthMeters,
                    windowWidthMeters = windowWidthMeters,
                    isClosedLoop = routeModel.isClosedLoop,
                ).forEach { highlightedPath ->
                    val (haloColor, lineColor) = when (highlightedPath.kind) {
                        SessionRouteHighlightKind.Ahead -> {
                            sessionRoutePalette.aheadHalo to sessionRoutePalette.aheadLine
                        }
                        SessionRouteHighlightKind.Behind -> {
                            sessionRoutePalette.behindHalo to sessionRoutePalette.behindLine
                        }
                    }
                    drawPath(
                        path = highlightedPath.path,
                        color = haloColor,
                        style = Stroke(width = routeHaloWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = highlightedPath.path,
                        color = lineColor,
                        style = Stroke(width = routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
        if (!setupGradientMode && renderModel.poiMarkers.isNotEmpty()) {
            drawRoutePoiMarkers(
                markers = renderModel.poiMarkers,
                darkModeEnabled = state.darkModeEnabled,
                colors = colors,
            )
        }

        if (hypothesisPoints.isNotEmpty()) {
            drawRouteHypotheses(
                hypotheses = hypothesisPoints,
                toneColor = toneColor,
                mistColor = colors.mist,
            )
        } else if (nearest != null) {
            drawNearestSnapMarker(
                center = Offset(nearest.x, nearest.y),
                toneColor = toneColor,
                uncertainty = nearestPointUncertainty,
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

private fun DrawScope.drawNearbyWays(
    polylines: List<List<ScreenPoint>>,
    color: Color,
    widthPx: Float,
) {
    val dashPathEffect = PathEffect.dashPathEffect(
        intervals = floatArrayOf(widthPx * 3.2f, widthPx * 2.4f),
    )
    polylines.forEach { polyline ->
        if (polyline.size < 2) {
            return@forEach
        }
        drawPath(
            path = buildPolylinePath(polyline),
            color = color,
            style = Stroke(
                width = widthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = dashPathEffect,
            ),
        )
    }
}

private fun DrawScope.drawRoutePoiMarkers(
    markers: List<RoutePoiScreenMarker>,
    darkModeEnabled: Boolean,
    colors: GeePeeColors,
) {
    markers.forEach { marker ->
        val center = Offset(marker.point.x, marker.point.y)
        val accent = routePoiAccentColor(marker.kind)
        val haloRadius = 9.dp.toPx()
        val iconRadius = 6.dp.toPx()

        drawCircle(
            color = colors.mist.copy(alpha = if (darkModeEnabled) 0.94f else 0.9f),
            radius = haloRadius,
            center = center,
        )
        when (marker.kind) {
            RoutePoiKind.DrinkingWater -> {
                drawPath(
                    path = Path().apply {
                        moveTo(center.x, center.y - iconRadius)
                        cubicTo(
                            center.x + iconRadius,
                            center.y - iconRadius * 0.3f,
                            center.x + iconRadius * 0.9f,
                            center.y + iconRadius * 0.8f,
                            center.x,
                            center.y + iconRadius,
                        )
                        cubicTo(
                            center.x - iconRadius * 0.9f,
                            center.y + iconRadius * 0.8f,
                            center.x - iconRadius,
                            center.y - iconRadius * 0.3f,
                            center.x,
                            center.y - iconRadius,
                        )
                        close()
                    },
                    color = accent,
                )
            }

            RoutePoiKind.Shelter -> {
                val roofWidth = iconRadius * 1.9f
                val roofHeight = iconRadius * 1.2f
                drawPath(
                    path = Path().apply {
                        moveTo(center.x, center.y - roofHeight)
                        lineTo(center.x + roofWidth / 2f, center.y - roofHeight / 5f)
                        lineTo(center.x - roofWidth / 2f, center.y - roofHeight / 5f)
                        close()
                    },
                    color = accent,
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(center.x - iconRadius * 0.55f, center.y - iconRadius * 0.1f),
                    size = Size(iconRadius * 1.1f, iconRadius * 1.15f),
                    cornerRadius = CornerRadius(iconRadius * 0.2f, iconRadius * 0.2f),
                )
            }

            RoutePoiKind.PicnicSite -> {
                drawPath(
                    path = Path().apply {
                        moveTo(center.x, center.y - iconRadius)
                        lineTo(center.x + iconRadius, center.y)
                        lineTo(center.x, center.y + iconRadius)
                        lineTo(center.x - iconRadius, center.y)
                        close()
                    },
                    color = accent,
                )
            }

            RoutePoiKind.Toilets -> {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(center.x - iconRadius, center.y - iconRadius * 0.8f),
                    size = Size(iconRadius * 2f, iconRadius * 1.6f),
                    cornerRadius = CornerRadius(iconRadius * 0.35f, iconRadius * 0.35f),
                )
            }

            RoutePoiKind.BicycleRepairStation,
            RoutePoiKind.BicycleShop,
            -> {
                drawCircle(
                    color = accent,
                    radius = iconRadius,
                    center = center,
                )
            }
        }
    }
}

private data class SessionRoutePalette(
    val baseHalo: Color,
    val baseLine: Color,
    val aheadHalo: Color,
    val aheadLine: Color,
    val behindHalo: Color,
    val behindLine: Color,
)

private fun sessionRoutePalette(
    darkModeEnabled: Boolean,
    colors: GeePeeColors,
): SessionRoutePalette {
    return if (darkModeEnabled) {
        SessionRoutePalette(
            baseHalo = colors.line.copy(alpha = 0.08f),
            baseLine = colors.line.copy(alpha = 0.34f),
            aheadHalo = colors.routeAhead.copy(alpha = 0.18f),
            aheadLine = colors.routeAhead,
            behindHalo = colors.line.copy(alpha = 0.12f),
            behindLine = colors.line.copy(alpha = 0.68f),
        )
    } else {
        SessionRoutePalette(
            baseHalo = colors.line.copy(alpha = 0.05f),
            baseLine = colors.line.copy(alpha = 0.22f),
            aheadHalo = colors.routeAhead.copy(alpha = 0.12f),
            aheadLine = colors.routeAhead,
            behindHalo = colors.line.copy(alpha = 0.08f),
            behindLine = colors.line.copy(alpha = 0.48f),
        )
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

internal fun routeSegmentEmphasis(
    startProgressRatio: Float,
    endProgressRatio: Float,
    currentRouteMeters: Double?,
    totalRouteMeters: Double,
    windowWidthMeters: Double,
    isClosedLoop: Boolean,
): Float {
    if (currentRouteMeters == null || totalRouteMeters <= 0.0) {
        return 1f
    }

    val segmentRouteMeters = ((startProgressRatio + endProgressRatio) / 2.0f) * totalRouteMeters.toFloat()
    val routeDeltaMeters = routeProgressDeltaMeters(
        routeMetersA = segmentRouteMeters.toDouble(),
        routeMetersB = currentRouteMeters,
        totalRouteMeters = totalRouteMeters,
        isClosedLoop = isClosedLoop,
    )
    val fullStrengthMeters = kotlin.math.max(90.0, windowWidthMeters * 0.18)
    val fadeOutMeters = kotlin.math.max(180.0, windowWidthMeters * 0.55)

    return when {
        routeDeltaMeters <= fullStrengthMeters -> 1f
        routeDeltaMeters >= fadeOutMeters -> 0f
        else -> {
            val normalized = ((routeDeltaMeters - fullStrengthMeters) / (fadeOutMeters - fullStrengthMeters)).toFloat()
            val eased = 1f - normalized
            eased * eased * (3f - 2f * eased)
        }
    }
}

internal fun highlightedSessionSubpaths(
    points: List<RouteGradientPoint>,
    currentRouteMeters: Double?,
    totalRouteMeters: Double,
    windowWidthMeters: Double,
    isClosedLoop: Boolean,
    minimumEmphasis: Float = 0.35f,
): List<SessionHighlightedRoutePath> {
    if (points.size < 2) {
        return emptyList()
    }

    val subpaths = mutableListOf<SessionHighlightedRoutePath>()
    var currentPoints = mutableListOf<ScreenPoint>()
    var currentKind: SessionRouteHighlightKind? = null

    fun flushCurrent() {
        if (currentPoints.size >= 2 && currentKind != null) {
            subpaths += SessionHighlightedRoutePath(
                path = buildPolylinePath(currentPoints),
                kind = currentKind!!,
            )
        }
        currentPoints = mutableListOf()
        currentKind = null
    }

    points.zipWithNext().forEach { (start, end) ->
        val kind = routeSegmentHighlightKind(
            startProgressRatio = start.progressRatio,
            endProgressRatio = end.progressRatio,
            currentRouteMeters = currentRouteMeters,
            totalRouteMeters = totalRouteMeters,
            windowWidthMeters = windowWidthMeters,
            isClosedLoop = isClosedLoop,
            minimumEmphasis = minimumEmphasis,
        )

        if (kind != null) {
            if (currentPoints.isEmpty() || currentKind != kind) {
                flushCurrent()
                currentPoints += start.point
                currentKind = kind
            }
            if (currentPoints.last() != end.point) {
                currentPoints += end.point
            }
        } else {
            flushCurrent()
        }
    }

    flushCurrent()
    return subpaths
}

internal enum class SessionRouteHighlightKind {
    Ahead,
    Behind,
}

internal data class SessionHighlightedRoutePath(
    val path: Path,
    val kind: SessionRouteHighlightKind,
)

internal fun routeSegmentHighlightKind(
    startProgressRatio: Float,
    endProgressRatio: Float,
    currentRouteMeters: Double?,
    totalRouteMeters: Double,
    windowWidthMeters: Double,
    isClosedLoop: Boolean,
    minimumEmphasis: Float = 0.35f,
): SessionRouteHighlightKind? {
    val emphasis = routeSegmentEmphasis(
        startProgressRatio = startProgressRatio,
        endProgressRatio = endProgressRatio,
        currentRouteMeters = currentRouteMeters,
        totalRouteMeters = totalRouteMeters,
        windowWidthMeters = windowWidthMeters,
        isClosedLoop = isClosedLoop,
    )
    if (emphasis < minimumEmphasis || currentRouteMeters == null || totalRouteMeters <= 0.0) {
        return null
    }

    val segmentRouteMeters = ((startProgressRatio + endProgressRatio) / 2.0f) * totalRouteMeters.toFloat()
    val signedDeltaMeters = signedRouteProgressDeltaMeters(
        routeMetersA = segmentRouteMeters.toDouble(),
        routeMetersB = currentRouteMeters,
        totalRouteMeters = totalRouteMeters,
        isClosedLoop = isClosedLoop,
    )
    return if (signedDeltaMeters < 0.0) {
        SessionRouteHighlightKind.Behind
    } else {
        SessionRouteHighlightKind.Ahead
    }
}

private fun routeProgressDeltaMeters(
    routeMetersA: Double,
    routeMetersB: Double,
    totalRouteMeters: Double,
    isClosedLoop: Boolean,
): Double {
    val directDelta = kotlin.math.abs(routeMetersA - routeMetersB)
    if (!isClosedLoop || totalRouteMeters <= 0.0) {
        return directDelta
    }
    return minOf(directDelta, totalRouteMeters - directDelta)
}

private fun signedRouteProgressDeltaMeters(
    routeMetersA: Double,
    routeMetersB: Double,
    totalRouteMeters: Double,
    isClosedLoop: Boolean,
): Double {
    val directDelta = routeMetersA - routeMetersB
    if (!isClosedLoop || totalRouteMeters <= 0.0) {
        return directDelta
    }
    var wrappedDelta = directDelta % totalRouteMeters
    if (wrappedDelta > totalRouteMeters / 2.0) {
        wrappedDelta -= totalRouteMeters
    } else if (wrappedDelta < -totalRouteMeters / 2.0) {
        wrappedDelta += totalRouteMeters
    }
    return wrappedDelta
}

private fun DrawScope.drawNearestSnapMarker(
    center: Offset,
    toneColor: Color,
    uncertainty: Float,
) {
    val clampedUncertainty = uncertainty.coerceIn(0f, 1f)
    if (clampedUncertainty > 0f) {
        drawCircle(
            color = toneColor.copy(alpha = lerpFloat(start = 0.08f, stop = 0.18f, fraction = clampedUncertainty)),
            radius = lerpFloat(start = 18.dp.toPx(), stop = 24.dp.toPx(), fraction = clampedUncertainty),
            center = center,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
    drawCircle(
        color = toneColor.copy(alpha = 0.18f),
        radius = 14.dp.toPx(),
        center = center,
    )
    drawCircle(
        color = toneColor,
        radius = 6.dp.toPx(),
        center = center,
    )
}

private fun DrawScope.drawRouteHypotheses(
    hypotheses: List<RouteHypothesisScreenPoint>,
    toneColor: Color,
    mistColor: Color,
) {
    hypotheses.forEach { hypothesis ->
        val center = Offset(hypothesis.point.x, hypothesis.point.y)
        val confidence = hypothesis.confidence.coerceIn(0f, 1f)
        val haloAlpha = if (hypothesis.isPrimary) {
            lerpFloat(start = 0.08f, stop = 0.22f, fraction = confidence)
        } else {
            lerpFloat(start = 0.16f, stop = 0.3f, fraction = confidence)
        }
        val fillAlpha = if (hypothesis.isPrimary) {
            lerpFloat(start = 0.18f, stop = 0.85f, fraction = confidence)
        } else {
            lerpFloat(start = 0.34f, stop = 0.62f, fraction = confidence)
        }
        val haloRadius = if (hypothesis.isPrimary) {
            lerpFloat(start = 10.dp.toPx(), stop = 14.dp.toPx(), fraction = confidence)
        } else {
            lerpFloat(start = 11.dp.toPx(), stop = 13.dp.toPx(), fraction = confidence)
        }
        val fillRadius = if (hypothesis.isPrimary) {
            lerpFloat(start = 4.dp.toPx(), stop = 7.dp.toPx(), fraction = confidence)
        } else {
            lerpFloat(start = 5.dp.toPx(), stop = 6.5.dp.toPx(), fraction = confidence)
        }

        drawCircle(
            color = mistColor.copy(alpha = haloAlpha),
            radius = haloRadius,
            center = center,
        )
        drawCircle(
            color = toneColor.copy(alpha = fillAlpha),
            radius = fillRadius,
            center = center,
        )

        if (hypothesis.isPrimary) {
            drawCircle(
                color = toneColor.copy(alpha = 0.92f),
                radius = fillRadius + 3.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
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

private fun buildPolylinePath(points: List<ScreenPoint>): Path {
    return Path().apply {
        val first = points.first()
        moveTo(first.x, first.y)
        points.drop(1).forEach { point ->
            lineTo(point.x, point.y)
        }
    }
}

internal fun mergedDisplayGradientPolylines(
    polylines: List<RouteGradientPolyline>,
    simplifyTolerancePx: Float? = null,
    mergeTolerancePx: Float = 0.75f,
    pruneSharpSpikes: Boolean = false,
): List<List<RouteGradientPoint>> {
    if (polylines.isEmpty()) {
        return emptyList()
    }

    val merged = mutableListOf<MutableList<RouteGradientPoint>>()

    polylines.forEach { polyline ->
        val points = polyline.points
        if (points.size < 2) {
            return@forEach
        }

        val current = points.toMutableList()
        val previous = merged.lastOrNull()
        if (previous != null && areScreenPointsClose(previous.last().point, current.first().point, mergeTolerancePx)) {
            previous += current.drop(1)
        } else {
            merged += current
        }
    }

    return merged.map { points ->
        val simplified = simplifyTolerancePx?.let { tolerance ->
            simplifyGradientPointsForDisplay(points, tolerance)
        } ?: points
        if (pruneSharpSpikes) {
            pruneSharpDisplaySpikes(simplified)
        } else {
            simplified
        }
    }
}

internal fun pruneSharpDisplaySpikes(
    points: List<RouteGradientPoint>,
    shortSegmentThresholdPx: Float = 18f,
    shortToLongRatioThreshold: Float = 0.55f,
    maxTurnDot: Float = 0.25f,
    directLengthRatioThreshold: Float = 1.1f,
): List<RouteGradientPoint> {
    if (points.size < 3) {
        return points
    }

    val pruned = mutableListOf<RouteGradientPoint>()
    pruned += points.first()

    for (index in 1 until points.lastIndex) {
        val previous = pruned.last()
        val current = points[index]
        val next = points[index + 1]
        val previousToCurrent = screenPointDistance(previous.point, current.point)
        val currentToNext = screenPointDistance(current.point, next.point)
        val previousToNext = screenPointDistance(previous.point, next.point)
        val shorter = minOf(previousToCurrent, currentToNext)
        val longer = maxOf(previousToCurrent, currentToNext)
        val turnDot = turnDot(previous.point, current.point, next.point)
        val shouldDrop = shorter <= shortSegmentThresholdPx &&
            shorter <= longer * shortToLongRatioThreshold &&
            turnDot <= maxTurnDot &&
            previousToNext <= longer * directLengthRatioThreshold

        if (!shouldDrop) {
            pruned += current
        }
    }

    pruned += points.last()
    return pruned
}

private fun areScreenPointsClose(
    first: ScreenPoint,
    second: ScreenPoint,
    tolerancePx: Float,
): Boolean {
    return screenPointDistance(first, second) <= tolerancePx
}

internal fun simplifyGradientPointsForDisplay(
    points: List<RouteGradientPoint>,
    tolerancePx: Float,
): List<RouteGradientPoint> {
    if (points.size <= 2 || tolerancePx <= 0f) {
        return points
    }

    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true

    fun simplifyRange(startIndex: Int, endIndex: Int) {
        if (endIndex - startIndex <= 1) {
            return
        }

        val start = points[startIndex].point
        val end = points[endIndex].point
        var maxDistance = 0f
        var furthestIndex = -1

        for (index in (startIndex + 1) until endIndex) {
            val distance = perpendicularDistance(points[index].point, start, end)
            if (distance > maxDistance) {
                maxDistance = distance
                furthestIndex = index
            }
        }

        if (furthestIndex >= 0 && maxDistance >= tolerancePx) {
            keep[furthestIndex] = true
            simplifyRange(startIndex, furthestIndex)
            simplifyRange(furthestIndex, endIndex)
        }
    }

    simplifyRange(0, points.lastIndex)
    return buildList {
        points.forEachIndexed { index, point ->
            if (keep[index]) {
                add(point)
            }
        }
    }
}

private fun perpendicularDistance(
    point: ScreenPoint,
    segmentStart: ScreenPoint,
    segmentEnd: ScreenPoint,
): Float {
    val dx = segmentEnd.x - segmentStart.x
    val dy = segmentEnd.y - segmentStart.y
    if (dx == 0f && dy == 0f) {
        return hypot(point.x - segmentStart.x, point.y - segmentStart.y)
    }
    val numerator = kotlin.math.abs(
        dy * point.x - dx * point.y + segmentEnd.x * segmentStart.y - segmentEnd.y * segmentStart.x,
    )
    val denominator = hypot(dx, dy)
    return numerator / denominator
}

private fun turnDot(
    previous: ScreenPoint,
    current: ScreenPoint,
    next: ScreenPoint,
): Float {
    val incomingX = current.x - previous.x
    val incomingY = current.y - previous.y
    val outgoingX = next.x - current.x
    val outgoingY = next.y - current.y
    val incomingLength = hypot(incomingX, incomingY).coerceAtLeast(0.0001f)
    val outgoingLength = hypot(outgoingX, outgoingY).coerceAtLeast(0.0001f)
    return ((incomingX / incomingLength) * (outgoingX / outgoingLength)) +
        ((incomingY / incomingLength) * (outgoingY / outgoingLength))
}

private fun screenPointDistance(
    first: ScreenPoint,
    second: ScreenPoint,
): Float {
    return hypot(first.x - second.x, first.y - second.y)
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
