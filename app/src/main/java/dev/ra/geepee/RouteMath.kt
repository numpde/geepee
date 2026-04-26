package dev.ra.geepee

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val BOUNDS_PADDING_METERS = 12.0
private const val ROUTE_RENDER_CHUNK_SIZE = 128
private const val LOCAL_EDGE_WINDOW = 192
private const val LOCAL_EDGE_MARGIN = 24
private const val LOCAL_EDGE_FALLBACK_DISTANCE_METERS = 150.0
private const val ROUTE_EDGE_GRID_METERS = 160.0

data class GeoPoint(
    val lat: Double,
    val lon: Double,
)

data class GeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float?,
    val headingDegrees: Float?,
    val speedMetersPerSecond: Float?,
    val timestampMillis: Long,
    val bearingAccuracyDegrees: Float? = null,
)

data class Projection(
    val originLat: Double,
    val originLon: Double,
    val cosLat: Double,
)

data class ProjectedPoint(
    val x: Double,
    val y: Double,
)

data class RouteSegment(
    val geoPoints: List<GeoPoint>,
    val points: List<ProjectedPoint>,
    val cumulativeMeters: List<Double>,
    val lengthMeters: Double,
    val offsetMeters: Double,
    val renderChunks: List<RouteRenderChunk>,
)

data class RouteModel(
    val projection: Projection,
    val segments: List<RouteSegment>,
    val edges: List<RouteEdge>,
    val spatialIndex: RouteSpatialIndex,
    val pointCount: Int,
    val totalLengthMeters: Double,
    val bounds: Bounds,
    val isClosedLoop: Boolean = false,
)

data class RouteAnalysis(
    val point: ProjectedPoint,
    val nearestPoint: ProjectedPoint,
    val nearestGeoPoint: GeoPoint,
    val routeTangentX: Double,
    val routeTangentY: Double,
    val offRouteMeters: Double,
    val routeMeters: Double,
    val progressMeters: Double,
    val remainingMeters: Double,
    val progressRatio: Double,
    val accuracyMeters: Float?,
    val nearestEdgeIndex: Int,
)

data class Bounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
)

data class RouteViewport(
    val centerX: Double,
    val centerY: Double,
    val widthMeters: Double,
)

data class ScreenPoint(
    val x: Float,
    val y: Float,
)

data class RouteRenderModel(
    val polylines: List<List<ScreenPoint>>,
    val gradientPolylines: List<RouteGradientPolyline>,
    val nearestPoint: ScreenPoint?,
    val hypothesisPoints: List<RouteHypothesisScreenPoint>,
    val nearestPointUncertainty: Float,
    val userPoint: ScreenPoint?,
    val edgePoint: ScreenPoint?,
    val historyPoints: List<ScreenPoint>,
)

data class RouteRenderChunk(
    val points: List<ProjectedPoint>,
    val progressRatios: List<Float>,
    val bounds: Bounds,
)

data class RouteGradientPolyline(
    val points: List<RouteGradientPoint>,
)

data class RouteGradientPoint(
    val point: ScreenPoint,
    val progressRatio: Float,
)

data class RouteHypothesisScreenPoint(
    val point: ScreenPoint,
    val confidence: Float,
    val isPrimary: Boolean,
    val secondaryConfidenceContribution: Float,
)

private data class RouteAmbiguityDisplay(
    val visibleHypotheses: List<RouteHypothesisScreenPoint>,
    val nearestPointUncertainty: Float,
)

private const val MIN_VISIBLE_AMBIGUITY_CONFIDENCE = 0.18f
private const val MIN_VISIBLE_AMBIGUITY_SEPARATION_PX = 18f

data class RouteEdge(
    val startPoint: ProjectedPoint,
    val endPoint: ProjectedPoint,
    val startGeoPoint: GeoPoint,
    val endGeoPoint: GeoPoint,
    val routeMetersAtStart: Double,
    val lengthMeters: Double,
    val bounds: Bounds,
)

data class RouteSpatialIndex(
    val cellSizeMeters: Double,
    val cells: Map<GridCell, IntArray>,
    val minCellX: Int,
    val maxCellX: Int,
    val minCellY: Int,
    val maxCellY: Int,
)

data class GridCell(
    val x: Int,
    val y: Int,
)

fun buildRouteModel(rawSegments: List<List<GeoPoint>>): RouteModel {
    require(rawSegments.isNotEmpty()) { "Route needs at least one segment." }
    val allPoints = rawSegments.flatten()
    require(allPoints.size >= 2) { "Route needs at least two GPX points." }

    val projection = buildProjection(allPoints)
    val isClosedLoop = distanceBetweenProjected(
        projectPoint(allPoints.first(), projection),
        projectPoint(allPoints.last(), projection),
    ) <= 12.0
    var totalLengthMeters = 0.0
    var pointCount = 0
    val edges = mutableListOf<RouteEdge>()

    val segments = rawSegments.map { segment ->
        val projectedPoints = segment.map { projectPoint(it, projection) }
        pointCount += projectedPoints.size
        val cumulativeMeters = mutableListOf(0.0)
        val offsetMeters = totalLengthMeters
        var lengthMeters = 0.0

        for (index in 1 until projectedPoints.size) {
            val startPoint = projectedPoints[index - 1]
            val endPoint = projectedPoints[index]
            val legLength = distanceBetweenProjected(startPoint, endPoint)
            edges += RouteEdge(
                startPoint = startPoint,
                endPoint = endPoint,
                startGeoPoint = segment[index - 1],
                endGeoPoint = segment[index],
                routeMetersAtStart = offsetMeters + lengthMeters,
                lengthMeters = legLength,
                bounds = computeBounds(listOf(startPoint, endPoint), paddingMeters = 0.0),
            )
            lengthMeters += legLength
            cumulativeMeters += lengthMeters
        }

        totalLengthMeters += lengthMeters

        RouteSegment(
            geoPoints = segment,
            points = projectedPoints,
            cumulativeMeters = cumulativeMeters,
            lengthMeters = lengthMeters,
            offsetMeters = offsetMeters,
            renderChunks = buildRouteRenderChunks(
                points = projectedPoints,
                routeMeters = cumulativeMeters.map { offsetMeters + it },
            ),
        )
    }

    return RouteModel(
        projection = projection,
        segments = segments,
        edges = edges,
        spatialIndex = buildRouteSpatialIndex(edges, ROUTE_EDGE_GRID_METERS),
        pointCount = pointCount,
        totalLengthMeters = totalLengthMeters,
        bounds = computeBounds(segments.flatMap { it.points }),
        isClosedLoop = isClosedLoop,
    )
}

