package dev.ra.geepee

import kotlin.math.max
import kotlin.math.min

internal enum class RoutePoiKind {
    DrinkingWater,
    Toilets,
    Shelter,
    PicnicSite,
    BicycleRepairStation,
    BicycleShop,
}

internal data class RoutePoi(
    val featureId: String,
    val kind: RoutePoiKind,
    val name: String?,
    val geoPoint: GeoPoint,
    val projectedPoint: ProjectedPoint,
)

internal data class RouteNearbyWaySnippet(
    val featureId: String,
    val points: List<ProjectedPoint>,
    val bounds: Bounds,
)

internal data class RouteContext(
    val pois: List<RoutePoi> = emptyList(),
    val nearbyWays: List<RouteNearbyWaySnippet> = emptyList(),
)

internal data class LocalNearbyWayDebugStatus(
    val localTileCount: Int = 0,
    val loadedLocalTileCount: Int = 0,
    val hasVisibleTileData: Boolean? = null,
    val nearbyWaysLoading: Boolean = false,
    val nearbyWayCount: Int = 0,
    val errorMessage: String? = null,
) {
    companion object {
        fun loading(
            localTileCount: Int,
            loadedLocalTileCount: Int,
            existingNearbyWayCount: Int,
        ): LocalNearbyWayDebugStatus {
            val hasVisibleTileData = loadedLocalTileCount > 0
            return LocalNearbyWayDebugStatus(
                localTileCount = localTileCount,
                loadedLocalTileCount = loadedLocalTileCount,
                hasVisibleTileData = hasVisibleTileData,
                nearbyWaysLoading = hasVisibleTileData,
                nearbyWayCount = if (hasVisibleTileData) existingNearbyWayCount else 0,
            )
        }

        fun resolved(
            localTileCount: Int,
            loadedLocalTileCount: Int,
            nearbyWayCount: Int,
        ): LocalNearbyWayDebugStatus {
            return LocalNearbyWayDebugStatus(
                localTileCount = localTileCount,
                loadedLocalTileCount = loadedLocalTileCount,
                hasVisibleTileData = loadedLocalTileCount > 0,
                nearbyWayCount = nearbyWayCount,
            )
        }

        fun failed(
            localTileCount: Int,
            loadedLocalTileCount: Int,
            hasVisibleTileData: Boolean,
            errorMessage: String,
        ): LocalNearbyWayDebugStatus {
            return LocalNearbyWayDebugStatus(
                localTileCount = localTileCount,
                loadedLocalTileCount = loadedLocalTileCount,
                hasVisibleTileData = hasVisibleTileData,
                nearbyWayCount = 0,
                errorMessage = errorMessage,
            )
        }
    }
}

internal data class RouteMapInfoState(
    val localNearbyWays: LocalNearbyWayDebugStatus? = null,
    val nearbyWays: List<RouteNearbyWaySnippet> = emptyList(),
) {
    companion object {
        fun resolvedNearbyWays(
            localTileCount: Int,
            loadedLocalTileCount: Int,
            nearbyWays: List<RouteNearbyWaySnippet>,
        ): RouteMapInfoState {
            return RouteMapInfoState(
                localNearbyWays = LocalNearbyWayDebugStatus.resolved(
                    localTileCount = localTileCount,
                    loadedLocalTileCount = loadedLocalTileCount,
                    nearbyWayCount = nearbyWays.size,
                ),
                nearbyWays = nearbyWays,
            )
        }

        fun failedNearbyWays(
            localTileCount: Int,
            loadedLocalTileCount: Int,
            hasVisibleTileData: Boolean,
            errorMessage: String,
        ): RouteMapInfoState {
            return RouteMapInfoState(
                localNearbyWays = LocalNearbyWayDebugStatus.failed(
                    localTileCount = localTileCount,
                    loadedLocalTileCount = loadedLocalTileCount,
                    hasVisibleTileData = hasVisibleTileData,
                    errorMessage = errorMessage,
                ),
                nearbyWays = emptyList(),
            )
        }
    }
}

