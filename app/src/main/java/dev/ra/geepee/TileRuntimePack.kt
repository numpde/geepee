package dev.ra.geepee

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

private const val TILE_RUNTIME_PACK_MAGIC = 0x47505450
private const val TILE_RUNTIME_PACK_SCHEMA_VERSION = 1
private const val TILE_RUNTIME_COORDINATE_SCALE = 100
private const val TILE_RUNTIME_EARTH_RADIUS_METERS = 6_371_000.0
private const val TILE_RUNTIME_NODE_KEY_SCALE = 10_000_000.0
private const val TILE_RUNTIME_MAX_ITEMS_PER_LEAF = 12
private const val TILE_RUNTIME_MAX_DEPTH = 6

internal data class TileRuntimePack(
    val schemaVersion: Int = TILE_RUNTIME_PACK_SCHEMA_VERSION,
    val sourcePackSchemaVersion: Int,
    val tileId: DownloadTileId,
    val tileBounds: GeoBounds,
    val queryBounds: GeoBounds,
    val runtimeBounds: GeoBounds,
    val fetchedAtMillis: Long,
    val coordinateScale: Int = TILE_RUNTIME_COORDINATE_SCALE,
    val runtimeWidthMeters: Double,
    val runtimeHeightMeters: Double,
    val pointFeatures: List<TileRuntimePointFeature>,
    val waySegments: List<TileRuntimeWaySegment>,
    val junctions: List<TileRuntimeJunction>,
    val quadtreeNodes: List<TileRuntimeQuadtreeNode>,
)

internal data class TileRuntimeLocalPoint(
    val x: Int,
    val y: Int,
)

internal data class TileRuntimeLocalBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
)

internal data class TileRuntimePointFeature(
    val pointId: Int,
    val featureId: String,
    val tags: Map<String, String>,
    val point: TileRuntimeLocalPoint,
)

internal data class TileRuntimeWaySegment(
    val segmentId: Int,
    val sourceFeatureId: String,
    val tags: Map<String, String>,
    val startJunctionId: Int?,
    val endJunctionId: Int?,
    val points: List<TileRuntimeLocalPoint>,
    val bounds: TileRuntimeLocalBounds,
    val lengthMeters: Double,
)

internal data class TileRuntimeJunction(
    val junctionId: Int,
    val point: TileRuntimeLocalPoint,
    val sourcePointCount: Int,
    val connectedSegmentIds: List<Int>,
)

internal data class TileRuntimeQuadtreeNode(
    val nodeIndex: Int,
    val bounds: TileRuntimeLocalBounds,
    val childIndexes: List<Int>,
    val segmentIds: List<Int>,
    val pointIds: List<Int>,
    val junctionIds: List<Int>,
    val neighborLeafIndexes: List<Int>,
) {
    val isLeaf: Boolean
        get() = childIndexes.isEmpty()
}

