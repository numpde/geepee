package dev.ra.geepee
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.max
import kotlin.math.min

private const val ROUTE_TILE_OVERLAY_MAGIC = 0x4750544F
private const val ROUTE_TILE_OVERLAY_SCHEMA_VERSION = 1

internal data class RouteTileOverlay(
    val schemaVersion: Int = ROUTE_TILE_OVERLAY_SCHEMA_VERSION,
    val routeFingerprint: String,
    val tileId: DownloadTileId,
    val sourceRuntimeSchemaVersion: Int,
    val sourceFetchedAtMillis: Long,
    val context: RouteContext,
    val leafEntries: List<RouteTileOverlayLeafEntry>,
)

internal data class RouteTileOverlayLeafEntry(
    val leafNodeIndex: Int,
    val poiIndexes: List<Int>,
    val nearbyWayIndexes: List<Int>,
)

internal data class RouteTileOverlayBundle(
    val runtimePack: TileRuntimePack,
    val overlay: RouteTileOverlay,
)

private data class OverlayRoutePoiCandidate(
    val poi: RoutePoi,
    val routeMeters: Double,
    val offsetMeters: Double,
)

internal fun buildRouteTileOverlay(
    routeModel: RouteModel,
    runtimePack: TileRuntimePack,
    config: TileContextConfig,
    focusGeoPoint: GeoPoint? = null,
    focusWindowWidthMeters: Double? = null,
): RouteTileOverlay {
    val projectedTileBounds = runtimeGeoBoundsToProjectedBounds(runtimePack.runtimeBounds, routeModel.projection)
    val overlayFocusBounds = if (focusGeoPoint != null && focusWindowWidthMeters != null) {
        nearbyWayFocusBounds(
            routeModel = routeModel,
            focusGeoPoint = focusGeoPoint,
            focusWindowWidthMeters = focusWindowWidthMeters,
            haloMeters = config.wayHaloMeters,
            continuationMeters = config.nearbyWayContinuationMeters,
        ) ?: projectedTileBounds
    } else {
        projectedTileBounds
    }
    val routeRelevantLeafIndexes = routeRelevantLeafIndexes(
        routeModel = routeModel,
        runtimePack = runtimePack,
        focusBounds = overlayFocusBounds,
        config = config,
    )
    val context = RouteContext(
        pois = buildRoutePoisFromRuntimePack(
            routeModel = routeModel,
            runtimePack = runtimePack,
            routeRelevantLeafIndexes = routeRelevantLeafIndexes,
            config = config,
        ),
        nearbyWays = buildRouteNearbyWaysFromRuntimePack(
            routeModel = routeModel,
            runtimePack = runtimePack,
            routeRelevantLeafIndexes = routeRelevantLeafIndexes,
            focusBounds = overlayFocusBounds,
            config = config,
        ),
    )
    val pointLeafIndexesByFeatureId = buildPointLeafIndexesByFeatureId(runtimePack)
    val wayLeafIndexesByFeatureId = buildWayLeafIndexesByFeatureId(runtimePack)
    val leafEntries = buildRouteTileOverlayLeafEntries(
        context = context,
        pointLeafIndexesByFeatureId = pointLeafIndexesByFeatureId,
        wayLeafIndexesByFeatureId = wayLeafIndexesByFeatureId,
    )
    return RouteTileOverlay(
        routeFingerprint = routeFingerprint(routeModel),
        tileId = runtimePack.tileId,
        sourceRuntimeSchemaVersion = runtimePack.schemaVersion,
        sourceFetchedAtMillis = runtimePack.fetchedAtMillis,
        context = context,
        leafEntries = leafEntries,
    )
}