fun analyzeLocationAgainstModel(
    model: RouteModel,
    fix: LocationFix,
    previousNearestEdgeIndex: Int? = null,
): RouteAnalysis {
    val projectedFix = projectLocationFix(model, fix)
    val nearest = collectRouteCandidates(
        model = model,
        projectedFix = projectedFix,
        previousNearestEdgeIndexes = previousNearestEdgeIndex?.let(::listOf).orEmpty(),
    )
        .minByOrNull { it.offRouteMeters }
        ?: emptyRouteAnalysis(projectedFix)
    return nearest.copy(
        progressMeters = nearest.routeMeters,
        remainingMeters = max(0.0, model.totalLengthMeters - nearest.routeMeters),
        progressRatio = if (model.totalLengthMeters > 0.0) {
            nearest.routeMeters / model.totalLengthMeters
        } else {
            0.0
        },
        accuracyMeters = fix.accuracyMeters,
    )
}

internal fun projectLocationFix(model: RouteModel, fix: LocationFix): ProjectedPoint {
    return projectPoint(GeoPoint(fix.lat, fix.lon), model.projection)
}

internal fun collectRouteCandidates(
    model: RouteModel,
    projectedFix: ProjectedPoint,
    previousNearestEdgeIndexes: List<Int> = emptyList(),
): List<RouteAnalysis> {
    if (model.edges.isEmpty()) {
        return emptyList()
    }

    var localCandidates = emptyList<RouteAnalysis>()
    val validPreviousEdgeIndexes = previousNearestEdgeIndexes.filter { it in 0..model.edges.lastIndex }

    if (validPreviousEdgeIndexes.isNotEmpty()) {
        localCandidates = analyzeProjectedPointCandidates(
            model = model,
            projectedFix = projectedFix,
            edgeIndexes = localWindowEdgeIndexes(model, validPreviousEdgeIndexes),
        )
    }

    val indexedCandidates = collectIndexedCandidates(
        model = model,
        projectedFix = projectedFix,
    )
    if (indexedCandidates.isNotEmpty()) {
        val combinedIndexes = LinkedHashSet<Int>().apply {
            indexedCandidates.forEach { add(it.nearestEdgeIndex) }
            localCandidates.forEach { add(it.nearestEdgeIndex) }
        }.toIntArray()
        return analyzeProjectedPointCandidates(model, projectedFix, combinedIndexes)
    }

    if (localCandidates.isNotEmpty()) {
        val localBest = localCandidates.minByOrNull { it.offRouteMeters }
        if (localBest != null && isLocalEdgeMatchReliable(localBest, validPreviousEdgeIndexes, model.edges.lastIndex)) {
            return localCandidates
        }
    }

    return analyzeProjectedPointRange(model, projectedFix, 0, model.edges.lastIndex)
}

