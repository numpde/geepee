package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RouteRibbonTest {
    @Test
    fun routeRibbonMeshBuildsContinuousStripForBend() {
        val mesh = buildRouteRibbonMesh(
            points = listOf(
                RouteGradientPoint(ScreenPoint(0f, 0f), 0f),
                RouteGradientPoint(ScreenPoint(30f, 0f), 0.5f),
                RouteGradientPoint(ScreenPoint(30f, 20f), 1f),
            ),
            widthPx = 8f,
        )

        assertNotNull(mesh)
        assertEquals(6, mesh!!.vertexCount)
        assertEquals(4, mesh.triangleCount)
        assertEquals(12, mesh.vertices.positions.size)
        assertEquals(6, mesh.vertices.colors.size)
        assertTrue(mesh.vertices.positions.all { it.isFinite() })
    }

    @Test
    fun routeRibbonMeshCoalescesDuplicatePointsBeforeBuilding() {
        val mesh = buildRouteRibbonMesh(
            points = listOf(
                RouteGradientPoint(ScreenPoint(0f, 0f), 0f),
                RouteGradientPoint(ScreenPoint(10f, 0f), 0.25f),
                RouteGradientPoint(ScreenPoint(10f, 0f), 0.5f),
                RouteGradientPoint(ScreenPoint(20f, 0f), 1f),
            ),
            widthPx = 8f,
        )

        assertNotNull(mesh)
        assertEquals(6, mesh!!.vertexCount)
    }

    @Test
    fun routeGradientColorTracksProgressRatio() {
        assertEquals(ROUTE_START_COLOR, routeGradientColor(0f))
        assertEquals(ROUTE_FINISH_COLOR, routeGradientColor(1f))
    }

    @Test
    fun routeRibbonMeshClampsSharpShortJoinSpikes() {
        val join = ScreenPoint(14f, 0f)
        val mesh = buildRouteRibbonMesh(
            points = listOf(
                RouteGradientPoint(ScreenPoint(0f, 0f), 0f),
                RouteGradientPoint(join, 0.5f),
                RouteGradientPoint(ScreenPoint(18f, 10f), 1f),
            ),
            widthPx = 8f,
        )

        assertNotNull(mesh)
        val positions = mesh!!.vertices.positions
        val leftJoin = OffsetLike(positions[4], positions[5])
        val rightJoin = OffsetLike(positions[6], positions[7])
        val maxExpectedOffset = 8f

        assertTrue(distance(leftJoin, join) <= maxExpectedOffset)
        assertTrue(distance(rightJoin, join) <= maxExpectedOffset)
    }

    private fun distance(
        point: OffsetLike,
        center: ScreenPoint,
    ): Float {
        return hypot(point.x - center.x, point.y - center.y)
    }

    private data class OffsetLike(
        val x: Float,
        val y: Float,
    )
}