internal fun routeTileOverlayToByteArray(overlay: RouteTileOverlay): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(ROUTE_TILE_OVERLAY_MAGIC)
        data.writeInt(overlay.schemaVersion)
        data.writeUTF(overlay.routeFingerprint)
        data.writeInt(overlay.tileId.zoom)
        data.writeInt(overlay.tileId.x)
        data.writeInt(overlay.tileId.y)
        data.writeInt(overlay.sourceRuntimeSchemaVersion)
        data.writeLong(overlay.sourceFetchedAtMillis)

        data.writeInt(overlay.context.pois.size)
        overlay.context.pois.forEach { poi ->
            data.writeUTF(poi.featureId)
            data.writeUTF(poi.kind.name)
            data.writeBoolean(poi.name != null)
            if (poi.name != null) {
                data.writeUTF(poi.name)
            }
            data.writeDouble(poi.geoPoint.lat)
            data.writeDouble(poi.geoPoint.lon)
            data.writeDouble(poi.projectedPoint.x)
            data.writeDouble(poi.projectedPoint.y)
        }

        data.writeInt(overlay.context.nearbyWays.size)
        overlay.context.nearbyWays.forEach { way ->
            data.writeUTF(way.featureId)
            data.writeInt(way.points.size)
            way.points.forEach { point ->
                data.writeDouble(point.x)
                data.writeDouble(point.y)
            }
            data.writeDouble(way.bounds.minX)
            data.writeDouble(way.bounds.maxX)
            data.writeDouble(way.bounds.minY)
            data.writeDouble(way.bounds.maxY)
        }

        data.writeInt(overlay.leafEntries.size)
        overlay.leafEntries.forEach { entry ->
            data.writeInt(entry.leafNodeIndex)
            writeRouteTileOverlayIntList(data, entry.poiIndexes)
            writeRouteTileOverlayIntList(data, entry.nearbyWayIndexes)
        }
    }
    return output.toByteArray()
}

internal fun routeTileOverlayFromByteArray(bytes: ByteArray): RouteTileOverlay {
    DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        val magic = data.readInt()
        require(magic == ROUTE_TILE_OVERLAY_MAGIC) {
            "Unexpected route tile overlay magic: $magic"
        }
        val schemaVersion = data.readInt()
        require(schemaVersion == ROUTE_TILE_OVERLAY_SCHEMA_VERSION) {
            "Unsupported route tile overlay schema version: $schemaVersion"
        }
        val routeFingerprint = data.readUTF()
        val tileId = DownloadTileId(
            zoom = data.readInt(),
            x = data.readInt(),
            y = data.readInt(),
        )
        val sourceRuntimeSchemaVersion = data.readInt()
        val sourceFetchedAtMillis = data.readLong()

        val pois = buildList {
            repeat(data.readInt()) {
                val featureId = data.readUTF()
                val kind = enumValueOf<RoutePoiKind>(data.readUTF())
                val name = if (data.readBoolean()) data.readUTF() else null
                add(
                    RoutePoi(
                        featureId = featureId,
                        kind = kind,
                        name = name,
                        geoPoint = GeoPoint(
                            lat = data.readDouble(),
                            lon = data.readDouble(),
                        ),
                        projectedPoint = ProjectedPoint(
                            x = data.readDouble(),
                            y = data.readDouble(),
                        ),
                    ),
                )
            }
        }

        val nearbyWays = buildList {
            repeat(data.readInt()) {
                val featureId = data.readUTF()
                val points = buildList {
                    repeat(data.readInt()) {
                        add(
                            ProjectedPoint(
                                x = data.readDouble(),
                                y = data.readDouble(),
                            ),
                        )
                    }
                }
                add(
                    RouteNearbyWaySnippet(
                        featureId = featureId,
                        points = points,
                        bounds = Bounds(
                            minX = data.readDouble(),
                            maxX = data.readDouble(),
                            minY = data.readDouble(),
                            maxY = data.readDouble(),
                        ),
                    ),
                )
            }
        }

        val leafEntries = buildList {
            repeat(data.readInt()) {
                add(
                    RouteTileOverlayLeafEntry(
                        leafNodeIndex = data.readInt(),
                        poiIndexes = readRouteTileOverlayIntList(data),
                        nearbyWayIndexes = readRouteTileOverlayIntList(data),
                    ),
                )
            }
        }

        return RouteTileOverlay(
            schemaVersion = schemaVersion,
            routeFingerprint = routeFingerprint,
            tileId = tileId,
            sourceRuntimeSchemaVersion = sourceRuntimeSchemaVersion,
            sourceFetchedAtMillis = sourceFetchedAtMillis,
            context = RouteContext(
                pois = pois,
                nearbyWays = nearbyWays,
            ),
            leafEntries = leafEntries,
        )
    }
}

