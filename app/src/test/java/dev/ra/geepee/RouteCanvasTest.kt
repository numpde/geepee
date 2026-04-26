package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCanvasTest {
    @Test
    fun mergedDisplayGradientPolylinesCoalescesTouchingChunks() {
        val merged = mergedDisplayGradientPolylines(
            polylines = listOf(
                RouteGradientPolyline(
                    points = listOf(
                        RouteGradientPoint(ScreenPoint(0f, 0f), 0f),
                        RouteGradientPoint(ScreenPoint(10f, 0f), 0.1f),
                    ),
                ),
                RouteGradientPolyline(
                    points = listOf(
                        RouteGradientPoint(ScreenPoint(10f, 0f), 0.1f),
                        RouteGradientPoint(ScreenPoint(20f, 0f), 0.2f),
                    ),
                ),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals(3, merged.single().size)
    }

    @Test
    fun mergedDisplayGradientPolylinesCanPruneShortSharpSpike() {
        val merged = mergedDisplayGradientPolylines(
            polylines = listOf(
                RouteGradientPolyline(
                    points = listOf(
                        RouteGradientPoint(ScreenPoint(0f, 0f), 0f),
                        RouteGradientPoint(ScreenPoint(20f, 0f), 0.25f),
                        RouteGradientPoint(ScreenPoint(22f, 10f), 0.5f),
                        RouteGradientPoint(ScreenPoint(40f, 1f), 0.75f),
                    ),
                ),
            ),
            pruneSharpSpikes = true,
        )

        assertEquals(1, merged.size)
        assertEquals(3, merged.single().size)
        assertEquals(ScreenPoint(20f, 0f), merged.single()[1].point)
    }

    @Test
    fun routeSegmentEmphasisStaysFullWithoutMatchedRoutePosition() {
        assertEquals(
            1f,
            routeSegmentEmphasis(
                startProgressRatio = 0.2f,
                endProgressRatio = 0.25f,
                currentRouteMeters = null,
                totalRouteMeters = 1_000.0,
                windowWidthMeters = 200.0,
                isClosedLoop = false,
            ),
            0f,
        )
    }

    @Test
    fun routeSegmentEmphasisWrapsAcrossClosedLoopStartFinish() {
        val loopEmphasis = routeSegmentEmphasis(
            startProgressRatio = 0.98f,
            endProgressRatio = 1.0f,
            currentRouteMeters = 15.0,
            totalRouteMeters = 1_000.0,
            windowWidthMeters = 200.0,
            isClosedLoop = true,
        )
        val nonLoopEmphasis = routeSegmentEmphasis(
            startProgressRatio = 0.98f,
            endProgressRatio = 1.0f,
            currentRouteMeters = 15.0,
            totalRouteMeters = 1_000.0,
            windowWidthMeters = 200.0,
            isClosedLoop = false,
        )

        assertEquals(1f, loopEmphasis, 0f)
        assertEquals(0f, nonLoopEmphasis, 0f)
    }

    @Test
    fun routeSegmentEmphasisFadesFarRouteSections() {
        val emphasis = routeSegmentEmphasis(
            startProgressRatio = 0.0f,
            endProgressRatio = 0.05f,
            currentRouteMeters = 900.0,
            totalRouteMeters = 1_000.0,
            windowWidthMeters = 200.0,
            isClosedLoop = false,
        )

        assertTrue(emphasis < 0.05f)
    }

    @Test
    fun routeSegmentHighlightKindTreatsPastRouteAsBehindAndFutureAsAhead() {
        assertEquals(
            SessionRouteHighlightKind.Behind,
            routeSegmentHighlightKind(
                startProgressRatio = 0.10f,
                endProgressRatio = 0.16f,
                currentRouteMeters = 200.0,
                totalRouteMeters = 1_000.0,
                windowWidthMeters = 200.0,
                isClosedLoop = false,
            ),
        )
        assertEquals(
            SessionRouteHighlightKind.Ahead,
            routeSegmentHighlightKind(
                startProgressRatio = 0.24f,
                endProgressRatio = 0.30f,
                currentRouteMeters = 200.0,
                totalRouteMeters = 1_000.0,
                windowWidthMeters = 200.0,
                isClosedLoop = false,
            ),
        )
    }

    @Test
    fun routeSegmentHighlightKindWrapsAcrossClosedLoopStartFinish() {
        assertEquals(
            SessionRouteHighlightKind.Behind,
            routeSegmentHighlightKind(
                startProgressRatio = 0.96f,
                endProgressRatio = 0.99f,
                currentRouteMeters = 15.0,
                totalRouteMeters = 1_000.0,
                windowWidthMeters = 200.0,
                isClosedLoop = true,
            ),
        )
        assertEquals(
            SessionRouteHighlightKind.Ahead,
            routeSegmentHighlightKind(
                startProgressRatio = 0.01f,
                endProgressRatio = 0.04f,
                currentRouteMeters = 980.0,
                totalRouteMeters = 1_000.0,
                windowWidthMeters = 200.0,
                isClosedLoop = true,
            ),
        )
    }

    @Test
    fun routeSegmentHighlightKindSuppressesUnrelatedSegments() {
        assertNull(
            routeSegmentHighlightKind(
                startProgressRatio = 0.0f,
                endProgressRatio = 0.05f,
                currentRouteMeters = 900.0,
                totalRouteMeters = 1_000.0,
                windowWidthMeters = 200.0,
                isClosedLoop = false,
            ),
        )
    }
}