internal fun buildRouteRenderModel(
    routeModel: RouteModel,
    analysis: RouteAnalysis?,
    matchHypotheses: List<RouteMatchDisplayHypothesis> = emptyList(),
    historyPoints: List<ProjectedPoint> = emptyList(),
    localWindowWidthMeters: Double,
    canvasWidth: Float,
    canvasHeight: Float,
    lookAheadFraction: Double = 0.0,
    rotationDegrees: Float = 0f,
    includeGradientPolylines: Boolean = false,
    boundsOverride: Bounds? = null,
): RouteRenderModel {
    if (canvasWidth <= 0f || canvasHeight <= 0f) {
        return RouteRenderModel(emptyList(), emptyList(), null, emptyList(), 0f, null, null, emptyList())
    }

    val bounds = boundsOverride ?: if (analysis == null) {
        routeModel.bounds
    } else {
        createLocalBounds(
            anchorPoint = analysis.nearestPoint,
            widthMeters = localWindowWidthMeters,
            canvasWidth = canvasWidth.toDouble(),
            canvasHeight = canvasHeight.toDouble(),
            routeTangentX = analysis.routeTangentX,
            routeTangentY = analysis.routeTangentY,
            lookAheadFraction = lookAheadFraction,
        )
    }

    val projector = createScreenProjector(bounds, canvasWidth.toDouble(), canvasHeight.toDouble())
    val screenBounds = ScreenBounds(
        minX = 0f,
        maxX = canvasWidth,
        minY = 0f,
        maxY = canvasHeight,
    )
    val screenCenter = ScreenPoint(
        x = canvasWidth / 2f,
        y = canvasHeight / 2f,
    )

    val polylines = if (includeGradientPolylines) {
        emptyList()
    } else {
        routeModel.segments
            .flatMap { segment ->
                segment.renderChunks
                    .asSequence()
                    .filter { chunk -> boundsIntersect(chunk.bounds, bounds) }
                    .flatMap { chunk ->
                        clipScreenPolylineToBounds(
                            points = chunk.points.map { point ->
                                rotateScreenPoint(
                                    point = toScreenPoint(point, projector),
                                    center = screenCenter,
                                    rotationDegrees = rotationDegrees.toDouble(),
                                )
                            },
                            bounds = screenBounds,
                        ).asSequence()
                    }
                    .toList()
            }
    }
    val gradientPolylines = if (includeGradientPolylines) {
        routeModel.segments
            .flatMap { segment ->
                segment.renderChunks
                    .asSequence()
                    .filter { chunk -> boundsIntersect(chunk.bounds, bounds) }
                    .flatMap { chunk ->
                        clipGradientPolylineToBounds(
                            points = chunk.points.map { point ->
                                rotateScreenPoint(
                                    point = toScreenPoint(point, projector),
                                    center = screenCenter,
                                    rotationDegrees = rotationDegrees.toDouble(),
                                )
                            },
                            progressRatios = chunk.progressRatios,
                            bounds = screenBounds,
                        ).asSequence().map { polyline ->
                            RouteGradientPolyline(
                                points = polyline,
                            )
                        }
                }
                .toList()
            }
    } else {
        emptyList()
    }

    val nearestProjected = analysis?.nearestPoint?.let { point ->
        rotateScreenPoint(
            point = toScreenPoint(point, projector),
            center = screenCenter,
            rotationDegrees = rotationDegrees.toDouble(),
        )
    }
    val nearestPoint = nearestProjected?.takeIf { isScreenPointWithinBounds(it, screenBounds) }
    val ambiguityDisplay = classifyAmbiguityDisplay(
        collapseScreenHypotheses(
            matchHypotheses.mapNotNull { hypothesis ->
                val projectedPoint = rotateScreenPoint(
                    point = toScreenPoint(hypothesis.analysis.nearestPoint, projector),
                    center = screenCenter,
                    rotationDegrees = rotationDegrees.toDouble(),
                )
                projectedPoint
                    .takeIf { isScreenPointWithinBounds(it, screenBounds) }
                    ?.let { visiblePoint ->
                    RouteHypothesisScreenPoint(
                        point = visiblePoint,
                        confidence = hypothesis.confidence,
                        isPrimary = hypothesis.isPrimary,
                        secondaryConfidenceContribution = if (hypothesis.isPrimary) 0f else hypothesis.confidence,
                    )
                }
            },
        ),
    )

    val userProjected = analysis?.point?.let { point ->
        rotateScreenPoint(
            point = toScreenPoint(point, projector),
            center = screenCenter,
            rotationDegrees = rotationDegrees.toDouble(),
        )
    }
    val userVisible = userProjected?.let { isScreenPointWithinBounds(it, screenBounds) } == true
    val userPoint = if (userVisible) userProjected else null

    val edgePoint = if (nearestProjected != null && userProjected != null && !userVisible) {
        clipScreenSegmentToBounds(nearestProjected, userProjected, screenBounds)?.end
    } else {
        null
    }

    val projectedHistoryPoints = historyPoints.mapNotNull { point ->
        rotateScreenPoint(
            point = toScreenPoint(point, projector),
            center = screenCenter,
            rotationDegrees = rotationDegrees.toDouble(),
        ).takeIf { isScreenPointWithinBounds(it, screenBounds) }
    }

    return RouteRenderModel(
        polylines = polylines,
        gradientPolylines = gradientPolylines,
        nearestPoint = nearestPoint,
        hypothesisPoints = ambiguityDisplay.visibleHypotheses,
        nearestPointUncertainty = ambiguityDisplay.nearestPointUncertainty,
        userPoint = userPoint,
        edgePoint = edgePoint,
        historyPoints = projectedHistoryPoints,
    )
}

internal fun createRouteViewport(
    contentBounds: Bounds,
    canvasWidth: Double,
    canvasHeight: Double,
): RouteViewport {
    return RouteViewport(
        centerX = contentBounds.centerX(),
        centerY = contentBounds.centerY(),
        widthMeters = fittedViewportWidthMeters(
            contentBounds = contentBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
        ),
    )
}

internal fun routeViewportBounds(
    viewport: RouteViewport,
    canvasWidth: Double,
    canvasHeight: Double,
): Bounds {
    val heightMeters = viewport.widthMeters * (canvasHeight / canvasWidth)
    return Bounds(
        minX = viewport.centerX - viewport.widthMeters / 2.0,
        maxX = viewport.centerX + viewport.widthMeters / 2.0,
        minY = viewport.centerY - heightMeters / 2.0,
        maxY = viewport.centerY + heightMeters / 2.0,
    )
}

internal fun transformRouteViewport(
    viewport: RouteViewport,
    contentBounds: Bounds,
    canvasWidth: Double,
    canvasHeight: Double,
    centroid: ScreenPoint,
    pan: ScreenPoint,
    zoomChange: Float,
): RouteViewport {
    val currentBounds = routeViewportBounds(
        viewport = viewport,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )
    val minWidthMeters = minimumViewportWidthMeters(contentBounds)
    val maxWidthMeters = fittedViewportWidthMeters(
        contentBounds = contentBounds,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )
    val nextWidthMeters = clamp(
        value = viewport.widthMeters / zoomChange.coerceAtLeast(0.01f).toDouble(),
        minValue = minWidthMeters,
        maxValue = maxWidthMeters,
    )
    val nextHeightMeters = nextWidthMeters * (canvasHeight / canvasWidth)
    val centroidWorld = projectedPointFromScreenPoint(
        point = centroid,
        bounds = currentBounds,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )
    val centroidXFraction = (centroid.x / canvasWidth.toFloat()).toDouble().coerceIn(0.0, 1.0)
    val centroidYFraction = (centroid.y / canvasHeight.toFloat()).toDouble().coerceIn(0.0, 1.0)
    val panXMeters = pan.x.toDouble() / canvasWidth * nextWidthMeters
    val panYMeters = pan.y.toDouble() / canvasHeight * nextHeightMeters
    val unclamped = RouteViewport(
        centerX = centroidWorld.x - (centroidXFraction - 0.5) * nextWidthMeters - panXMeters,
        centerY = centroidWorld.y - (0.5 - centroidYFraction) * nextHeightMeters + panYMeters,
        widthMeters = nextWidthMeters,
    )
    return clampRouteViewport(
        viewport = unclamped,
        contentBounds = contentBounds,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )
}