internal fun compileTileRuntimePack(sourcePack: TileContextPack): TileRuntimePack {
    val tileBounds = tileGeoBounds(sourcePack.tileId)
    val runtimeBounds = runtimeGeoBoundsFor(sourcePack)
    val projector = TileRuntimeProjector(runtimeBounds = runtimeBounds)

    val wayFeatures = sourcePack.features.filter { it.geometryKind == TileGeometryKind.Way }
    val pointFeatures = sourcePack.features.filter { it.geometryKind == TileGeometryKind.Point }

    val usagesByKey = linkedMapOf<TileRuntimeNodeKey, MutableList<String>>()
    val representativePointByKey = linkedMapOf<TileRuntimeNodeKey, GeoPoint>()
    wayFeatures.forEach { feature ->
        feature.geometry.forEachIndexed { pointIndex, point ->
            val key = point.toRuntimeNodeKey()
            representativePointByKey.putIfAbsent(key, point)
            usagesByKey.getOrPut(key) { mutableListOf() }
                .add(feature.featureId)
        }
    }
    val junctionKeys = usagesByKey
        .filterValues { usages -> usages.size > 1 }
        .keys

    val segmentPrototypes = mutableListOf<TileRuntimeWaySegmentPrototype>()
    wayFeatures.forEach { feature ->
        if (feature.geometry.size < 2) {
            return@forEach
        }
        val splitIndexes = linkedSetOf(0, feature.geometry.lastIndex)
        feature.geometry.forEachIndexed { index, point ->
            if (index in 1 until feature.geometry.lastIndex && point.toRuntimeNodeKey() in junctionKeys) {
                splitIndexes += index
            }
        }
        val orderedSplitIndexes = splitIndexes.sorted()
        orderedSplitIndexes.zipWithNext().forEach { (startIndex, endIndex) ->
            val geoPoints = feature.geometry.subList(startIndex, endIndex + 1)
            val localPoints = geoPoints
                .map(projector::toLocalPoint)
                .dedupeConsecutive()
            if (localPoints.size < 2) {
                return@forEach
            }
            val bounds = localBoundsFor(localPoints)
            val lengthMeters = localPolylineLengthMeters(localPoints, projector.coordinateScale)
            if (lengthMeters <= 0.0) {
                return@forEach
            }
            segmentPrototypes += TileRuntimeWaySegmentPrototype(
                sourceFeatureId = feature.featureId,
                tags = feature.tags.toSortedMap(),
                startKey = geoPoints.first().toRuntimeNodeKey().takeIf { it in junctionKeys },
                endKey = geoPoints.last().toRuntimeNodeKey().takeIf { it in junctionKeys },
                points = localPoints,
                bounds = bounds,
                lengthMeters = lengthMeters,
            )
        }
    }

    val connectedSegmentIdsByKey = linkedMapOf<TileRuntimeNodeKey, MutableSet<Int>>()
    segmentPrototypes.forEachIndexed { segmentId, prototype ->
        prototype.startKey?.let { key ->
            connectedSegmentIdsByKey.getOrPut(key) { linkedSetOf() } += segmentId
        }
        prototype.endKey?.let { key ->
            connectedSegmentIdsByKey.getOrPut(key) { linkedSetOf() } += segmentId
        }
    }

    val junctionIdByKey = linkedMapOf<TileRuntimeNodeKey, Int>()
    val junctions = buildList {
        connectedSegmentIdsByKey.entries
            .filter { (_, segmentIds) -> segmentIds.size > 1 }
            .forEachIndexed { junctionId, (key, segmentIds) ->
                junctionIdByKey[key] = junctionId
                val representativePoint = representativePointByKey.getValue(key)
                add(
                    TileRuntimeJunction(
                        junctionId = junctionId,
                        point = projector.toLocalPoint(representativePoint),
                        sourcePointCount = usagesByKey.getValue(key).size,
                        connectedSegmentIds = segmentIds.toList().sorted(),
                    ),
                )
            }
    }

    val segments = segmentPrototypes.mapIndexed { segmentId, prototype ->
        TileRuntimeWaySegment(
            segmentId = segmentId,
            sourceFeatureId = prototype.sourceFeatureId,
            tags = prototype.tags,
            startJunctionId = prototype.startKey?.let(junctionIdByKey::get),
            endJunctionId = prototype.endKey?.let(junctionIdByKey::get),
            points = prototype.points,
            bounds = prototype.bounds,
            lengthMeters = prototype.lengthMeters,
        )
    }

    val compiledPointFeatures = pointFeatures.mapIndexed { pointId, feature ->
        TileRuntimePointFeature(
            pointId = pointId,
            featureId = feature.featureId,
            tags = feature.tags.toSortedMap(),
            point = projector.toLocalPoint(feature.geometry.single()),
        )
    }

    val rootBounds = TileRuntimeLocalBounds(
        minX = 0,
        minY = 0,
        maxX = max(projector.queryWidthUnits, 1),
        maxY = max(projector.queryHeightUnits, 1),
    )
    val quadtreeNodes = buildTileRuntimeQuadtree(
        rootBounds = rootBounds,
        segments = segments,
        pointFeatures = compiledPointFeatures,
        junctions = junctions,
    )

    return TileRuntimePack(
        sourcePackSchemaVersion = sourcePack.schemaVersion,
        tileId = sourcePack.tileId,
        tileBounds = tileBounds,
        queryBounds = sourcePack.queryBounds,
        runtimeBounds = runtimeBounds,
        fetchedAtMillis = sourcePack.fetchedAtMillis,
        runtimeWidthMeters = projector.runtimeWidthMeters,
        runtimeHeightMeters = projector.runtimeHeightMeters,
        pointFeatures = compiledPointFeatures,
        waySegments = segments,
        junctions = junctions,
        quadtreeNodes = quadtreeNodes,
    )
}