private fun buildRoutePoisFromRuntimePack(
    routeModel: RouteModel,
    runtimePack: TileRuntimePack,
    routeRelevantLeafIndexes: Set<Int>,
    config: TileContextConfig,
): List<RoutePoi> {
    if (runtimePack.pointFeatures.isEmpty() || routeRelevantLeafIndexes.isEmpty()) {
        return emptyList()
    }
    val candidatePointIds = runtimePack.quadtreeNodes
        .asSequence()
        .filter { node -> node.isLeaf && node.nodeIndex in routeRelevantLeafIndexes }
        .flatMap { node -> node.pointIds.asSequence() }
        .distinct()
        .toSet()
    if (candidatePointIds.isEmpty()) {
        return emptyList()
    }
    val pointsById = runtimePack.pointFeatures.associateBy(TileRuntimePointFeature::pointId)
    val poisByFeatureId = linkedMapOf<String, OverlayRoutePoiCandidate>()
    candidatePointIds.forEach { pointId ->
        val feature = pointsById[pointId] ?: return@forEach
        val kind = routePoiKind(feature.tags) ?: return@forEach
        val geoPoint = tileRuntimeLocalPointToGeoPoint(runtimePack, feature.point)
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = geoPoint.lat,
                lon = geoPoint.lon,
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
        val poiCandidate = OverlayRoutePoiCandidate(
            poi = RoutePoi(
                featureId = feature.featureId,
                kind = kind,
                name = feature.tags["name"],
                geoPoint = geoPoint,
                projectedPoint = projectGeoPointToRouteProjection(geoPoint, routeModel.projection),
            ),
            routeMeters = analysis.routeMeters,
            offsetMeters = analysis.offRouteMeters,
        )
        val previous = poisByFeatureId[feature.featureId]
        if (previous == null || poiCandidate.offsetMeters < previous.offsetMeters) {
            poisByFeatureId[feature.featureId] = poiCandidate
        }
    }
    return poisByFeatureId.values
        .sortedBy(OverlayRoutePoiCandidate::routeMeters)
        .map(OverlayRoutePoiCandidate::poi)
}

private fun buildRouteNearbyWaysFromRuntimePack(
    routeModel: RouteModel,
    runtimePack: TileRuntimePack,
    routeRelevantLeafIndexes: Set<Int>,
    focusBounds: Bounds,
    config: TileContextConfig,
): List<RouteNearbyWaySnippet> {
    if (runtimePack.waySegments.isEmpty() || routeRelevantLeafIndexes.isEmpty()) {
        return emptyList()
    }
    val candidateSegmentIds = runtimePack.quadtreeNodes
        .asSequence()
        .filter { node -> node.isLeaf && node.nodeIndex in routeRelevantLeafIndexes }
        .flatMap { node -> node.segmentIds.asSequence() }
        .distinct()
        .toSet()
    if (candidateSegmentIds.isEmpty()) {
        return emptyList()
    }
    val segmentsById = runtimePack.waySegments.associateBy(TileRuntimeWaySegment::segmentId)
    val snippets = candidateSegmentIds.flatMap { segmentId ->
        val segment = segmentsById[segmentId] ?: return@flatMap emptyList<RouteNearbyWaySnippet>()
        val segmentProjectedBounds = tileRuntimeLocalBoundsToProjectedBounds(
            runtimePack = runtimePack,
            bounds = segment.bounds,
            projection = routeModel.projection,
        )
        val hintEdgeIndexes = routeEdgeIndexesIntersectingBounds(
            model = routeModel,
            bounds = expandBounds(
                segmentProjectedBounds,
                config.wayHaloMeters + config.nearbyWayContinuationMeters,
            ),
        )
        extractNearbyWaySnippetsFromProjectedPoints(
            routeModel = routeModel,
            featureId = segment.sourceFeatureId,
            projectedPoints = segment.points.map { point ->
                tileRuntimeLocalPointToProjectedPoint(runtimePack, point, routeModel.projection)
            },
            haloMeters = config.wayHaloMeters,
            continuationMeters = config.nearbyWayContinuationMeters,
            focusBounds = focusBounds,
            initialNearestEdgeIndexes = hintEdgeIndexes,
            restrictToHintWindow = hintEdgeIndexes.isNotEmpty(),
        )
    }
    return dedupeNearbyWaysByFeatureId(snippets)
}

