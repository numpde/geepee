package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeePeeScreenStateTest {
    @Test
    fun buildMovementViewState_prefersViewportFocusForLiveView() {
        val viewportFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24211, 36.83407),
            windowWidthMeters = 150.0,
            projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
        )
        val fallbackPoint = GeoPoint(-1.25, 36.83)

        val state = buildMovementViewState(
            movementMode = true,
            viewportFocus = viewportFocus,
            setupBounds = Bounds(-10.0, 10.0, -5.0, 5.0),
            routeScale = RouteScale.TwoHundred,
            currentReferenceGeoPoint = fallbackPoint,
            hasAnalysis = true,
        )

        assertEquals(viewportFocus.projectedBounds, state.tileGridBounds)
        assertEquals(viewportFocus.windowWidthMeters, state.windowWidthMeters, 0.0)
        assertEquals(viewportFocus.centerGeoPoint, state.openInPoint)
        assertEquals(viewportFocus, state.effectiveMapInfoFocus)
    }

    @Test
    fun buildMovementViewState_suppressesMapInfoFocusWithoutAnalysis() {
        val viewportFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24211, 36.83407),
            windowWidthMeters = 150.0,
            projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
        )

        val state = buildMovementViewState(
            movementMode = true,
            viewportFocus = viewportFocus,
            setupBounds = null,
            routeScale = RouteScale.TwoHundred,
            currentReferenceGeoPoint = null,
            hasAnalysis = false,
        )

        assertNull(state.effectiveMapInfoFocus)
        assertEquals(viewportFocus.projectedBounds, state.tileGridBounds)
        assertEquals(viewportFocus.centerGeoPoint, state.openInPoint)
    }

    @Test
    fun buildMovementViewState_usesSetupFallbacksOutsideMovementMode() {
        val setupBounds = Bounds(-10.0, 10.0, -5.0, 5.0)
        val fallbackPoint = GeoPoint(-1.25, 36.83)

        val state = buildMovementViewState(
            movementMode = false,
            viewportFocus = MapInfoFocus(
                centerGeoPoint = GeoPoint(-1.24211, 36.83407),
                windowWidthMeters = 150.0,
                projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
            ),
            setupBounds = setupBounds,
            routeScale = RouteScale.FiveHundred,
            currentReferenceGeoPoint = fallbackPoint,
            hasAnalysis = true,
        )

        assertEquals(setupBounds, state.tileGridBounds)
        assertEquals(RouteScale.FiveHundred.windowWidthMeters, state.windowWidthMeters, 0.0)
        assertEquals(fallbackPoint, state.openInPoint)
        assertNull(state.effectiveMapInfoFocus)
    }
}