internal fun LocalNearbyWayDebugStatus.finishLoading(
    nearbyWayCount: Int = this.nearbyWayCount,
    errorMessage: String? = this.errorMessage,
): LocalNearbyWayDebugStatus {
    return copy(
        nearbyWaysLoading = false,
        nearbyWayCount = nearbyWayCount,
        errorMessage = errorMessage,
    )
}

internal fun RouteMapInfoState.withStatus(status: LocalNearbyWayDebugStatus?): RouteMapInfoState {
    return copy(localNearbyWays = status)
}

internal fun RouteMapInfoState.withNearbyWays(nearbyWays: List<RouteNearbyWaySnippet>): RouteMapInfoState {
    return copy(nearbyWays = nearbyWays)
}

internal fun RouteMapInfoState.clearNearbyWayResult(): RouteMapInfoState {
    return copy(
        localNearbyWays = localNearbyWays?.finishLoading(
            nearbyWayCount = 0,
            errorMessage = null,
        ),
        nearbyWays = emptyList(),
    )
}

internal fun RouteMapInfoState.beginNearbyWayLoad(status: LocalNearbyWayDebugStatus): RouteMapInfoState {
    return copy(localNearbyWays = status)
}

internal fun RouteMapInfoState.completeNearbyWayLoad(result: RouteMapInfoState): RouteMapInfoState {
    return copy(
        localNearbyWays = result.localNearbyWays?.finishLoading(),
        nearbyWays = result.nearbyWays,
    )
}

internal data class RouteContextState(
    val pois: List<RoutePoi> = emptyList(),
    val mapInfo: RouteMapInfoState = RouteMapInfoState(),
)

internal fun RouteContextState.withPois(pois: List<RoutePoi>): RouteContextState {
    return copy(pois = pois)
}

internal fun RouteContextState.withMapInfo(mapInfo: RouteMapInfoState): RouteContextState {
    return copy(mapInfo = mapInfo)
}

private data class RoutePoiCandidate(
    val poi: RoutePoi,
    val routeMeters: Double,
    val offsetMeters: Double,
)

internal fun buildRoutePois(
    routeModel: RouteModel,
    packs: List<TileContextPack>,
    config: TileContextConfig,
): List<RoutePoi> {
    return collectRoutePoiCandidates(
        routeModel = routeModel,
        packs = packs,
        config = config,
    ).values
        .sortedBy(RoutePoiCandidate::routeMeters)
        .map(RoutePoiCandidate::poi)
}

internal fun buildRouteNearbyWays(
    routeModel: RouteModel,
    packs: List<TileContextPack>,
    config: TileContextConfig,
    focusGeoPoint: GeoPoint? = null,
    focusWindowWidthMeters: Double? = null,
): List<RouteNearbyWaySnippet> {
    val nearbyWayFocusBounds = nearbyWayFocusBounds(
        routeModel = routeModel,
        focusGeoPoint = focusGeoPoint,
        focusWindowWidthMeters = focusWindowWidthMeters,
        haloMeters = config.wayHaloMeters,
        continuationMeters = config.nearbyWayContinuationMeters,
    )
    val focusHintEdgeIndexes = nearbyWayFocusBounds?.let { bounds ->
        routeEdgeIndexesIntersectingBounds(
            model = routeModel,
            bounds = expandBounds(bounds, config.wayHaloMeters + config.nearbyWayContinuationMeters),
        )
    }.orEmpty().ifEmpty {
        focusGeoPoint?.let { point ->
            collectRouteCandidates(
                model = routeModel,
                projectedFix = projectGeoPointToRouteProjection(point, routeModel.projection),
            ).minByOrNull(RouteAnalysis::offRouteMeters)?.nearestEdgeIndex?.let(::listOf)
        }.orEmpty()
    }
    return collectNearbyWayFeatures(packs).values.flatMap { feature ->
        extractNearbyWaySnippets(
            routeModel = routeModel,
            feature = feature,
            haloMeters = config.wayHaloMeters,
            continuationMeters = config.nearbyWayContinuationMeters,
            focusBounds = nearbyWayFocusBounds,
            initialNearestEdgeIndexes = focusHintEdgeIndexes,
            restrictToHintWindow = focusHintEdgeIndexes.isNotEmpty(),
        )
    }
}