private fun routeRelevantLeafIndexes(
    routeModel: RouteModel,
    runtimePack: TileRuntimePack,
    focusBounds: Bounds,
    config: TileContextConfig,
): Set<Int> {
    val routePaddingMeters = config.wayHaloMeters + config.nearbyWayContinuationMeters
    return runtimePack.quadtreeNodes
        .asSequence()
        .filter(TileRuntimeQuadtreeNode::isLeaf)
        .filter { leaf ->
            val leafProjectedBounds = tileRuntimeLocalBoundsToProjectedBounds(
                runtimePack = runtimePack,
                bounds = leaf.bounds,
                projection = routeModel.projection,
            )
            boundsIntersect(leafProjectedBounds, focusBounds) &&
                routeEdgeIndexesIntersectingBounds(
                    model = routeModel,
                    bounds = expandBounds(leafProjectedBounds, routePaddingMeters),
                ).isNotEmpty()
        }
        .map(TileRuntimeQuadtreeNode::nodeIndex)
        .toSet()
}

private fun buildPointLeafIndexesByFeatureId(
    runtimePack: TileRuntimePack,
): Map<String, List<Int>> {
    val pointIdsByFeatureId = runtimePack.pointFeatures.associate { it.featureId to it.pointId }
    val leafIndexesByPointId = mutableMapOf<Int, MutableList<Int>>()
    runtimePack.quadtreeNodes
        .filter(TileRuntimeQuadtreeNode::isLeaf)
        .forEach { leaf ->
            leaf.pointIds.forEach { pointId ->
                leafIndexesByPointId.getOrPut(pointId) { mutableListOf() } += leaf.nodeIndex
            }
        }
    return pointIdsByFeatureId.mapValues { (_, pointId) ->
        leafIndexesByPointId[pointId].orEmpty().sorted()
    }
}

private fun buildWayLeafIndexesByFeatureId(
    runtimePack: TileRuntimePack,
): Map<String, List<Int>> {
    val featureIdBySegmentId = runtimePack.waySegments.associate { it.segmentId to it.sourceFeatureId }
    val leafIndexesByFeatureId = mutableMapOf<String, MutableSet<Int>>()
    runtimePack.quadtreeNodes
        .filter(TileRuntimeQuadtreeNode::isLeaf)
        .forEach { leaf ->
            leaf.segmentIds.forEach { segmentId ->
                val featureId = featureIdBySegmentId.getValue(segmentId)
                leafIndexesByFeatureId.getOrPut(featureId) { linkedSetOf() } += leaf.nodeIndex
            }
        }
    return leafIndexesByFeatureId.mapValues { (_, leafIndexes) ->
        leafIndexes.toList().sorted()
    }
}

private fun buildRouteTileOverlayLeafEntries(
    context: RouteContext,
    pointLeafIndexesByFeatureId: Map<String, List<Int>>,
    wayLeafIndexesByFeatureId: Map<String, List<Int>>,
): List<RouteTileOverlayLeafEntry> {
    val poiIndexesByLeaf = mutableMapOf<Int, MutableList<Int>>()
    context.pois.forEachIndexed { poiIndex, poi ->
        pointLeafIndexesByFeatureId[poi.featureId].orEmpty().forEach { leafIndex ->
            poiIndexesByLeaf.getOrPut(leafIndex) { mutableListOf() } += poiIndex
        }
    }

    val nearbyWayIndexesByLeaf = mutableMapOf<Int, MutableList<Int>>()
    context.nearbyWays.forEachIndexed { nearbyWayIndex, nearbyWay ->
        wayLeafIndexesByFeatureId[nearbyWay.featureId].orEmpty().forEach { leafIndex ->
            nearbyWayIndexesByLeaf.getOrPut(leafIndex) { mutableListOf() } += nearbyWayIndex
        }
    }

    return (poiIndexesByLeaf.keys + nearbyWayIndexesByLeaf.keys)
        .toSortedSet()
        .map { leafIndex ->
            RouteTileOverlayLeafEntry(
                leafNodeIndex = leafIndex,
                poiIndexes = poiIndexesByLeaf[leafIndex].orEmpty().sorted(),
                nearbyWayIndexes = nearbyWayIndexesByLeaf[leafIndex].orEmpty().sorted(),
            )
        }
}

