package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCanvasTest {
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