internal fun tileRuntimePackToByteArray(pack: TileRuntimePack): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(TILE_RUNTIME_PACK_MAGIC)
        data.writeInt(pack.schemaVersion)
        data.writeInt(pack.sourcePackSchemaVersion)
        data.writeInt(pack.tileId.zoom)
        data.writeInt(pack.tileId.x)
        data.writeInt(pack.tileId.y)
        writeGeoBounds(data, pack.tileBounds)
        writeGeoBounds(data, pack.queryBounds)
        writeGeoBounds(data, pack.runtimeBounds)
        data.writeLong(pack.fetchedAtMillis)
        data.writeInt(pack.coordinateScale)
        data.writeDouble(pack.runtimeWidthMeters)
        data.writeDouble(pack.runtimeHeightMeters)

        data.writeInt(pack.pointFeatures.size)
        pack.pointFeatures.forEach { point ->
            data.writeInt(point.pointId)
            data.writeUTF(point.featureId)
            writeStringMap(data, point.tags)
            writeLocalPoint(data, point.point)
        }

        data.writeInt(pack.waySegments.size)
        pack.waySegments.forEach { segment ->
            data.writeInt(segment.segmentId)
            data.writeUTF(segment.sourceFeatureId)
            writeStringMap(data, segment.tags)
            data.writeInt(segment.startJunctionId ?: -1)
            data.writeInt(segment.endJunctionId ?: -1)
            writeLocalBounds(data, segment.bounds)
            data.writeDouble(segment.lengthMeters)
            writeLocalPoints(data, segment.points)
        }

        data.writeInt(pack.junctions.size)
        pack.junctions.forEach { junction ->
            data.writeInt(junction.junctionId)
            writeLocalPoint(data, junction.point)
            data.writeInt(junction.sourcePointCount)
            writeIntList(data, junction.connectedSegmentIds)
        }

        data.writeInt(pack.quadtreeNodes.size)
        pack.quadtreeNodes.forEach { node ->
            data.writeInt(node.nodeIndex)
            writeLocalBounds(data, node.bounds)
            writeIntList(data, node.childIndexes)
            writeIntList(data, node.segmentIds)
            writeIntList(data, node.pointIds)
            writeIntList(data, node.junctionIds)
            writeIntList(data, node.neighborLeafIndexes)
        }
    }
    return output.toByteArray()
}

