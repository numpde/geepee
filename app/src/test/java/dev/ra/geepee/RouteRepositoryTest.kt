package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteRepositoryTest {
    @Test
    fun reverseRouteSegmentsReversesSegmentOrderAndPoints() {
        val original = listOf(
            listOf(
                GeoPoint(lat = 1.0, lon = 1.0),
                GeoPoint(lat = 2.0, lon = 2.0),
            ),
            listOf(
                GeoPoint(lat = 3.0, lon = 3.0),
                GeoPoint(lat = 4.0, lon = 4.0),
                GeoPoint(lat = 5.0, lon = 5.0),
            ),
        )

        val reversed = reverseRouteSegments(original)

        assertEquals(
            listOf(
                listOf(
                    GeoPoint(lat = 5.0, lon = 5.0),
                    GeoPoint(lat = 4.0, lon = 4.0),
                    GeoPoint(lat = 3.0, lon = 3.0),
                ),
                listOf(
                    GeoPoint(lat = 2.0, lon = 2.0),
                    GeoPoint(lat = 1.0, lon = 1.0),
                ),
            ),
            reversed,
        )
    }
}