internal fun geoBoundsForProjectedBounds(
    bounds: Bounds,
    projection: Projection,
): GeoBounds {
    val corners = listOf(
        unprojectPoint(ProjectedPoint(bounds.minX, bounds.minY), projection),
        unprojectPoint(ProjectedPoint(bounds.minX, bounds.maxY), projection),
        unprojectPoint(ProjectedPoint(bounds.maxX, bounds.minY), projection),
        unprojectPoint(ProjectedPoint(bounds.maxX, bounds.maxY), projection),
    )
    return GeoBounds(
        west = corners.minOf { it.lon },
        south = corners.minOf { it.lat },
        east = corners.maxOf { it.lon },
        north = corners.maxOf { it.lat },
    )
}

internal fun projectedBoundsForGeoBounds(
    bounds: GeoBounds,
    projection: Projection,
): Bounds {
    val corners = listOf(
        projectPoint(GeoPoint(bounds.south, bounds.west), projection),
        projectPoint(GeoPoint(bounds.north, bounds.west), projection),
        projectPoint(GeoPoint(bounds.south, bounds.east), projection),
        projectPoint(GeoPoint(bounds.north, bounds.east), projection),
    )
    return Bounds(
        minX = corners.minOf { it.x },
        maxX = corners.maxOf { it.x },
        minY = corners.minOf { it.y },
        maxY = corners.maxOf { it.y },
    )
}

internal fun projectedPointToScreenPoint(
    point: ProjectedPoint,
    bounds: Bounds,
    canvasWidth: Float,
    canvasHeight: Float,
): ScreenPoint {
    return toScreenPoint(
        point = point,
        projector = createScreenProjector(
            bounds = bounds,
            canvasWidth = canvasWidth.toDouble(),
            canvasHeight = canvasHeight.toDouble(),
        ),
    )
}

private data class ScreenBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

private fun analyzeProjectedPointRange(
    model: RouteModel,
    projectedFix: ProjectedPoint,
    startEdgeIndex: Int,
    endEdgeIndex: Int,
) : List<RouteAnalysis> {
    return (startEdgeIndex..endEdgeIndex).map { edgeIndex ->
        analyzeRouteEdge(model.edges[edgeIndex], projectedFix, edgeIndex)
    }
}

private fun analyzeProjectedPointCandidates(
    model: RouteModel,
    projectedFix: ProjectedPoint,
    edgeIndexes: IntArray,
) : List<RouteAnalysis> {
    return edgeIndexes.map { edgeIndex ->
        analyzeRouteEdge(model.edges[edgeIndex], projectedFix, edgeIndex)
    }
}

internal fun analyzeRouteEdge(
    edge: RouteEdge,
    projectedFix: ProjectedPoint,
    edgeIndex: Int,
): RouteAnalysis {
    val nearest = nearestPointOnSegment(projectedFix, edge.startPoint, edge.endPoint)
    val tangentLength = max(edge.lengthMeters, 0.0001)
    val routeMeters = edge.routeMetersAtStart + edge.lengthMeters * nearest.t
    return RouteAnalysis(
        point = projectedFix,
        nearestPoint = nearest.point,
        nearestGeoPoint = interpolateGeoPoint(
            start = edge.startGeoPoint,
            end = edge.endGeoPoint,
            t = nearest.t,
        ),
        routeTangentX = (edge.endPoint.x - edge.startPoint.x) / tangentLength,
        routeTangentY = (edge.endPoint.y - edge.startPoint.y) / tangentLength,
        offRouteMeters = nearest.distance,
        routeMeters = routeMeters,
        progressMeters = routeMeters,
        remainingMeters = 0.0,
        progressRatio = 0.0,
        accuracyMeters = null,
        nearestEdgeIndex = edgeIndex,
    )
}

internal fun emptyRouteAnalysis(projectedFix: ProjectedPoint): RouteAnalysis {
    return RouteAnalysis(
        point = projectedFix,
        nearestPoint = projectedFix,
        nearestGeoPoint = GeoPoint(0.0, 0.0),
        routeTangentX = 0.0,
        routeTangentY = 0.0,
        offRouteMeters = 0.0,
        routeMeters = 0.0,
        progressMeters = 0.0,
        remainingMeters = 0.0,
        progressRatio = 0.0,
        accuracyMeters = null,
        nearestEdgeIndex = -1,
    )
}

private fun isLocalEdgeMatchReliable(
    analysis: RouteAnalysis,
    hintEdgeIndexes: List<Int>,
    lastEdgeIndex: Int,
): Boolean {
    if (analysis.offRouteMeters > LOCAL_EDGE_FALLBACK_DISTANCE_METERS) {
        return false
    }
    return hintEdgeIndexes.any { hintEdgeIndex ->
        val searchStart = max(0, hintEdgeIndex - LOCAL_EDGE_WINDOW)
        val searchEnd = min(lastEdgeIndex, hintEdgeIndex + LOCAL_EDGE_WINDOW)
        if (analysis.nearestEdgeIndex !in searchStart..searchEnd) {
            false
        } else {
            val nearLeftBoundary = searchStart > 0 && analysis.nearestEdgeIndex - searchStart <= LOCAL_EDGE_MARGIN
            val nearRightBoundary = searchEnd < lastEdgeIndex && searchEnd - analysis.nearestEdgeIndex <= LOCAL_EDGE_MARGIN
            !nearLeftBoundary && !nearRightBoundary
        }
    }
}

private fun localWindowEdgeIndexes(
    model: RouteModel,
    hintEdgeIndexes: List<Int>,
): IntArray {
    return LinkedHashSet<Int>().apply {
        hintEdgeIndexes.forEach { hintEdgeIndex ->
            val searchStart = max(0, hintEdgeIndex - LOCAL_EDGE_WINDOW)
            val searchEnd = min(model.edges.lastIndex, hintEdgeIndex + LOCAL_EDGE_WINDOW)
            for (edgeIndex in searchStart..searchEnd) {
                add(edgeIndex)
            }
        }
    }.toIntArray()
}