internal fun tileRuntimePackFromByteArray(bytes: ByteArray): TileRuntimePack {
    DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        val magic = data.readInt()
        require(magic == TILE_RUNTIME_PACK_MAGIC) {
            "Unexpected tile runtime pack magic: $magic"
        }
        val schemaVersion = data.readInt()
        require(schemaVersion == TILE_RUNTIME_PACK_SCHEMA_VERSION) {
            "Unsupported tile runtime pack schema version: $schemaVersion"
        }
        val sourcePackSchemaVersion = data.readInt()
        val tileId = DownloadTileId(
            zoom = data.readInt(),
            x = data.readInt(),
            y = data.readInt(),
        )
        val tileBounds = readGeoBounds(data)
        val queryBounds = readGeoBounds(data)
        val runtimeBounds = readGeoBounds(data)
        val fetchedAtMillis = data.readLong()
        val coordinateScale = data.readInt()
        val runtimeWidthMeters = data.readDouble()
        val runtimeHeightMeters = data.readDouble()

        val pointFeatures = buildList {
            repeat(data.readInt()) {
                add(
                    TileRuntimePointFeature(
                        pointId = data.readInt(),
                        featureId = data.readUTF(),
                        tags = readStringMap(data),
                        point = readLocalPoint(data),
                    ),
                )
            }
        }

        val waySegments = buildList {
            repeat(data.readInt()) {
                val segmentId = data.readInt()
                val sourceFeatureId = data.readUTF()
                val tags = readStringMap(data)
                val startJunctionId = data.readInt().takeIf { it >= 0 }
                val endJunctionId = data.readInt().takeIf { it >= 0 }
                add(
                    TileRuntimeWaySegment(
                        segmentId = segmentId,
                        sourceFeatureId = sourceFeatureId,
                        tags = tags,
                        startJunctionId = startJunctionId,
                        endJunctionId = endJunctionId,
                        bounds = readLocalBounds(data),
                        lengthMeters = data.readDouble(),
                        points = readLocalPoints(data),
                    ),
                )
            }
        }

        val junctions = buildList {
            repeat(data.readInt()) {
                add(
                    TileRuntimeJunction(
                        junctionId = data.readInt(),
                        point = readLocalPoint(data),
                        sourcePointCount = data.readInt(),
                        connectedSegmentIds = readIntList(data),
                    ),
                )
            }
        }

        val quadtreeNodes = buildList {
            repeat(data.readInt()) {
                add(
                    TileRuntimeQuadtreeNode(
                        nodeIndex = data.readInt(),
                        bounds = readLocalBounds(data),
                        childIndexes = readIntList(data),
                        segmentIds = readIntList(data),
                        pointIds = readIntList(data),
                        junctionIds = readIntList(data),
                        neighborLeafIndexes = readIntList(data),
                    ),
                )
            }
        }

        return TileRuntimePack(
            schemaVersion = schemaVersion,
            sourcePackSchemaVersion = sourcePackSchemaVersion,
            tileId = tileId,
            tileBounds = tileBounds,
            queryBounds = queryBounds,
            runtimeBounds = runtimeBounds,
            fetchedAtMillis = fetchedAtMillis,
            coordinateScale = coordinateScale,
            runtimeWidthMeters = runtimeWidthMeters,
            runtimeHeightMeters = runtimeHeightMeters,
            pointFeatures = pointFeatures,
            waySegments = waySegments,
            junctions = junctions,
            quadtreeNodes = quadtreeNodes,
        )
    }
}

internal fun tileRuntimeLocalPointToGeoPoint(
    pack: TileRuntimePack,
    point: TileRuntimeLocalPoint,
): GeoPoint {
    return TileRuntimeProjector(
        runtimeBounds = pack.runtimeBounds,
        coordinateScale = pack.coordinateScale,
    ).toGeoPoint(point)
}

internal fun tileRuntimeGeoPointToLocalPoint(
    pack: TileRuntimePack,
    point: GeoPoint,
): TileRuntimeLocalPoint {
    return TileRuntimeProjector(
        runtimeBounds = pack.runtimeBounds,
        coordinateScale = pack.coordinateScale,
    ).toLocalPoint(point)
}

private data class TileRuntimeWaySegmentPrototype(
    val sourceFeatureId: String,
    val tags: Map<String, String>,
    val startKey: TileRuntimeNodeKey?,
    val endKey: TileRuntimeNodeKey?,
    val points: List<TileRuntimeLocalPoint>,
    val bounds: TileRuntimeLocalBounds,
    val lengthMeters: Double,
)

private data class TileRuntimeNodeKey(
    val latE7: Int,
    val lonE7: Int,
)

private class TileRuntimeProjector(
    runtimeBounds: GeoBounds,
    val coordinateScale: Int = TILE_RUNTIME_COORDINATE_SCALE,
) {
    private val originX = mercatorMetersX(runtimeBounds.west)
    private val originY = mercatorMetersY(runtimeBounds.south)
    private val maxX = mercatorMetersX(runtimeBounds.east)
    private val maxY = mercatorMetersY(runtimeBounds.north)

    val runtimeWidthMeters: Double = max(maxX - originX, 0.0)
    val runtimeHeightMeters: Double = max(maxY - originY, 0.0)
    val queryWidthUnits: Int = max(ceil(runtimeWidthMeters * coordinateScale).toInt(), 1)
    val queryHeightUnits: Int = max(ceil(runtimeHeightMeters * coordinateScale).toInt(), 1)

    fun toLocalPoint(point: GeoPoint): TileRuntimeLocalPoint {
        val xMeters = mercatorMetersX(point.lon) - originX
        val yMeters = mercatorMetersY(point.lat) - originY
        return TileRuntimeLocalPoint(
            x = (xMeters * coordinateScale).roundToInt(),
            y = (yMeters * coordinateScale).roundToInt(),
        )
    }

    fun toGeoPoint(point: TileRuntimeLocalPoint): GeoPoint {
        val xMeters = originX + point.x.toDouble() / coordinateScale.toDouble()
        val yMeters = originY + point.y.toDouble() / coordinateScale.toDouble()
        return GeoPoint(
            lat = mercatorMetersToLat(yMeters),
            lon = mercatorMetersToLon(xMeters),
        )
    }
}