internal fun buildRouteContext(
    routeModel: RouteModel,
    packs: List<TileContextPack>,
    config: TileContextConfig,
    nearbyWayFocusGeoPoint: GeoPoint? = null,
    nearbyWayFocusWindowWidthMeters: Double? = null,
): RouteContext {
    return RouteContext(
        pois = buildRoutePois(
            routeModel = routeModel,
            packs = packs,
            config = config,
        ),
        nearbyWays = buildRouteNearbyWays(
            routeModel = routeModel,
            packs = packs,
            config = config,
            focusGeoPoint = nearbyWayFocusGeoPoint,
            focusWindowWidthMeters = nearbyWayFocusWindowWidthMeters,
        ),
    )
}

private fun collectRoutePoiCandidates(
    routeModel: RouteModel,
    packs: List<TileContextPack>,
    config: TileContextConfig,
): LinkedHashMap<String, RoutePoiCandidate> {
    val poisByFeatureId = linkedMapOf<String, RoutePoiCandidate>()
    packs.forEach { pack ->
        pack.features.forEach { feature ->
            if (feature.geometryKind != TileGeometryKind.Point) {
                return@forEach
            }
            val point = feature.geometry.singleOrNull() ?: return@forEach
            val kind = routePoiKind(feature.tags) ?: return@forEach
            val analysis = analyzeLocationAgainstModel(
                model = routeModel,
                fix = LocationFix(
                    lat = point.lat,
                    lon = point.lon,
                    accuracyMeters = null,
                    headingDegrees = null,
                    speedMetersPerSecond = null,
                    timestampMillis = 0L,
                ),
            )
            val maxOffsetMeters = poiOffsetLimitMeters(kind, config)
            if (analysis.offRouteMeters > maxOffsetMeters) {
                return@forEach
            }
            val candidate = RoutePoi(
                featureId = feature.featureId,
                kind = kind,
                name = feature.tags["name"],
                geoPoint = point,
                projectedPoint = projectGeoPointToRouteProjection(point, routeModel.projection),
            )
            val candidateWithMetrics = RoutePoiCandidate(
                poi = candidate,
                routeMeters = analysis.routeMeters,
                offsetMeters = analysis.offRouteMeters,
            )
            val previous = poisByFeatureId[feature.featureId]
            if (previous == null || candidateWithMetrics.offsetMeters < previous.offsetMeters) {
                poisByFeatureId[feature.featureId] = candidateWithMetrics
            }
        }
    }
    return poisByFeatureId
}

private fun collectNearbyWayFeatures(packs: List<TileContextPack>): LinkedHashMap<String, TileContextFeature> {
    val wayFeaturesById = linkedMapOf<String, TileContextFeature>()
    packs.forEach { pack ->
        pack.features.forEach { feature ->
            if (feature.geometryKind != TileGeometryKind.Way) {
                return@forEach
            }
            val previous = wayFeaturesById[feature.featureId]
            if (previous == null || feature.geometry.size > previous.geometry.size) {
                wayFeaturesById[feature.featureId] = feature
            }
        }
    }
    return wayFeaturesById
}

internal fun routePoiKind(tags: Map<String, String>): RoutePoiKind? {
    return when {
        tags["amenity"] == "drinking_water" -> RoutePoiKind.DrinkingWater
        tags["amenity"] == "toilets" -> RoutePoiKind.Toilets
        tags["amenity"] == "shelter" -> RoutePoiKind.Shelter
        tags["tourism"] == "picnic_site" -> RoutePoiKind.PicnicSite
        tags["amenity"] == "bicycle_repair_station" -> RoutePoiKind.BicycleRepairStation
        tags["shop"] == "bicycle" -> RoutePoiKind.BicycleShop
        else -> null
    }
}

