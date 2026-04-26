package dev.ra.geepee

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileRuntimePackRealTileTest {
    @Test
    fun realDownloadedTileCompilesIntoRuntimePackAndRoundTrips() {
        val sourcePack = loadRuntimeTileFixture("tile-context/10-571-356-local.json")

        val compiled = compileTileRuntimePack(sourcePack)
        val restored = tileRuntimePackFromByteArray(tileRuntimePackToByteArray(compiled))

        assertTrue(compiled.waySegments.isNotEmpty())
        assertTrue(compiled.quadtreeNodes.isNotEmpty())
        assertEquals(
            sourcePack.features.count { it.geometryKind == TileGeometryKind.Point },
            compiled.pointFeatures.size,
        )
        assertEquals(compiled, restored)
    }

    @Test
    fun realDownloadedTileRuntimePackKeepsFeatureCoverageInLeaves() {
        val sourcePack = loadRuntimeTileFixture("tile-context/10-571-356-local.json")
        val compiled = compileTileRuntimePack(sourcePack)
        val leafNodes = compiled.quadtreeNodes.filter(TileRuntimeQuadtreeNode::isLeaf)
        val rootBounds = compiled.quadtreeNodes.first().bounds

        val segmentIdsInLeaves = leafNodes.flatMap(TileRuntimeQuadtreeNode::segmentIds).distinct().sorted()
        val pointIdsInLeaves = leafNodes.flatMap(TileRuntimeQuadtreeNode::pointIds).distinct().sorted()
        val junctionIdsInLeaves = leafNodes.flatMap(TileRuntimeQuadtreeNode::junctionIds).distinct().sorted()

        assertEquals(compiled.waySegments.map(TileRuntimeWaySegment::segmentId).sorted(), segmentIdsInLeaves)
        assertEquals(compiled.pointFeatures.map(TileRuntimePointFeature::pointId).sorted(), pointIdsInLeaves)
        assertEquals(compiled.junctions.map(TileRuntimeJunction::junctionId).sorted(), junctionIdsInLeaves)

        compiled.pointFeatures.forEach { feature ->
            assertLocalPointWithin(rootBounds, feature.point)
        }
        compiled.junctions.forEach { junction ->
            assertLocalPointWithin(rootBounds, junction.point)
        }
        compiled.waySegments.forEach { segment ->
            assertTrue(segment.lengthMeters > 0.0)
            segment.points.forEach { point ->
                assertLocalPointWithin(rootBounds, point)
            }
        }
    }

    private fun loadRuntimeTileFixture(path: String): TileContextPack {
        val resource = requireNotNull(javaClass.classLoader?.getResource("dev/ra/geepee/$path")) {
            "Missing tile fixture resource: $path"
        }
        return tileContextPackFromJson(File(resource.toURI()).readText())
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