private fun runtimeGeoBoundsFor(sourcePack: TileContextPack): GeoBounds {
    val allPoints = sourcePack.features.flatMap(TileContextFeature::geometry)
    if (allPoints.isEmpty()) {
        return sourcePack.queryBounds
    }
    return GeoBounds(
        west = min(sourcePack.queryBounds.west, allPoints.minOf(GeoPoint::lon)),
        south = min(sourcePack.queryBounds.south, allPoints.minOf(GeoPoint::lat)),
        east = max(sourcePack.queryBounds.east, allPoints.maxOf(GeoPoint::lon)),
        north = max(sourcePack.queryBounds.north, allPoints.maxOf(GeoPoint::lat)),
    )
}

private fun buildTileRuntimeQuadtree(
    rootBounds: TileRuntimeLocalBounds,
    segments: List<TileRuntimeWaySegment>,
    pointFeatures: List<TileRuntimePointFeature>,
    junctions: List<TileRuntimeJunction>,
): List<TileRuntimeQuadtreeNode> {
    val segmentBoundsById = segments.associate { it.segmentId to it.bounds }
    val pointBoundsById = pointFeatures.associate { feature ->
        val bounds = TileRuntimeLocalBounds(
            minX = feature.point.x,
            minY = feature.point.y,
            maxX = feature.point.x,
            maxY = feature.point.y,
        )
        feature.pointId to bounds
    }
    val junctionBoundsById = junctions.associate { junction ->
        val bounds = TileRuntimeLocalBounds(
            minX = junction.point.x,
            minY = junction.point.y,
            maxX = junction.point.x,
            maxY = junction.point.y,
        )
        junction.junctionId to bounds
    }

    val mutableNodes = mutableListOf<TileRuntimeMutableNode>()

    fun buildNode(
        bounds: TileRuntimeLocalBounds,
        segmentIds: List<Int>,
        pointIds: List<Int>,
        junctionIds: List<Int>,
        depth: Int,
    ): Int {
        val nodeIndex = mutableNodes.size
        mutableNodes += TileRuntimeMutableNode(
            bounds = bounds,
            childIndexes = emptyList(),
            segmentIds = segmentIds.sorted(),
            pointIds = pointIds.sorted(),
            junctionIds = junctionIds.sorted(),
        )

        val itemCount = segmentIds.size + pointIds.size + junctionIds.size
        val canSplit = depth < TILE_RUNTIME_MAX_DEPTH &&
            itemCount > TILE_RUNTIME_MAX_ITEMS_PER_LEAF &&
            bounds.maxX - bounds.minX > 1 &&
            bounds.maxY - bounds.minY > 1
        if (!canSplit) {
            return nodeIndex
        }

        val children = splitBounds(bounds)
        val childAssignments = children.map { childBounds ->
            TileRuntimeNodeItems(
                segmentIds = segmentIds.filter { tileRuntimeBoundsIntersect(segmentBoundsById.getValue(it), childBounds) },
                pointIds = pointIds.filter { tileRuntimeBoundsIntersect(pointBoundsById.getValue(it), childBounds) },
                junctionIds = junctionIds.filter { tileRuntimeBoundsIntersect(junctionBoundsById.getValue(it), childBounds) },
            )
        }
        val nonEmptyChildren = childAssignments.count { it.totalCount > 0 }
        val meaningfulSplit = nonEmptyChildren > 1 && childAssignments.any { it.totalCount < itemCount }
        if (!meaningfulSplit) {
            return nodeIndex
        }

        val childIndexes = children.indices.mapNotNull { childIndex ->
            val childItems = childAssignments[childIndex]
            if (childItems.totalCount == 0) {
                null
            } else {
                buildNode(
                    bounds = children[childIndex],
                    segmentIds = childItems.segmentIds,
                    pointIds = childItems.pointIds,
                    junctionIds = childItems.junctionIds,
                    depth = depth + 1,
                )
            }
        }
        mutableNodes[nodeIndex] = mutableNodes[nodeIndex].copy(
            childIndexes = childIndexes,
            segmentIds = emptyList(),
            pointIds = emptyList(),
            junctionIds = emptyList(),
        )
        return nodeIndex
    }

    buildNode(
        bounds = rootBounds,
        segmentIds = segments.map(TileRuntimeWaySegment::segmentId),
        pointIds = pointFeatures.map(TileRuntimePointFeature::pointId),
        junctionIds = junctions.map(TileRuntimeJunction::junctionId),
        depth = 0,
    )

    val leafIndexes = mutableNodes.indices.filter { mutableNodes[it].childIndexes.isEmpty() }
    val leafNeighbors = leafIndexes.associateWith { leafIndex ->
        val bounds = mutableNodes[leafIndex].bounds
        leafIndexes.filter { otherIndex ->
            otherIndex != leafIndex && touchesOrOverlaps(bounds, mutableNodes[otherIndex].bounds)
        }.sorted()
    }

    return mutableNodes.mapIndexed { index, node ->
        TileRuntimeQuadtreeNode(
            nodeIndex = index,
            bounds = node.bounds,
            childIndexes = node.childIndexes,
            segmentIds = node.segmentIds,
            pointIds = node.pointIds,
            junctionIds = node.junctionIds,
            neighborLeafIndexes = leafNeighbors[index].orEmpty(),
        )
    }
}