internal fun candidateEdgeIndexes(
    spatialIndex: RouteSpatialIndex,
    point: ProjectedPoint,
): IntArray {
    val centerCell = gridCellForPoint(point, spatialIndex.cellSizeMeters)
    val collected = LinkedHashSet<Int>()
    val maxRadius = max(
        max(abs(centerCell.x - spatialIndex.minCellX), abs(centerCell.x - spatialIndex.maxCellX)),
        max(abs(centerCell.y - spatialIndex.minCellY), abs(centerCell.y - spatialIndex.maxCellY)),
    )
    for (radius in 0..maxRadius) {
        collectCellRingEdgeIndexes(spatialIndex, centerCell, radius, collected)
    }
    return collected.toIntArray()
}

private fun buildRouteSpatialIndex(
    edges: List<RouteEdge>,
    cellSizeMeters: Double,
): RouteSpatialIndex {
    val cells = mutableMapOf<GridCell, MutableList<Int>>()
    edges.forEachIndexed { edgeIndex, edge ->
        val minCell = gridCellForCoordinates(edge.bounds.minX, edge.bounds.minY, cellSizeMeters)
        val maxCell = gridCellForCoordinates(edge.bounds.maxX, edge.bounds.maxY, cellSizeMeters)
        for (x in minCell.x..maxCell.x) {
            for (y in minCell.y..maxCell.y) {
                cells.getOrPut(GridCell(x, y)) { mutableListOf() } += edgeIndex
            }
        }
    }
    val cellKeys = cells.keys
    return RouteSpatialIndex(
        cellSizeMeters = cellSizeMeters,
        cells = cells.mapValues { (_, value) -> value.toIntArray() },
        minCellX = cellKeys.minOfOrNull { it.x } ?: 0,
        maxCellX = cellKeys.maxOfOrNull { it.x } ?: 0,
        minCellY = cellKeys.minOfOrNull { it.y } ?: 0,
        maxCellY = cellKeys.maxOfOrNull { it.y } ?: 0,
    )
}

private fun collectIndexedCandidates(
    model: RouteModel,
    projectedFix: ProjectedPoint,
): List<RouteAnalysis> {
    val spatialIndex = model.spatialIndex
    if (spatialIndex.cells.isEmpty()) {
        return emptyList()
    }

    val centerCell = gridCellForPoint(projectedFix, spatialIndex.cellSizeMeters)
    val maxRadius = max(
        max(abs(centerCell.x - spatialIndex.minCellX), abs(centerCell.x - spatialIndex.maxCellX)),
        max(abs(centerCell.y - spatialIndex.minCellY), abs(centerCell.y - spatialIndex.maxCellY)),
    )
    val collectedEdgeIndexes = LinkedHashSet<Int>()
    val analyzedCandidates = LinkedHashMap<Int, RouteAnalysis>()

    for (radius in 0..maxRadius) {
        val ringEdgeIndexes = LinkedHashSet<Int>()
        collectCellRingEdgeIndexes(spatialIndex, centerCell, radius, ringEdgeIndexes)
        ringEdgeIndexes.forEach { edgeIndex ->
            if (collectedEdgeIndexes.add(edgeIndex)) {
                analyzedCandidates[edgeIndex] = analyzeRouteEdge(model.edges[edgeIndex], projectedFix, edgeIndex)
            }
        }

        val bestCandidate = analyzedCandidates.values.minByOrNull { it.offRouteMeters } ?: continue
        if (searchExhausted(spatialIndex, centerCell, radius) ||
            bestCandidate.offRouteMeters < lowerBoundToUnsearchedCells(
                point = projectedFix,
                centerCell = centerCell,
                radius = radius,
                cellSizeMeters = spatialIndex.cellSizeMeters,
            )
        ) {
            return analyzedCandidates.values.toList()
        }
    }

    return analyzedCandidates.values.toList()
}

private fun collectCellRingEdgeIndexes(
    spatialIndex: RouteSpatialIndex,
    centerCell: GridCell,
    radius: Int,
    collected: MutableSet<Int>,
) {
    if (radius == 0) {
        spatialIndex.cells[centerCell]?.forEach { collected += it }
        return
    }

    val minX = centerCell.x - radius
    val maxX = centerCell.x + radius
    val minY = centerCell.y - radius
    val maxY = centerCell.y + radius

    for (x in minX..maxX) {
        spatialIndex.cells[GridCell(x, minY)]?.forEach { collected += it }
        spatialIndex.cells[GridCell(x, maxY)]?.forEach { collected += it }
    }
    for (y in (minY + 1) until maxY) {
        spatialIndex.cells[GridCell(minX, y)]?.forEach { collected += it }
        spatialIndex.cells[GridCell(maxX, y)]?.forEach { collected += it }
    }
}

private fun searchExhausted(
    spatialIndex: RouteSpatialIndex,
    centerCell: GridCell,
    radius: Int,
): Boolean {
    return centerCell.x - radius <= spatialIndex.minCellX &&
        centerCell.x + radius >= spatialIndex.maxCellX &&
        centerCell.y - radius <= spatialIndex.minCellY &&
        centerCell.y + radius >= spatialIndex.maxCellY
}

private fun lowerBoundToUnsearchedCells(
    point: ProjectedPoint,
    centerCell: GridCell,
    radius: Int,
    cellSizeMeters: Double,
): Double {
    val searchedMinX = (centerCell.x - radius) * cellSizeMeters
    val searchedMaxX = (centerCell.x + radius + 1) * cellSizeMeters
    val searchedMinY = (centerCell.y - radius) * cellSizeMeters
    val searchedMaxY = (centerCell.y + radius + 1) * cellSizeMeters
    return min(
        min(point.x - searchedMinX, searchedMaxX - point.x),
        min(point.y - searchedMinY, searchedMaxY - point.y),
    )
}

private fun gridCellForPoint(point: ProjectedPoint, cellSizeMeters: Double): GridCell {
    return gridCellForCoordinates(point.x, point.y, cellSizeMeters)
}

private fun gridCellForCoordinates(x: Double, y: Double, cellSizeMeters: Double): GridCell {
    return GridCell(
        x = kotlin.math.floor(x / cellSizeMeters).toInt(),
        y = kotlin.math.floor(y / cellSizeMeters).toInt(),
    )
}

