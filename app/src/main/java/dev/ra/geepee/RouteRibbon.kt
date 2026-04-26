package dev.ra.geepee

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.hypot

internal val ROUTE_START_COLOR = Color(0xFF2962FF)
internal val ROUTE_FINISH_COLOR = Color(0xFFD32F2F)

private const val MIN_POINT_DISTANCE_PX = 0.01f
private const val MIN_MITER_DENOMINATOR = 0.2f
private const val MIN_MITER_TURN_DOT = 0.55f
private const val JOIN_SEGMENT_LIMIT_FRACTION = 0.45f

internal data class RouteRibbonMesh(
    val vertices: Vertices,
    val vertexCount: Int,
    val triangleCount: Int,
)

internal fun buildRouteRibbonMesh(
    points: List<RouteGradientPoint>,
    widthPx: Float,
    startColor: Color = ROUTE_START_COLOR,
    finishColor: Color = ROUTE_FINISH_COLOR,
    miterLimitRatio: Float = 3f,
): RouteRibbonMesh? {
    if (points.size < 2 || widthPx <= 0f) {
        return null
    }

    val displayPoints = dedupeGradientPoints(points)
    if (displayPoints.size < 2) {
        return null
    }

    val halfWidth = widthPx / 2f
    val positions = ArrayList<Offset>(displayPoints.size * 2)
    val colors = ArrayList<Color>(displayPoints.size * 2)
    val segments = displayPoints
        .zipWithNext()
        .map { (start, end) -> segmentVector(start.point, end.point) }

    displayPoints.forEachIndexed { index, point ->
        val offset = joinOffsetVector(
            segments = segments,
            pointIndex = index,
            halfWidth = halfWidth,
            miterLimitRatio = miterLimitRatio,
        )
        val color = routeGradientColor(point.progressRatio, startColor, finishColor)
        positions += Offset(point.point.x + offset.x, point.point.y + offset.y)
        positions += Offset(point.point.x - offset.x, point.point.y - offset.y)
        colors += color
        colors += color
    }

    val vertices = Vertices(
        vertexMode = VertexMode.TriangleStrip,
        positions = positions,
        textureCoordinates = List(positions.size) { Offset.Zero },
        colors = colors,
        indices = emptyList(),
    )

    return RouteRibbonMesh(
        vertices = vertices,
        vertexCount = positions.size,
        triangleCount = maxOf(0, positions.size - 2),
    )
}

internal fun routeGradientColor(
    progressRatio: Float,
    startColor: Color = ROUTE_START_COLOR,
    finishColor: Color = ROUTE_FINISH_COLOR,
): Color {
    return lerp(startColor, finishColor, progressRatio.coerceIn(0f, 1f))
}

private fun dedupeGradientPoints(points: List<RouteGradientPoint>): List<RouteGradientPoint> {
    if (points.size <= 2) {
        return points
    }

    val deduped = ArrayList<RouteGradientPoint>(points.size)
    points.forEach { point ->
        val previous = deduped.lastOrNull()
        if (previous == null) {
            deduped += point
        } else if (screenPointDistance(previous.point, point.point) <= MIN_POINT_DISTANCE_PX) {
            deduped[deduped.lastIndex] = point
        } else {
            deduped += point
        }
    }
    return deduped
}

private fun joinOffsetVector(
    segments: List<SegmentVec>,
    pointIndex: Int,
    halfWidth: Float,
    miterLimitRatio: Float,
): Vec2 {
    val previousSegment = segments.getOrNull(pointIndex - 1) ?: segments.firstOrNull()
    val nextSegment = segments.getOrNull(pointIndex) ?: segments.lastOrNull()
    val previousDirection = previousSegment?.direction
    val nextDirection = nextSegment?.direction
    if (previousDirection == null && nextDirection == null) {
        return Vec2(0f, halfWidth)
    }
    if (previousDirection == null) {
        return leftNormal(nextDirection!!) * halfWidth
    }
    if (nextDirection == null) {
        return leftNormal(previousDirection) * halfWidth
    }

    val previousNormal = leftNormal(previousDirection)
    val nextNormal = leftNormal(nextDirection)
    val summedNormal = previousNormal + nextNormal
    val summedLength = summedNormal.length()
    val turnDot = dot(previousDirection, nextDirection)
    val fallbackNormal = if (summedLength > MIN_POINT_DISTANCE_PX) {
        summedNormal / summedLength
    } else {
        nextNormal
    }

    if (summedLength <= MIN_POINT_DISTANCE_PX || turnDot <= MIN_MITER_TURN_DOT) {
        return fallbackNormal * halfWidth
    }

    val miter = summedNormal / summedLength
    val denominator = dot(miter, nextNormal)
    if (abs(denominator) < MIN_MITER_DENOMINATOR) {
        return fallbackNormal * halfWidth
    }

    val segmentLimit = minOf(
        previousSegment.lengthPx,
        nextSegment.lengthPx,
    ) * JOIN_SEGMENT_LIMIT_FRACTION
    val maxJoinLength = minOf(
        halfWidth * miterLimitRatio,
        segmentLimit.coerceAtLeast(halfWidth),
    )
    val miterLength = (halfWidth / denominator)
        .coerceIn(-maxJoinLength, maxJoinLength)
    return miter * miterLength
}

private fun segmentVector(
    start: ScreenPoint,
    end: ScreenPoint,
): SegmentVec {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx, dy).coerceAtLeast(MIN_POINT_DISTANCE_PX)
    return SegmentVec(
        direction = Vec2(dx / length, dy / length),
        lengthPx = length,
    )
}

private fun screenPointDistance(
    first: ScreenPoint,
    second: ScreenPoint,
): Float {
    return hypot(first.x - second.x, first.y - second.y)
}

private fun leftNormal(direction: Vec2): Vec2 {
    return Vec2(-direction.y, direction.x)
}

private fun dot(
    first: Vec2,
    second: Vec2,
): Float {
    return first.x * second.x + first.y * second.y
}

private data class Vec2(
    val x: Float,
    val y: Float,
) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun times(scale: Float): Vec2 = Vec2(x * scale, y * scale)

    operator fun div(scale: Float): Vec2 = Vec2(x / scale, y / scale)

    fun length(): Float = hypot(x, y)
}

private data class SegmentVec(
    val direction: Vec2,
    val lengthPx: Float,
)