internal fun poiOffsetLimitMeters(
    kind: RoutePoiKind,
    config: TileContextConfig,
): Double {
    return when (kind) {
        RoutePoiKind.BicycleRepairStation,
        RoutePoiKind.BicycleShop,
        -> config.serviceHaloMeters

        RoutePoiKind.DrinkingWater,
        RoutePoiKind.Toilets,
        RoutePoiKind.Shelter,
        RoutePoiKind.PicnicSite,
        -> config.poiHaloMeters
    }
}

private const val MIN_NEARBY_WAY_SNIPPET_LENGTH_METERS = 10.0
private const val MAX_ON_ROUTE_WAY_OFFSET_METERS = 10.0
private const val MIN_NEARBY_WAY_BRANCH_ANGLE_DEGREES = 18.0

private data class NearbyWaySample(
    val point: ProjectedPoint,
    val offRouteMeters: Double,
    val nearestEdgeIndex: Int,
    val routeTangentX: Double,
    val routeTangentY: Double,
)

internal fun extractNearbyWaySnippets(
    routeModel: RouteModel,
    feature: TileContextFeature,
    haloMeters: Double,
    continuationMeters: Double,
    focusBounds: Bounds? = null,
    initialNearestEdgeIndexes: List<Int> = emptyList(),
    restrictToHintWindow: Boolean = false,
): List<RouteNearbyWaySnippet> {
    if (feature.geometry.size < 2) {
        return emptyList()
    }
    return extractNearbyWaySnippetsFromProjectedPoints(
        routeModel = routeModel,
        featureId = feature.featureId,
        projectedPoints = feature.geometry.map { point ->
            projectGeoPointToRouteProjection(point, routeModel.projection)
        },
        haloMeters = haloMeters,
        continuationMeters = continuationMeters,
        focusBounds = focusBounds,
        initialNearestEdgeIndexes = initialNearestEdgeIndexes,
        restrictToHintWindow = restrictToHintWindow,
    )
}

internal fun extractNearbyWaySnippetsFromProjectedPoints(
    routeModel: RouteModel,
    featureId: String,
    projectedPoints: List<ProjectedPoint>,
    haloMeters: Double,
    continuationMeters: Double,
    focusBounds: Bounds? = null,
    initialNearestEdgeIndexes: List<Int> = emptyList(),
    restrictToHintWindow: Boolean = false,
): List<RouteNearbyWaySnippet> {
    if (projectedPoints.size < 2) {
        return emptyList()
    }
    if (focusBounds != null) {
        val projectedBounds = nearbyWayBounds(projectedPoints)
        if (!boundsIntersect(projectedBounds, expandBounds(focusBounds, haloMeters))) {
            return emptyList()
        }
    }
    val samples = buildNearbyWaySamples(
        routeModel = routeModel,
        projectedPoints = projectedPoints,
        initialNearestEdgeIndexes = initialNearestEdgeIndexes,
        maxHintDistanceMeters = haloMeters + continuationMeters,
        restrictToHintWindow = restrictToHintWindow,
    )
    val snippets = mutableListOf<RouteNearbyWaySnippet>()
    var currentPoints = mutableListOf<ProjectedPoint>()
    var currentMaxOffset = 0.0
    var currentMaxAngleDegrees = 0.0

    for (index in 0 until samples.lastIndex) {
        val start = samples[index]
        val end = samples[index + 1]
        val midpoint = ProjectedPoint(
            x = (start.point.x + end.point.x) / 2.0,
            y = (start.point.y + end.point.y) / 2.0,
        )
        val midpointSample = buildNearbyWaySample(
            routeModel = routeModel,
            point = midpoint,
            hintEdgeIndexes = (initialNearestEdgeIndexes + listOfNotNull(start.nearestEdgeIndex.takeIf { it >= 0 })).distinct(),
            maxHintDistanceMeters = haloMeters + continuationMeters,
            restrictToHintWindow = restrictToHintWindow,
        )
        val segmentThresholdMeters = if (currentPoints.isEmpty()) {
            haloMeters
        } else {
            haloMeters + continuationMeters
        }
        val segmentNearRoute = minOf(
            start.offRouteMeters,
            end.offRouteMeters,
            midpointSample.offRouteMeters,
        ) <= segmentThresholdMeters

        if (segmentNearRoute) {
            if (currentPoints.isEmpty()) {
                currentPoints += start.point
            }
            currentPoints += end.point
            currentMaxOffset = max(
                currentMaxOffset,
                max(start.offRouteMeters, max(end.offRouteMeters, midpointSample.offRouteMeters)),
            )
            currentMaxAngleDegrees = max(
                currentMaxAngleDegrees,
                max(
                    segmentRouteAngleDegrees(start.point, end.point, start),
                    segmentRouteAngleDegrees(start.point, end.point, midpointSample),
                ),
            )
        } else {
            flushNearbyWaySnippet(
                snippets = snippets,
                featureId = featureId,
                points = currentPoints,
                maxOffsetMeters = currentMaxOffset,
                maxAngleDegrees = currentMaxAngleDegrees,
            )
            currentPoints = mutableListOf()
            currentMaxOffset = 0.0
            currentMaxAngleDegrees = 0.0
        }
    }

    flushNearbyWaySnippet(
        snippets = snippets,
        featureId = featureId,
        points = currentPoints,
        maxOffsetMeters = currentMaxOffset,
        maxAngleDegrees = currentMaxAngleDegrees,
    )

    return snippets
}