internal fun mergeRouteTileOverlayPois(
    routeModel: RouteModel,
    overlays: List<RouteTileOverlay>,
): List<RoutePoi> {
    return overlays
        .flatMap { it.context.pois }
        .distinctBy(RoutePoi::featureId)
        .sortedBy { poi ->
            analyzeLocationAgainstModel(
                model = routeModel,
                fix = LocationFix(
                    lat = poi.geoPoint.lat,
                    lon = poi.geoPoint.lon,
                    accuracyMeters = null,
                    headingDegrees = null,
                    speedMetersPerSecond = null,
                    timestampMillis = 0L,
                ),
            ).routeMeters
        }
}

internal fun queryRouteTileOverlayNearbyWays(
    routeModel: RouteModel,
    bundle: RouteTileOverlayBundle,
    focusGeoPoint: GeoPoint,
    focusWindowWidthMeters: Double,
    focusBoundsOverride: Bounds? = null,
    config: TileContextConfig,
): List<RouteNearbyWaySnippet> {
    if (bundle.overlay.context.nearbyWays.isEmpty()) {
        return emptyList()
    }
    val localFocusBounds = localFocusBounds(
        runtimePack = bundle.runtimePack,
        focusGeoPoint = focusGeoPoint,
        focusWindowWidthMeters = focusWindowWidthMeters,
        focusProjectedBounds = focusBoundsOverride,
        projection = routeModel.projection,
        config = config,
    )
    val candidateLeafIndexes = bundle.runtimePack.quadtreeNodes
        .filter { node ->
            node.isLeaf && tileRuntimeBoundsIntersect(node.bounds, localFocusBounds)
        }
        .map(TileRuntimeQuadtreeNode::nodeIndex)
        .toSet()

    val candidateNearbyWayIndexes = bundle.overlay.leafEntries
        .asSequence()
        .filter { entry -> entry.leafNodeIndex in candidateLeafIndexes }
        .flatMap { entry -> entry.nearbyWayIndexes.asSequence() }
        .distinct()
        .toList()
    if (candidateNearbyWayIndexes.isEmpty()) {
        return emptyList()
    }

    val projectedFocusBounds = focusBoundsOverride ?: overlayNearbyWayFocusBounds(
        routeModel = routeModel,
        focusGeoPoint = focusGeoPoint,
        focusWindowWidthMeters = focusWindowWidthMeters,
        haloMeters = config.wayHaloMeters,
        continuationMeters = config.nearbyWayContinuationMeters,
    )
    return dedupeNearbyWaysByFeatureId(
        candidateNearbyWayIndexes
        .map { index -> bundle.overlay.context.nearbyWays[index] }
        .filter { nearbyWay ->
            boundsIntersect(nearbyWay.bounds, expandBounds(projectedFocusBounds, config.wayHaloMeters))
        }
    )
}

