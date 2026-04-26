package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileRuntimePackTest {
    @Test
    fun compileTileRuntimePackSplitsWaysAtSharedJunctions() {
        val sourcePack = syntheticJunctionPack()

        val compiled = compileTileRuntimePack(sourcePack)

        assertEquals(4, compiled.waySegments.size)
        assertEquals(1, compiled.junctions.size)
        assertEquals(listOf(0, 1, 2, 3), compiled.junctions.single().connectedSegmentIds)
        assertEquals(1, compiled.pointFeatures.size)

        val point = compiled.pointFeatures.single()
        val restoredGeoPoint = tileRuntimeLocalPointToGeoPoint(compiled, point.point)
        assertEquals(0.002, restoredGeoPoint.lat, 1e-5)
        assertEquals(0.002, restoredGeoPoint.lon, 1e-5)

        val referencedJunctionSegments = compiled.waySegments.count { segment ->
            segment.startJunctionId != null || segment.endJunctionId != null
        }
        assertEquals(4, referencedJunctionSegments)
    }

    @Test
    fun tileRuntimePackRoundTripsThroughBinaryCodec() {
        val compiled = compileTileRuntimePack(syntheticJunctionPack())

        val restored = tileRuntimePackFromByteArray(tileRuntimePackToByteArray(compiled))

        assertEquals(compiled, restored)
    }

    @Test
    fun compiledQuadtreeSplitsDenseTileAndKeepsNeighborLinksSymmetric() {
        val sourcePack = syntheticDensePointPack()

        val compiled = compileTileRuntimePack(sourcePack)

        assertTrue(compiled.quadtreeNodes.size > 1)
        val leafNodes = compiled.quadtreeNodes.filter(TileRuntimeQuadtreeNode::isLeaf)
        assertTrue(leafNodes.size > 1)

        val visiblePointIds = leafNodes
            .flatMap(TileRuntimeQuadtreeNode::pointIds)
            .distinct()
            .sorted()
        assertEquals(
            compiled.pointFeatures.map(TileRuntimePointFeature::pointId).sorted(),
            visiblePointIds,
        )

        leafNodes.forEach { leaf ->
            leaf.neighborLeafIndexes.forEach { neighborIndex ->
                val neighbor = compiled.quadtreeNodes[neighborIndex]
                assertTrue(
                    "Expected leaf-neighbor relationships to be symmetric",
                    neighbor.neighborLeafIndexes.contains(leaf.nodeIndex),
                )
            }
        }
    }

    @Test
    fun compiledRuntimePackKeepsAllLocalGeometryInsideRootBounds() {
        val compiled = compileTileRuntimePack(syntheticJunctionPack())
        val rootBounds = compiled.quadtreeNodes.first().bounds

        compiled.pointFeatures.forEach { feature ->
            assertLocalPointWithin(rootBounds, feature.point)
        }
        compiled.junctions.forEach { junction ->
            assertLocalPointWithin(rootBounds, junction.point)
        }
        compiled.waySegments.forEach { segment ->
            segment.points.forEach { point ->
                assertLocalPointWithin(rootBounds, point)
            }
        }
    }

    private fun syntheticJunctionPack(): TileContextPack {
        val tileId = tileIdForGeoPoint(GeoPoint(lat = 0.0, lon = 0.0), zoom = 10)
        return TileContextPack(
            tileId = tileId,
            queryBounds = GeoBounds(
                west = -0.01,
                south = -0.01,
                east = 0.01,
                north = 0.01,
            ),
            fetchedAtMillis = 123L,
            features = listOf(
                TileContextFeature(
                    featureId = "way/100",
                    geometryKind = TileGeometryKind.Way,
                    tags = mapOf("highway" to "cycleway", "name" to "East-West"),
                    geometry = listOf(
                        GeoPoint(lat = 0.0, lon = -0.005),
                        GeoPoint(lat = 0.0, lon = 0.0),
                        GeoPoint(lat = 0.0, lon = 0.005),
                    ),
                ),
                TileContextFeature(
                    featureId = "way/200",
                    geometryKind = TileGeometryKind.Way,
                    tags = mapOf("highway" to "service", "name" to "North-South"),
                    geometry = listOf(
                        GeoPoint(lat = -0.005, lon = 0.0),
                        GeoPoint(lat = 0.0, lon = 0.0),
                        GeoPoint(lat = 0.005, lon = 0.0),
                    ),
                ),
                TileContextFeature(
                    featureId = "node/300",
                    geometryKind = TileGeometryKind.Point,
                    tags = mapOf("amenity" to "drinking_water", "name" to "Pump"),
                    geometry = listOf(
                        GeoPoint(lat = 0.002, lon = 0.002),
                    ),
                ),
            ),
        )
    }

    private fun syntheticDensePointPack(): TileContextPack {
        val tileId = tileIdForGeoPoint(GeoPoint(lat = 0.0, lon = 0.0), zoom = 10)
        val points = buildList {
            var id = 1
            for (row in 0 until 4) {
                for (column in 0 until 4) {
                    add(
                        TileContextFeature(
                            featureId = "node/${id++}",
                            geometryKind = TileGeometryKind.Point,
                            tags = mapOf("amenity" to "toilets"),
                            geometry = listOf(
                                GeoPoint(
                                    lat = -0.006 + row * 0.004,
                                    lon = -0.006 + column * 0.004,
                                ),
                            ),
                        ),
                    )
                }
            }
        }
        return TileContextPack(
            tileId = tileId,
            queryBounds = GeoBounds(
                west = -0.01,
                south = -0.01,
                east = 0.01,
                north = 0.01,
            ),
            fetchedAtMillis = 456L,
            features = points,
        )
    }

    private fun assertLocalPointWithin(
        bounds: TileRuntimeLocalBounds,
        point: TileRuntimeLocalPoint,
    ) {
        assertTrue(point.x >= bounds.minX)
        assertTrue(point.x <= bounds.maxX)
        assertTrue(point.y >= bounds.minY)
        assertTrue(point.y <= bounds.maxY)
    }
}
