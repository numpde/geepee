package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTileOverlayRealTileTest {
    @Test
    fun realDownloadedTileOverlayMatchesDirectRouteContext() {
        val fixture = loadRouteMapInfoFixture()
        val runtimePack = compileTileRuntimePack(fixture.sourcePack)

        val directContext = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(fixture.sourcePack),
            config = DefaultTileContextConfig,
        )
        val overlay = buildRouteTileOverlay(
            routeModel = fixture.routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )

        assertRouteContextsEquivalent(directContext, overlay.context)
        assertTrue(overlay.context.nearbyWays.isNotEmpty())
        assertTrue(overlay.leafEntries.any { it.nearbyWayIndexes.isNotEmpty() })
    }

    @Test
    fun focusedRealDownloadedTileOverlayMatchesFocusedDirectRouteContext() {
        val fixture = loadRouteMapInfoFixture()
        val runtimePack = compileTileRuntimePack(fixture.sourcePack)
        val focusPoint = fixture.geoPoints[6_854]
        val focus = MapInfoFocus(
            centerGeoPoint = focusPoint,
            windowWidthMeters = 1_000.0,
            projectedBounds = nearbyWayFocusBounds(
                routeModel = fixture.routeModel,
                focusGeoPoint = focusPoint,
                focusWindowWidthMeters = 1_000.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ) ?: fixture.routeModel.bounds,
        )

        val directContext = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(fixture.sourcePack),
            config = DefaultTileContextConfig,
            nearbyWayFocus = focus,
        )
        val overlay = buildRouteTileOverlay(
            routeModel = fixture.routeModel,
            runtimePack = runtimePack,
            config = DefaultTileContextConfig,
        )
        val bundle = RouteTileOverlayBundle(runtimePack = runtimePack, overlay = overlay)
        val overlayNearbyWays = queryRouteTileOverlayNearbyWays(
            routeModel = fixture.routeModel,
            bundle = bundle,
            focus = focus,
            config = DefaultTileContextConfig,
        )

        assertNearbyWaysEquivalent(directContext.nearbyWays, overlayNearbyWays, tolerance = 0.2)
    }

    @Test
    fun focusedRealDownloadedRuntimePackQueryMatchesFocusedDirectRouteContext() {
        val fixture = loadRouteMapInfoFixture()
        val runtimePack = compileTileRuntimePack(fixture.sourcePack)
        val focusPoint = fixture.geoPoints[6_854]
        val focus = MapInfoFocus(
            centerGeoPoint = focusPoint,
            windowWidthMeters = 1_000.0,
            projectedBounds = nearbyWayFocusBounds(
                routeModel = fixture.routeModel,
                focusGeoPoint = focusPoint,
                focusWindowWidthMeters = 1_000.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ) ?: fixture.routeModel.bounds,
        )

        val directContext = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(fixture.sourcePack),
            config = DefaultTileContextConfig,
            nearbyWayFocus = focus,
        )
        val focusHintEdgeIndexes = routeEdgeIndexesIntersectingBounds(
            model = fixture.routeModel,
            bounds = focus.projectedBounds,
        )
        val runtimeNearbyWays = queryTileRuntimeNearbyWays(
            routeModel = fixture.routeModel,
            runtimePack = runtimePack,
            focus = focus,
            focusHintEdgeIndexes = focusHintEdgeIndexes,
            config = DefaultTileContextConfig,
        )

        assertNearbyWaysEquivalent(directContext.nearbyWays, runtimeNearbyWays, tolerance = 0.2)
    }
}

private fun assertRouteContextsEquivalent(
    expected: RouteContext,
    actual: RouteContext,
) {
    assertEquals(expected.pois.map(RoutePoi::featureId), actual.pois.map(RoutePoi::featureId))
    expected.pois.zip(actual.pois).forEach { (expectedPoi, actualPoi) ->
        assertEquals(expectedPoi.kind, actualPoi.kind)
        assertEquals(expectedPoi.name, actualPoi.name)
        assertEquals(expectedPoi.geoPoint.lat, actualPoi.geoPoint.lat, 1e-4)
        assertEquals(expectedPoi.geoPoint.lon, actualPoi.geoPoint.lon, 1e-4)
        assertEquals(expectedPoi.projectedPoint.x, actualPoi.projectedPoint.x, 0.05)
        assertEquals(expectedPoi.projectedPoint.y, actualPoi.projectedPoint.y, 0.05)
    }

    assertEquals(
        expected.nearbyWays.map(RouteNearbyWaySnippet::featureId),
        actual.nearbyWays.map(RouteNearbyWaySnippet::featureId),
    )
    expected.nearbyWays.zip(actual.nearbyWays).forEach { (expectedWay, actualWay) ->
        assertEquals(expectedWay.points.size, actualWay.points.size)
        expectedWay.points.zip(actualWay.points).forEach { (expectedPoint, actualPoint) ->
            assertEquals(expectedPoint.x, actualPoint.x, 0.2)
            assertEquals(expectedPoint.y, actualPoint.y, 0.2)
        }
        assertEquals(expectedWay.bounds.minX, actualWay.bounds.minX, 0.2)
        assertEquals(expectedWay.bounds.maxX, actualWay.bounds.maxX, 0.2)
        assertEquals(expectedWay.bounds.minY, actualWay.bounds.minY, 0.2)
        assertEquals(expectedWay.bounds.maxY, actualWay.bounds.maxY, 0.2)
    }
}

private fun assertNearbyWaysEquivalent(
    expected: List<RouteNearbyWaySnippet>,
    actual: List<RouteNearbyWaySnippet>,
    tolerance: Double,
) {
    assertEquals(
        expected.map(RouteNearbyWaySnippet::featureId),
        actual.map(RouteNearbyWaySnippet::featureId),
    )
    expected.zip(actual).forEach { (expectedWay, actualWay) ->
        assertEquals(expectedWay.points.size, actualWay.points.size)
        expectedWay.points.zip(actualWay.points).forEach { (expectedPoint, actualPoint) ->
            assertEquals(expectedPoint.x, actualPoint.x, tolerance)
            assertEquals(expectedPoint.y, actualPoint.y, tolerance)
        }
    }
}