private fun buildRouteRenderChunks(
    points: List<ProjectedPoint>,
    routeMeters: List<Double>,
): List<RouteRenderChunk> {
    if (points.size < 2) {
        return emptyList()
    }

    val chunks = mutableListOf<RouteRenderChunk>()
    var startIndex = 0
    while (startIndex < points.lastIndex) {
        val endIndex = min(points.lastIndex, startIndex + ROUTE_RENDER_CHUNK_SIZE)
        val chunkPoints = points.subList(startIndex, endIndex + 1)
        val totalLengthMeters = routeMeters.last().coerceAtLeast(1.0)
        chunks += RouteRenderChunk(
            points = chunkPoints,
            progressRatios = routeMeters.subList(startIndex, endIndex + 1).map { (it / totalLengthMeters).toFloat() },
            bounds = computeBounds(chunkPoints, paddingMeters = 0.0),
        )
        startIndex = endIndex
    }
    return chunks
}

private fun boundsIntersect(left: Bounds, right: Bounds): Boolean {
    return left.maxX >= right.minX &&
        left.minX <= right.maxX &&
        left.maxY >= right.minY &&
        left.minY <= right.maxY
}

private fun collapseScreenHypotheses(
    hypotheses: List<RouteHypothesisScreenPoint>,
    mergeDistancePx: Float = 14f,
): List<RouteHypothesisScreenPoint> {
    if (hypotheses.isEmpty()) {
        return emptyList()
    }

    val merged = mutableListOf<RouteHypothesisScreenPoint>()
    hypotheses.forEach { hypothesis ->
        val existingIndex = merged.indexOfFirst { existing ->
            hypot(
                (existing.point.x - hypothesis.point.x).toDouble(),
                (existing.point.y - hypothesis.point.y).toDouble(),
            ) <= mergeDistancePx
        }
        if (existingIndex == -1) {
            merged += hypothesis
        } else {
            val existing = merged[existingIndex]
            val totalConfidence = existing.confidence + hypothesis.confidence
            val mergedPoint = if (totalConfidence > 0f) {
                ScreenPoint(
                    x = ((existing.point.x * existing.confidence) + (hypothesis.point.x * hypothesis.confidence)) / totalConfidence,
                    y = ((existing.point.y * existing.confidence) + (hypothesis.point.y * hypothesis.confidence)) / totalConfidence,
                )
            } else {
                existing.point
            }
            merged[existingIndex] = RouteHypothesisScreenPoint(
                point = mergedPoint,
                confidence = totalConfidence.coerceAtMost(1f),
                isPrimary = existing.isPrimary || hypothesis.isPrimary,
                secondaryConfidenceContribution =
                    (existing.secondaryConfidenceContribution + hypothesis.secondaryConfidenceContribution)
                        .coerceIn(0f, 1f),
            )
        }
    }

    return merged.sortedWith(
        compareByDescending<RouteHypothesisScreenPoint> { it.isPrimary }
            .thenByDescending { it.confidence },
    )
}

private fun classifyAmbiguityDisplay(
    hypotheses: List<RouteHypothesisScreenPoint>,
): RouteAmbiguityDisplay {
    val primary = hypotheses.firstOrNull { it.isPrimary }
        ?: return RouteAmbiguityDisplay(
            visibleHypotheses = emptyList(),
            nearestPointUncertainty = 0f,
        )
    val secondaries = hypotheses.filterNot { it.isPrimary }
    val meaningfulSecondary = secondaries.any { hypothesis ->
        if (hypothesis.isPrimary) {
            false
        } else {
            hypothesis.confidence >= MIN_VISIBLE_AMBIGUITY_CONFIDENCE &&
                hypot(
                    (hypothesis.point.x - primary.point.x).toDouble(),
                    (hypothesis.point.y - primary.point.y).toDouble(),
                ) >= MIN_VISIBLE_AMBIGUITY_SEPARATION_PX
        }
    }
    return if (meaningfulSecondary) {
        RouteAmbiguityDisplay(
            visibleHypotheses = hypotheses,
            nearestPointUncertainty = 0f,
        )
    } else {
        RouteAmbiguityDisplay(
            visibleHypotheses = emptyList(),
            nearestPointUncertainty = (
                primary.secondaryConfidenceContribution +
                    secondaries.sumOf { it.confidence.toDouble() }.toFloat()
                )
                .coerceIn(0f, 1f),
        )
    }
}

private fun Bounds.width(): Double = maxX - minX

private fun Bounds.height(): Double = maxY - minY

private fun Bounds.centerX(): Double = (minX + maxX) / 2.0

private fun Bounds.centerY(): Double = (minY + maxY) / 2.0

private fun fittedViewportWidthMeters(
    contentBounds: Bounds,
    canvasWidth: Double,
    canvasHeight: Double,
): Double {
    val aspectRatio = canvasWidth / canvasHeight
    return max(
        contentBounds.width(),
        contentBounds.height() * aspectRatio,
    )
}

private fun minimumViewportWidthMeters(contentBounds: Bounds): Double {
    return max(
        24.0,
        min(contentBounds.width(), contentBounds.height()).coerceAtLeast(1.0) * 0.08,
    )
}