internal fun queryTileRuntimeNearbyWays(
    routeModel: RouteModel,
    runtimePack: TileRuntimePack,
    focusGeoPoint: GeoPoint,
    focusHintEdgeIndexes: List<Int>,
    focusWindowWidthMeters: Double,
    focusBoundsOverride: Bounds? = null,
    config: TileContextConfig,
): List<RouteNearbyWaySnippet> {
    if (runtimePack.waySegments.isEmpty()) {
        return emptyList()
    }
    val localFocusBounds = localFocusBounds(
        runtimePack = runtimePack,
        focusGeoPoint = focusGeoPoint,
        focusWindowWidthMeters = focusWindowWidthMeters,
        focusProjectedBounds = focusBoundsOverride,
        projection = routeModel.projection,
        config = config,
    )
    val seedLeafIndexes = runtimePack.quadtreeNodes
        .asSequence()
        .filter { node ->
            node.isLeaf && tileRuntimeBoundsIntersect(node.bounds, localFocusBounds)
        }
        .map(TileRuntimeQuadtreeNode::nodeIndex)
        .toList()
    if (seedLeafIndexes.isEmpty()) {
        return emptyList()
    }
    val candidateSegmentIds = seedLeafIndexes
        .asSequence()
        .flatMap { leafIndex -> runtimePack.quadtreeNodes[leafIndex].segmentIds.asSequence() }
        .distinct()
        .toList()
    if (candidateSegmentIds.isEmpty()) {
        return emptyList()
    }

    val projectedFocusBounds = focusBoundsOverride ?: overlayNearbyWayFocusBounds(
        routeModel = routeModel,
        focusGeoPoint = focusGeoPoint,
        focusWindowWidthMeters = focusWindowWidthMeters,
        haloMeters = config.wayHaloMeters,
        continuationMeters = config.nearbyWayContinuationMeters,
    )
    val localExpandedFocusBounds = expandTileRuntimeLocalBounds(localFocusBounds, runtimePack.coordinateScale, config.wayHaloMeters)
    val segmentsById = runtimePack.waySegments.associateBy(TileRuntimeWaySegment::segmentId)
    val candidateSegments = candidateSegmentIds
        .mapNotNull(segmentsById::get)
        .filter { segment ->
            tileRuntimeBoundsIntersect(segment.bounds, localExpandedFocusBounds)
        }
    val nearbyWays = dedupeNearbyWaysByFeatureId(
        candidateSegments.flatMap { segment ->
            extractNearbyWaySnippetsFromProjectedPoints(
                routeModel = routeModel,
                featureId = segment.sourceFeatureId,
                projectedPoints = segment.points.map { point ->
                    tileRuntimeLocalPointToProjectedPoint(runtimePack, point, routeModel.projection)
                },
                haloMeters = config.wayHaloMeters,
                continuationMeters = config.nearbyWayContinuationMeters,
                focusBounds = projectedFocusBounds,
                initialNearestEdgeIndexes = focusHintEdgeIndexes,
                restrictToHintWindow = focusHintEdgeIndexes.isNotEmpty(),
            )
        }
    )
    return nearbyWays
}

internal fun dedupeNearbyWaysByFeatureId(snippets: List<RouteNearbyWaySnippet>): List<RouteNearbyWaySnippet> {
    return snippets
        .groupBy(RouteNearbyWaySnippet::featureId)
        .values
        .map { featureSnippets ->
            featureSnippets.maxBy { snippet ->
                snippet.points.zipWithNext().sumOf { (start, end) ->
                    kotlin.math.hypot(end.x - start.x, end.y - start.y)
                }
            }
        }
        .sortedBy(RouteNearbyWaySnippet::featureId)
}

internal fun routeFingerprint(routeModel: RouteModel): String {
    var hash = -3750763034362895579L
    routeModel.segments.forEach { segment ->
        segment.geoPoints.forEach { point ->
            hash = fnv1a64(hash, java.lang.Double.doubleToLongBits(point.lat))
            hash = fnv1a64(hash, java.lang.Double.doubleToLongBits(point.lon))
        }
    }
    hash = fnv1a64(hash, routeModel.pointCount.toLong())
    hash = fnv1a64(hash, java.lang.Double.doubleToLongBits(routeModel.totalLengthMeters))
    return java.lang.Long.toUnsignedString(hash, 16)
}

private fun fnv1a64(
    currentHash: Long,
    value: Long,
): Long {
    var hash = currentHash
    var remaining = value
    repeat(Long.SIZE_BYTES) {
        hash = hash xor (remaining and 0xffL)
        hash *= 0x100000001b3L
        remaining = remaining ushr 8
    }
    return hash
}

private fun writeRouteTileOverlayIntList(
    data: DataOutputStream,
    values: List<Int>,
) {
    data.writeInt(values.size)
    values.forEach(data::writeInt)
}

private fun readRouteTileOverlayIntList(data: DataInputStream): List<Int> {
    return buildList {
        repeat(data.readInt()) {
            add(data.readInt())
        }
    }
}

private fun localFocusBounds(
    runtimePack: TileRuntimePack,
    focusGeoPoint: GeoPoint,
    focusWindowWidthMeters: Double,
    focusProjectedBounds: Bounds? = null,
    projection: Projection,
    config: TileContextConfig,
): TileRuntimeLocalBounds {
    focusProjectedBounds?.let { projectedBounds ->
        return tileRuntimeProjectedBoundsToLocalBounds(runtimePack, projectedBounds, projection)
    }
    val focusPoint = tileRuntimeGeoPointToLocalPoint(runtimePack, focusGeoPoint)
    val focusRadiusMeters = max(
        (config.wayHaloMeters + config.nearbyWayContinuationMeters) * 1.35,
        max(250.0, min(focusWindowWidthMeters * 0.35, 1_200.0)),
    )
    val radiusUnits = kotlin.math.ceil(focusRadiusMeters * runtimePack.coordinateScale).toInt()
    return TileRuntimeLocalBounds(
        minX = focusPoint.x - radiusUnits,
        minY = focusPoint.y - radiusUnits,
        maxX = focusPoint.x + radiusUnits,
        maxY = focusPoint.y + radiusUnits,
    )
}