private data class TileRuntimeNodeItems(
    val segmentIds: List<Int>,
    val pointIds: List<Int>,
    val junctionIds: List<Int>,
) {
    val totalCount: Int
        get() = segmentIds.size + pointIds.size + junctionIds.size
}

private data class TileRuntimeMutableNode(
    val bounds: TileRuntimeLocalBounds,
    val childIndexes: List<Int>,
    val segmentIds: List<Int>,
    val pointIds: List<Int>,
    val junctionIds: List<Int>,
)

private fun splitBounds(bounds: TileRuntimeLocalBounds): List<TileRuntimeLocalBounds> {
    val midX = (bounds.minX + bounds.maxX) / 2
    val midY = (bounds.minY + bounds.maxY) / 2
    return listOf(
        TileRuntimeLocalBounds(bounds.minX, bounds.minY, midX, midY),
        TileRuntimeLocalBounds(midX, bounds.minY, bounds.maxX, midY),
        TileRuntimeLocalBounds(bounds.minX, midY, midX, bounds.maxY),
        TileRuntimeLocalBounds(midX, midY, bounds.maxX, bounds.maxY),
    )
}

internal fun tileRuntimeBoundsIntersect(
    left: TileRuntimeLocalBounds,
    right: TileRuntimeLocalBounds,
): Boolean {
    return left.maxX >= right.minX &&
        left.minX <= right.maxX &&
        left.maxY >= right.minY &&
        left.minY <= right.maxY
}

private fun touchesOrOverlaps(
    left: TileRuntimeLocalBounds,
    right: TileRuntimeLocalBounds,
): Boolean {
    return left.maxX >= right.minX &&
        left.minX <= right.maxX &&
        left.maxY >= right.minY &&
        left.minY <= right.maxY
}

private fun GeoPoint.toRuntimeNodeKey(): TileRuntimeNodeKey {
    return TileRuntimeNodeKey(
        latE7 = (lat * TILE_RUNTIME_NODE_KEY_SCALE).roundToInt(),
        lonE7 = (lon * TILE_RUNTIME_NODE_KEY_SCALE).roundToInt(),
    )
}

private fun List<TileRuntimeLocalPoint>.dedupeConsecutive(): List<TileRuntimeLocalPoint> {
    if (size < 2) {
        return this
    }
    return buildList(size) {
        this@dedupeConsecutive.forEach { point ->
            if (lastOrNull() != point) {
                add(point)
            }
        }
    }
}

