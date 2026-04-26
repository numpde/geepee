package dev.ra.geepee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInfoFocusTest {
    @Test
    fun mapInfoFocusChanged_returnsFalseForNearbyRawFocusWithinThreshold() {
        val previous = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24211, 36.83407),
            windowWidthMeters = 200.0,
        )
        val current = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24212, 36.83408),
            windowWidthMeters = 202.0,
        )

        assertFalse(mapInfoFocusChanged(previous, current))
    }

    @Test
    fun mapInfoFocusChanged_returnsTrueWhenResolvedBoundsChange() {
        val previous = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24211, 36.83407),
            windowWidthMeters = 200.0,
            projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
        )
        val current = previous.copy(
            projectedBounds = Bounds(-120.0, 80.0, -80.0, 80.0),
        )

        assertTrue(mapInfoFocusChanged(previous, current))
    }

    @Test
    fun mapInfoFocusChanged_returnsTrueWhenOnlyOneFocusHasBounds() {
        val previous = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24211, 36.83407),
            windowWidthMeters = 200.0,
        )
        val current = previous.copy(
            projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
        )

        assertTrue(mapInfoFocusChanged(previous, current))
    }
}
