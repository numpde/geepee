package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Test

class UiModelsTest {
    @Test
    fun formatAgeUsesExplicitUnits() {
        assertEquals("59 sec", formatAge(59_000L))
        assertEquals("1 min", formatAge(60_000L))
        assertEquals("59 min", formatAge(3_599_000L))
        assertEquals("1 hr", formatAge(3_600_000L))
    }

    @Test
    fun routeScaleZoomLadderIncludesNewSessionScales() {
        assertEquals(RouteScale.Twenty, RouteScale.Fifty.zoomIn())
        assertEquals(RouteScale.Ten, RouteScale.Twenty.zoomIn())
        assertEquals(RouteScale.FiveHundred, RouteScale.TwoHundred.zoomOut())
        assertEquals(RouteScale.ThreeKilometers, RouteScale.Kilometer.zoomOut())
        assertEquals(RouteScale.TenKilometers, RouteScale.ThreeKilometers.zoomOut())
    }

    @Test
    fun scaleBarDistanceSupportsTinyAndLargeWindows() {
        assertEquals(2.0, RouteScale.Ten.scaleBarDistanceMeters(), 0.0)
        assertEquals(50.0, RouteScale.TwoHundred.scaleBarDistanceMeters(), 0.0)
        assertEquals(2000.0, RouteScale.TenKilometers.scaleBarDistanceMeters(), 0.0)
    }
}