private fun localBoundsFor(points: List<TileRuntimeLocalPoint>): TileRuntimeLocalBounds {
    return TileRuntimeLocalBounds(
        minX = points.minOf(TileRuntimeLocalPoint::x),
        minY = points.minOf(TileRuntimeLocalPoint::y),
        maxX = points.maxOf(TileRuntimeLocalPoint::x),
        maxY = points.maxOf(TileRuntimeLocalPoint::y),
    )
}

private fun localPolylineLengthMeters(
    points: List<TileRuntimeLocalPoint>,
    coordinateScale: Int,
): Double {
    var total = 0.0
    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        val dx = (end.x - start.x).toDouble() / coordinateScale.toDouble()
        val dy = (end.y - start.y).toDouble() / coordinateScale.toDouble()
        total += sqrt(dx * dx + dy * dy)
    }
    return total
}

private fun mercatorMetersX(longitude: Double): Double {
    return TILE_RUNTIME_EARTH_RADIUS_METERS * Math.toRadians(longitude)
}

private fun mercatorMetersY(latitude: Double): Double {
    return TILE_RUNTIME_EARTH_RADIUS_METERS * asinh(tan(Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))))
}

private fun mercatorMetersToLon(xMeters: Double): Double {
    return Math.toDegrees(xMeters / TILE_RUNTIME_EARTH_RADIUS_METERS)
}

private fun mercatorMetersToLat(yMeters: Double): Double {
    return Math.toDegrees(2.0 * kotlin.math.atan(kotlin.math.exp(yMeters / TILE_RUNTIME_EARTH_RADIUS_METERS)) - PI / 2.0)
}

private fun writeGeoBounds(
    data: DataOutputStream,
    bounds: GeoBounds,
) {
    data.writeDouble(bounds.west)
    data.writeDouble(bounds.south)
    data.writeDouble(bounds.east)
    data.writeDouble(bounds.north)
}

private fun readGeoBounds(data: DataInputStream): GeoBounds {
    return GeoBounds(
        west = data.readDouble(),
        south = data.readDouble(),
        east = data.readDouble(),
        north = data.readDouble(),
    )
}

private fun writeLocalPoint(
    data: DataOutputStream,
    point: TileRuntimeLocalPoint,
) {
    data.writeInt(point.x)
    data.writeInt(point.y)
}

private fun readLocalPoint(data: DataInputStream): TileRuntimeLocalPoint {
    return TileRuntimeLocalPoint(
        x = data.readInt(),
        y = data.readInt(),
    )
}

private fun writeLocalPoints(
    data: DataOutputStream,
    points: List<TileRuntimeLocalPoint>,
) {
    data.writeInt(points.size)
    points.forEach { point ->
        writeLocalPoint(data, point)
    }
}

private fun readLocalPoints(data: DataInputStream): List<TileRuntimeLocalPoint> {
    return buildList {
        repeat(data.readInt()) {
            add(readLocalPoint(data))
        }
    }
}

private fun writeLocalBounds(
    data: DataOutputStream,
    bounds: TileRuntimeLocalBounds,
) {
    data.writeInt(bounds.minX)
    data.writeInt(bounds.minY)
    data.writeInt(bounds.maxX)
    data.writeInt(bounds.maxY)
}

private fun readLocalBounds(data: DataInputStream): TileRuntimeLocalBounds {
    return TileRuntimeLocalBounds(
        minX = data.readInt(),
        minY = data.readInt(),
        maxX = data.readInt(),
        maxY = data.readInt(),
    )
}

private fun writeStringMap(
    data: DataOutputStream,
    map: Map<String, String>,
) {
    data.writeInt(map.size)
    map.toSortedMap().forEach { (key, value) ->
        data.writeUTF(key)
        data.writeUTF(value)
    }
}

private fun readStringMap(data: DataInputStream): Map<String, String> {
    return buildMap {
        repeat(data.readInt()) {
            put(data.readUTF(), data.readUTF())
        }
    }
}

private fun writeIntList(
    data: DataOutputStream,
    values: List<Int>,
) {
    data.writeInt(values.size)
    values.forEach(data::writeInt)
}

private fun readIntList(data: DataInputStream): List<Int> {
    return buildList {
        repeat(data.readInt()) {
            add(data.readInt())
        }
    }
}