private fun clampRouteViewport(
    viewport: RouteViewport,
    contentBounds: Bounds,
    canvasWidth: Double,
    canvasHeight: Double,
): RouteViewport {
    val clampedWidth = clamp(
        value = viewport.widthMeters,
        minValue = minimumViewportWidthMeters(contentBounds),
        maxValue = fittedViewportWidthMeters(contentBounds, canvasWidth, canvasHeight),
    )
    val visibleHeight = clampedWidth * (canvasHeight / canvasWidth)
    val halfWidth = clampedWidth / 2.0
    val halfHeight = visibleHeight / 2.0
    val minCenterX = if (clampedWidth >= contentBounds.width()) {
        contentBounds.centerX()
    } else {
        contentBounds.minX + halfWidth
    }
    val maxCenterX = if (clampedWidth >= contentBounds.width()) {
        contentBounds.centerX()
    } else {
        contentBounds.maxX - halfWidth
    }
    val minCenterY = if (visibleHeight >= contentBounds.height()) {
        contentBounds.centerY()
    } else {
        contentBounds.minY + halfHeight
    }
    val maxCenterY = if (visibleHeight >= contentBounds.height()) {
        contentBounds.centerY()
    } else {
        contentBounds.maxY - halfHeight
    }
    return RouteViewport(
        centerX = clamp(viewport.centerX, minCenterX, maxCenterX),
        centerY = clamp(viewport.centerY, minCenterY, maxCenterY),
        widthMeters = clampedWidth,
    )
}

private fun computeBounds(
    points: List<ProjectedPoint>,
    paddingMeters: Double = maxBoundsPadding(points),
): Bounds {
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }

    return Bounds(
        minX = minX - paddingMeters,
        maxX = maxX + paddingMeters,
        minY = minY - paddingMeters,
        maxY = maxY + paddingMeters,
    )
}

private fun buildProjection(points: List<GeoPoint>): Projection {
    val averageLat = points.map { it.lat }.average()
    val averageLon = points.map { it.lon }.average()
    return Projection(
        originLat = averageLat,
        originLon = averageLon,
        cosLat = cos(averageLat * PI / 180.0),
    )
}

private fun projectPoint(point: GeoPoint, projection: Projection): ProjectedPoint {
    return ProjectedPoint(
        x = ((point.lon - projection.originLon) * PI / 180.0) * EARTH_RADIUS_METERS * projection.cosLat,
        y = ((point.lat - projection.originLat) * PI / 180.0) * EARTH_RADIUS_METERS,
    )
}

internal fun unprojectPoint(point: ProjectedPoint, projection: Projection): GeoPoint {
    return GeoPoint(
        lat = projection.originLat + (point.y / EARTH_RADIUS_METERS) * (180.0 / PI),
        lon = projection.originLon + (point.x / (EARTH_RADIUS_METERS * projection.cosLat)) * (180.0 / PI),
    )
}

private data class SegmentProjection(
    val point: ProjectedPoint,
    val distance: Double,
    val t: Double,
)

private fun nearestPointOnSegment(
    point: ProjectedPoint,
    start: ProjectedPoint,
    end: ProjectedPoint,
): SegmentProjection {
    val dx = end.x - start.x
    val dy = end.y - start.y
    if (dx == 0.0 && dy == 0.0) {
        return SegmentProjection(start, distanceBetweenProjected(point, start), 0.0)
    }

    val t = clamp(
        ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy),
        0.0,
        1.0,
    )

    val projected = ProjectedPoint(
        x = start.x + dx * t,
        y = start.y + dy * t,
    )

    return SegmentProjection(
        point = projected,
        distance = distanceBetweenProjected(point, projected),
        t = t,
    )
}

private fun interpolateGeoPoint(start: GeoPoint, end: GeoPoint, t: Double): GeoPoint {
    return GeoPoint(
        lat = start.lat + (end.lat - start.lat) * t,
        lon = start.lon + (end.lon - start.lon) * t,
    )
}

private fun distanceBetweenProjected(left: ProjectedPoint, right: ProjectedPoint): Double {
    return hypot(right.x - left.x, right.y - left.y)
}

private fun maxBoundsPadding(points: List<ProjectedPoint>): Double {
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val spanX = maxX - minX
    val spanY = maxY - minY
    return max(spanX, spanY) * 0.12 + BOUNDS_PADDING_METERS
}

private fun createLocalBounds(
    anchorPoint: ProjectedPoint,
    widthMeters: Double,
    canvasWidth: Double,
    canvasHeight: Double,
    routeTangentX: Double,
    routeTangentY: Double,
    lookAheadFraction: Double,
): Bounds {
    val heightMeters = widthMeters * (canvasHeight / canvasWidth)
    val tangentLength = hypot(routeTangentX, routeTangentY).coerceAtLeast(0.0001)
    val normalizedTangentX = routeTangentX / tangentLength
    val normalizedTangentY = routeTangentY / tangentLength
    val forwardBiasMeters = min(widthMeters, heightMeters) * lookAheadFraction.coerceIn(0.0, 0.45)
    val shiftedAnchor = ProjectedPoint(
        x = anchorPoint.x + normalizedTangentX * forwardBiasMeters,
        y = anchorPoint.y + normalizedTangentY * forwardBiasMeters,
    )
    val halfWidth = widthMeters / 2.0
    val halfHeight = heightMeters / 2.0
    return Bounds(
        minX = shiftedAnchor.x - halfWidth,
        maxX = shiftedAnchor.x + halfWidth,
        minY = shiftedAnchor.y - halfHeight,
        maxY = shiftedAnchor.y + halfHeight,
    )
}

private fun clipScreenPolylineToBounds(points: List<ScreenPoint>, bounds: ScreenBounds): List<List<ScreenPoint>> {
    if (points.isEmpty()) {
        return emptyList()
    }

    val polylines = mutableListOf<List<ScreenPoint>>()
    var current = mutableListOf<ScreenPoint>()

    for (index in 0 until points.lastIndex) {
        val clipped = clipScreenSegmentToBounds(points[index], points[index + 1], bounds)
        if (clipped == null) {
            if (current.size >= 2) {
                polylines += current.toList()
            }
            current = mutableListOf()
            continue
        }

        if (current.isEmpty()) {
            current += clipped.start
        } else if (!sameScreenPoint(current.last(), clipped.start)) {
            current += clipped.start
        }
        current += clipped.end
    }

    if (current.size >= 2) {
        polylines += current.toList()
    }

    return polylines
}