private fun buildNearbyWaySamples(
    routeModel: RouteModel,
    projectedPoints: List<ProjectedPoint>,
    initialNearestEdgeIndexes: List<Int>,
    maxHintDistanceMeters: Double,
    restrictToHintWindow: Boolean,
): List<NearbyWaySample> {
    if (projectedPoints.isEmpty()) {
        return emptyList()
    }
    val samples = ArrayList<NearbyWaySample>(projectedPoints.size)
    val baseHintEdgeIndexes = initialNearestEdgeIndexes.filter { it in 0..routeModel.edges.lastIndex }.distinct()
    var currentHintEdgeIndexes = baseHintEdgeIndexes
    projectedPoints.forEach { point ->
        val sample = buildNearbyWaySample(
            routeModel = routeModel,
            point = point,
            hintEdgeIndexes = currentHintEdgeIndexes,
            maxHintDistanceMeters = maxHintDistanceMeters,
            restrictToHintWindow = restrictToHintWindow,
        )
        samples += sample
        currentHintEdgeIndexes = sample.nearestEdgeIndex
            .takeIf { it >= 0 }
            ?.let { (baseHintEdgeIndexes + it).distinct() }
            ?: baseHintEdgeIndexes
    }
    return samples
}

private fun buildNearbyWaySample(
    routeModel: RouteModel,
    point: ProjectedPoint,
    hintEdgeIndexes: List<Int> = emptyList(),
    maxHintDistanceMeters: Double,
    restrictToHintWindow: Boolean,
): NearbyWaySample {
    val analysis = if (restrictToHintWindow && hintEdgeIndexes.isNotEmpty()) {
        analyzeProjectedPointWithinHintWindow(
            model = routeModel,
            projectedFix = point,
            hintEdgeIndexes = hintEdgeIndexes,
        ) ?: emptyRouteAnalysis(point)
    } else {
        analyzeProjectedPointNearRouteHint(
            model = routeModel,
            projectedFix = point,
            hintEdgeIndexes = hintEdgeIndexes,
            maxHintDistanceMeters = maxHintDistanceMeters,
        )
    }
    return NearbyWaySample(
        point = point,
        offRouteMeters = analysis.offRouteMeters,
        nearestEdgeIndex = analysis.nearestEdgeIndex,
        routeTangentX = analysis.routeTangentX,
        routeTangentY = analysis.routeTangentY,
    )
}

