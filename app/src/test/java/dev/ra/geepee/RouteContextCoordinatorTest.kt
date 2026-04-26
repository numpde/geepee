package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteContextCoordinatorTest {
    @Test
    fun resolveNearbyWayQueryFocus_defaultsToMatchedRoutePointWithoutExplicitFocus() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 1.0),
                    GeoPoint(1.0, 1.0),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = 0.1,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val resolved = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = null,
            config = DefaultTileContextConfig,
            defaultFocusWindowWidthMeters = 200.0,
        )

        assertEquals(analysis.nearestGeoPoint, resolved.mapInfoFocus.centerGeoPoint)
        assertEquals(200.0, resolved.mapInfoFocus.windowWidthMeters, 0.0)
        assertEquals(analysis.nearestEdgeIndex, resolved.nearestEdgeIndex)
        assertEquals(
            tileIdForGeoPoint(analysis.nearestGeoPoint, DefaultTileContextConfig.downloadZoom),
            resolved.centerTileId,
        )
        assertTrue(resolved.localTileIds.contains(resolved.centerTileId))
    }

    @Test
    fun resolveNearbyWayQueryFocus_usesExplicitViewportCenterInsteadOfMatchedRoutePoint() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 1.0),
                    GeoPoint(1.0, 1.0),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = 0.1,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val explicitFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(0.9, 1.0),
            windowWidthMeters = 150.0,
        )

        val resolved = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = explicitFocus,
            config = DefaultTileContextConfig,
            defaultFocusWindowWidthMeters = 200.0,
        )

        assertEquals(explicitFocus, resolved.mapInfoFocus)
        assertEquals(
            tileIdForGeoPoint(explicitFocus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
            resolved.centerTileId,
        )
        assertTrue(resolved.localTileIds.contains(resolved.centerTileId))
        assertEquals(1, resolved.nearestEdgeIndex)
    }
}