private fun clipGradientPolylineToBounds(
    points: List<ScreenPoint>,
    progressRatios: List<Float>,
    bounds: ScreenBounds,
): List<List<RouteGradientPoint>> {
    if (points.isEmpty() || points.size != progressRatios.size) {
        return emptyList()
    }

    val polylines = mutableListOf<List<RouteGradientPoint>>()
    var current = mutableListOf<RouteGradientPoint>()

    for (index in 0 until points.lastIndex) {
        val clipped = clipScreenSegmentToBounds(points[index], points[index + 1], bounds)
        if (clipped == null) {
            if (current.size >= 2) {
                polylines += current.toList()
            }
            current = mutableListOf()
            continue
        }

        val startProgress = lerpFloat(
            start = progressRatios[index],
            stop = progressRatios[index + 1],
            fraction = clipped.startT.toFloat(),
        )
        val endProgress = lerpFloat(
            start = progressRatios[index],
            stop = progressRatios[index + 1],
            fraction = clipped.endT.toFloat(),
        )
        val startPoint = RouteGradientPoint(clipped.start, startProgress)
        val endPoint = RouteGradientPoint(clipped.end, endProgress)

        if (current.isEmpty()) {
            current += startPoint
        } else if (!sameScreenPoint(current.last().point, startPoint.point)) {
            current += startPoint
        }
        current += endPoint
    }

    if (current.size >= 2) {
        polylines += current.toList()
    }

    return polylines
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private data class ClippedScreenSegment(
    val start: ScreenPoint,
    val end: ScreenPoint,
    val startT: Double,
    val endT: Double,
)

private fun clipScreenSegmentToBounds(
    start: ScreenPoint,
    end: ScreenPoint,
    bounds: ScreenBounds,
): ClippedScreenSegment? {
    var t0 = 0.0
    var t1 = 1.0
    val dx = (end.x - start.x).toDouble()
    val dy = (end.y - start.y).toDouble()
    val checks = listOf(
        -dx to (start.x - bounds.minX).toDouble(),
        dx to (bounds.maxX - start.x).toDouble(),
        -dy to (start.y - bounds.minY).toDouble(),
        dy to (bounds.maxY - start.y).toDouble(),
    )

    for ((p, q) in checks) {
        if (p == 0.0 && q < 0.0) {
            return null
        }
        if (p == 0.0) {
            continue
        }

        val ratio = q / p
        if (p < 0.0) {
            if (ratio > t1) {
                return null
            }
            if (ratio > t0) {
                t0 = ratio
            }
        } else {
            if (ratio < t0) {
                return null
            }
            if (ratio < t1) {
                t1 = ratio
            }
        }
    }

    return ClippedScreenSegment(
        start = interpolateScreenPoint(start, end, t0),
        end = interpolateScreenPoint(start, end, t1),
        startT = t0,
        endT = t1,
    )
}

private fun interpolateScreenPoint(
    start: ScreenPoint,
    end: ScreenPoint,
    t: Double,
): ScreenPoint {
    return ScreenPoint(
        x = (start.x + (end.x - start.x) * t).toFloat(),
        y = (start.y + (end.y - start.y) * t).toFloat(),
    )
}

private fun isScreenPointWithinBounds(point: ScreenPoint, bounds: ScreenBounds): Boolean {
    return point.x >= bounds.minX &&
        point.x <= bounds.maxX &&
        point.y >= bounds.minY &&
        point.y <= bounds.maxY
}

private fun sameScreenPoint(left: ScreenPoint, right: ScreenPoint): Boolean {
    return abs(left.x - right.x) < 0.001 && abs(left.y - right.y) < 0.001
}

private data class ScreenProjector(
    val scale: Double,
    val minX: Double,
    val minY: Double,
    val offsetX: Double,
    val offsetY: Double,
    val canvasHeight: Double,
)

private fun createScreenProjector(bounds: Bounds, canvasWidth: Double, canvasHeight: Double): ScreenProjector {
    val width = max(1.0, bounds.maxX - bounds.minX)
    val height = max(1.0, bounds.maxY - bounds.minY)
    val scale = min(canvasWidth / width, canvasHeight / height)
    val usedWidth = width * scale
    val usedHeight = height * scale
    return ScreenProjector(
        scale = scale,
        minX = bounds.minX,
        minY = bounds.minY,
        offsetX = (canvasWidth - usedWidth) / 2.0,
        offsetY = (canvasHeight - usedHeight) / 2.0,
        canvasHeight = canvasHeight,
    )
}

private fun projectedPointFromScreenPoint(
    point: ScreenPoint,
    bounds: Bounds,
    canvasWidth: Double,
    canvasHeight: Double,
): ProjectedPoint {
    val widthMeters = bounds.width().coerceAtLeast(1.0)
    val heightMeters = bounds.height().coerceAtLeast(1.0)
    val xFraction = (point.x / canvasWidth.toFloat()).toDouble().coerceIn(0.0, 1.0)
    val yFraction = (point.y / canvasHeight.toFloat()).toDouble().coerceIn(0.0, 1.0)
    return ProjectedPoint(
        x = bounds.minX + xFraction * widthMeters,
        y = bounds.maxY - yFraction * heightMeters,
    )
}

private fun toScreenPoint(point: ProjectedPoint, projector: ScreenProjector): ScreenPoint {
    return ScreenPoint(
        x = (projector.offsetX + (point.x - projector.minX) * projector.scale).toFloat(),
        y = (projector.canvasHeight - projector.offsetY - (point.y - projector.minY) * projector.scale).toFloat(),
    )
}

private fun rotateScreenPoint(
    point: ScreenPoint,
    center: ScreenPoint,
    rotationDegrees: Double,
): ScreenPoint {
    if (rotationDegrees == 0.0) {
        return point
    }
    val radians = Math.toRadians(rotationDegrees)
    val dx = point.x - center.x
    val dy = point.y - center.y
    return ScreenPoint(
        x = (center.x + (dx * cos(radians) - dy * sin(radians))).toFloat(),
        y = (center.y + (dx * sin(radians) + dy * cos(radians))).toFloat(),
    )
}

private fun clamp(value: Double, minValue: Double, maxValue: Double): Double {
    return min(max(value, minValue), maxValue)
}