private fun flushNearbyWaySnippet(
    snippets: MutableList<RouteNearbyWaySnippet>,
    featureId: String,
    points: List<ProjectedPoint>,
    maxOffsetMeters: Double,
    maxAngleDegrees: Double,
) {
    if (points.size < 2) {
        return
    }
    val dedupedPoints = dedupeConsecutiveProjectedPoints(points)
    if (dedupedPoints.size < 2) {
        return
    }
    val lengthMeters = dedupedPoints
        .zipWithNext()
        .sumOf { (start, end) -> nearbyWayDistanceMeters(start, end) }
    if (lengthMeters < MIN_NEARBY_WAY_SNIPPET_LENGTH_METERS) {
        return
    }
    if (maxOffsetMeters <= MAX_ON_ROUTE_WAY_OFFSET_METERS) {
        return
    }
    if (maxAngleDegrees < MIN_NEARBY_WAY_BRANCH_ANGLE_DEGREES) {
        return
    }
    snippets += RouteNearbyWaySnippet(
        featureId = featureId,
        points = dedupedPoints,
        bounds = nearbyWayBounds(dedupedPoints),
    )
}

private fun dedupeConsecutiveProjectedPoints(points: List<ProjectedPoint>): List<ProjectedPoint> {
    return buildList {
        points.forEach { point ->
            val previous = lastOrNull()
            if (previous == null || previous.x != point.x || previous.y != point.y) {
                add(point)
            }
        }
    }
}

internal fun projectGeoPointToRouteProjection(
    point: GeoPoint,
    projection: Projection,
): ProjectedPoint {
    return ProjectedPoint(
        x = ((point.lon - projection.originLon) * kotlin.math.PI / 180.0) * 6_371_000.0 * projection.cosLat,
        y = ((point.lat - projection.originLat) * kotlin.math.PI / 180.0) * 6_371_000.0,
    )
}

private fun nearbyWayDistanceMeters(
    start: ProjectedPoint,
    end: ProjectedPoint,
): Double {
    return kotlin.math.hypot(end.x - start.x, end.y - start.y)
}

private fun segmentRouteAngleDegrees(
    start: ProjectedPoint,
    end: ProjectedPoint,
    sample: NearbyWaySample,
): Double {
    val segmentLength = nearbyWayDistanceMeters(start, end)
    if (segmentLength <= 0.001) {
        return 0.0
    }
    val segmentX = (end.x - start.x) / segmentLength
    val segmentY = (end.y - start.y) / segmentLength
    val dot = (segmentX * sample.routeTangentX + segmentY * sample.routeTangentY).coerceIn(-1.0, 1.0)
    val angle = Math.toDegrees(kotlin.math.acos(dot))
    return minOf(angle, 180.0 - angle)
}

private fun nearbyWayBounds(points: List<ProjectedPoint>): Bounds {
    return Bounds(
        minX = points.minOf(ProjectedPoint::x),
        maxX = points.maxOf(ProjectedPoint::x),
        minY = points.minOf(ProjectedPoint::y),
        maxY = points.maxOf(ProjectedPoint::y),
    )
}

internal fun nearbyWayFocusBounds(
    routeModel: RouteModel,
    focusGeoPoint: GeoPoint?,
    focusWindowWidthMeters: Double?,
    haloMeters: Double,
    continuationMeters: Double,
): Bounds? {
    if (focusGeoPoint == null || focusWindowWidthMeters == null) {
        return null
    }
    val center = projectGeoPointToRouteProjection(focusGeoPoint, routeModel.projection)
    val focusRadiusMeters = max(
        (haloMeters + continuationMeters) * 1.35,
        max(250.0, min(focusWindowWidthMeters * 0.35, 1_200.0)),
    )
    return Bounds(
        minX = center.x - focusRadiusMeters,
        maxX = center.x + focusRadiusMeters,
        minY = center.y - focusRadiusMeters,
        maxY = center.y + focusRadiusMeters,
    )
}

internal fun expandBounds(bounds: Bounds, paddingMeters: Double): Bounds {
    return Bounds(
        minX = bounds.minX - paddingMeters,
        maxX = bounds.maxX + paddingMeters,
        minY = bounds.minY - paddingMeters,
        maxY = bounds.maxY + paddingMeters,
    )
}

private fun boundsIntersect(left: Bounds, right: Bounds): Boolean {
    return left.minX <= right.maxX &&
        left.maxX >= right.minX &&
        left.minY <= right.maxY &&
        left.maxY >= right.minY
}