private fun runtimeGeoBoundsToProjectedBounds(
    bounds: GeoBounds,
    projection: Projection,
): Bounds {
    return projectedBoundsForGeoBounds(bounds, projection)
}

private fun tileRuntimeLocalBoundsToProjectedBounds(
    runtimePack: TileRuntimePack,
    bounds: TileRuntimeLocalBounds,
    projection: Projection,
): Bounds {
    val corners = listOf(
        TileRuntimeLocalPoint(bounds.minX, bounds.minY),
        TileRuntimeLocalPoint(bounds.minX, bounds.maxY),
        TileRuntimeLocalPoint(bounds.maxX, bounds.minY),
        TileRuntimeLocalPoint(bounds.maxX, bounds.maxY),
    ).map { corner ->
        tileRuntimeLocalPointToProjectedPoint(runtimePack, corner, projection)
    }
    return Bounds(
        minX = corners.minOf(ProjectedPoint::x),
        maxX = corners.maxOf(ProjectedPoint::x),
        minY = corners.minOf(ProjectedPoint::y),
        maxY = corners.maxOf(ProjectedPoint::y),
    )
}

private fun tileRuntimeProjectedBoundsToLocalBounds(
    runtimePack: TileRuntimePack,
    bounds: Bounds,
    projection: Projection,
): TileRuntimeLocalBounds {
    val corners = listOf(
        ProjectedPoint(bounds.minX, bounds.minY),
        ProjectedPoint(bounds.minX, bounds.maxY),
        ProjectedPoint(bounds.maxX, bounds.minY),
        ProjectedPoint(bounds.maxX, bounds.maxY),
    ).map { corner ->
        tileRuntimeGeoPointToLocalPoint(
            runtimePack,
            unprojectPoint(corner, projection),
        )
    }
    return TileRuntimeLocalBounds(
        minX = corners.minOf { it.x },
        minY = corners.minOf { it.y },
        maxX = corners.maxOf { it.x },
        maxY = corners.maxOf { it.y },
    )
}

private fun tileRuntimeLocalPointToProjectedPoint(
    runtimePack: TileRuntimePack,
    point: TileRuntimeLocalPoint,
    projection: Projection,
): ProjectedPoint {
    return projectGeoPointToRouteProjection(
        point = tileRuntimeLocalPointToGeoPoint(runtimePack, point),
        projection = projection,
    )
}

private fun expandTileRuntimeLocalBounds(
    bounds: TileRuntimeLocalBounds,
    coordinateScale: Int,
    paddingMeters: Double,
): TileRuntimeLocalBounds {
    val paddingUnits = kotlin.math.ceil(paddingMeters * coordinateScale.toDouble()).toInt()
    return TileRuntimeLocalBounds(
        minX = bounds.minX - paddingUnits,
        minY = bounds.minY - paddingUnits,
        maxX = bounds.maxX + paddingUnits,
        maxY = bounds.maxY + paddingUnits,
    )
}

private fun overlayNearbyWayFocusBounds(
    routeModel: RouteModel,
    focusGeoPoint: GeoPoint,
    focusWindowWidthMeters: Double,
    haloMeters: Double,
    continuationMeters: Double,
): Bounds {
    val center = ProjectedPoint(
        x = ((focusGeoPoint.lon - routeModel.projection.originLon) * kotlin.math.PI / 180.0) *
            6_371_000.0 * routeModel.projection.cosLat,
        y = ((focusGeoPoint.lat - routeModel.projection.originLat) * kotlin.math.PI / 180.0) *
            6_371_000.0,
    )
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

private fun boundsIntersect(left: Bounds, right: Bounds): Boolean {
    return left.minX <= right.maxX &&
        left.maxX >= right.minX &&
        left.minY <= right.maxY &&
        left.maxY >= right.minY
}
