package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapIntentsTest {
    @Test
    fun mapZoomTracksCurrentWindowWidth() {
        val wide = mapZoomForWindowWidthMeters(
            latitude = 47.839217,
            windowWidthMeters = 10_000.0,
            viewportWidthPx = 1080,
        )
        val medium = mapZoomForWindowWidthMeters(
            latitude = 47.839217,
            windowWidthMeters = 1_000.0,
            viewportWidthPx = 1080,
        )
        val tight = mapZoomForWindowWidthMeters(
            latitude = 47.839217,
            windowWidthMeters = 100.0,
            viewportWidthPx = 1080,
        )

        assertTrue("Expected tighter GeePee scale to map to higher external zoom", tight > medium)
        assertTrue("Expected medium GeePee scale to map to higher external zoom than wide", medium > wide)
    }

    @Test
    fun osmWebUrlUsesExpectedZoomAndCoordinates() {
        val url = osmWebUrl(
            point = GeoPoint(lat = 47.839217, lon = 21.066739),
            zoom = 16,
        )

        assertEquals(
            "https://www.openstreetmap.org/#map=16/47.839217/21.066739",
            url,
        )
    }
}
