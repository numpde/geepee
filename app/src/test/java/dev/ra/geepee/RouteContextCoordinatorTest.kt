package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

        assertEquals(analysis.nearestGeoPoint, resolved.focus.centerGeoPoint)
        assertEquals(200.0, resolved.focus.windowWidthMeters, 0.0)
        assertEquals(analysis.nearestEdgeIndex, resolved.nearestEdgeIndex)
        assertTrue(
            resolved.localTileIds.contains(
                tileIdForGeoPoint(analysis.nearestGeoPoint, DefaultTileContextConfig.downloadZoom),
            ),
        )
        assertEquals(
            nearbyWayFocusBounds(
                routeModel = routeModel,
                focusGeoPoint = analysis.nearestGeoPoint,
                focusWindowWidthMeters = 200.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ),
            resolved.focus.projectedBounds,
        )
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
            projectedBounds = nearbyWayFocusBounds(
                routeModel = routeModel,
                focusGeoPoint = GeoPoint(0.9, 1.0),
                focusWindowWidthMeters = 150.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ) ?: routeModel.bounds,
        )

        val resolved = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = explicitFocus,
            config = DefaultTileContextConfig,
            defaultFocusWindowWidthMeters = 200.0,
        )

        assertEquals(explicitFocus.centerGeoPoint, resolved.focus.centerGeoPoint)
        assertEquals(explicitFocus.windowWidthMeters, resolved.focus.windowWidthMeters, 0.0)
        assertTrue(
            resolved.localTileIds.contains(
                tileIdForGeoPoint(explicitFocus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
            ),
        )
        assertEquals(1, resolved.nearestEdgeIndex)
    }

    @Test
    fun nearbyWayQueryCacheKey_isStableWithinFocusBucket() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.01),
                ),
            ),
        )
        val baseFocus = NearbyWayQueryFocus(
            focus = MapInfoFocus(
                centerGeoPoint = GeoPoint(0.0, 0.0020),
                windowWidthMeters = 100.0,
                projectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            ),
            nearestEdgeIndex = 0,
            localTileIds = emptySet(),
        )
        val nearbyFocus = baseFocus.copy(
            focus = baseFocus.focus.copy(
                centerGeoPoint = GeoPoint(0.0, 0.00204),
            ),
        )
        val revisions = listOf(
            NearbyWayLoadedTileRevision(
                tileId = tileIdForGeoPoint(baseFocus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                updatedAtMillis = 123L,
            ),
        )

        val baseKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = baseFocus,
            loadedTileRevisions = revisions,
        )
        val nearbyKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = nearbyFocus,
            loadedTileRevisions = revisions,
        )

        assertEquals(baseKey, nearbyKey)
    }

    @Test
    fun nearbyWayQueryCacheKey_changesWhenLoadedTileRevisionChanges() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.01),
                ),
            ),
        )
        val focus = NearbyWayQueryFocus(
            focus = MapInfoFocus(
                centerGeoPoint = GeoPoint(0.0, 0.0020),
                windowWidthMeters = 100.0,
                projectedBounds = Bounds(-50.0, 50.0, -50.0, 50.0),
            ),
            nearestEdgeIndex = 0,
            localTileIds = emptySet(),
        )

        val firstKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = focus,
            loadedTileRevisions = listOf(
                NearbyWayLoadedTileRevision(
                    tileId = tileIdForGeoPoint(focus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                    updatedAtMillis = 123L,
                ),
            ),
        )
        val secondKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = focus,
            loadedTileRevisions = listOf(
                NearbyWayLoadedTileRevision(
                    tileId = tileIdForGeoPoint(focus.focus.centerGeoPoint, DefaultTileContextConfig.downloadZoom),
                    updatedAtMillis = 456L,
                ),
            ),
        )

        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun resolveNearbyWayQueryFocus_usesViewportBoundsForLocalTileCoverage() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 0.5),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = 0.05,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )
        val projectedBounds = projectedBoundsForGeoBounds(
            bounds = GeoBounds(
                west = 0.0,
                south = -0.01,
                east = 0.4,
                north = 0.01,
            ),
            projection = routeModel.projection,
        )
        val explicitFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(0.0, 0.2),
            windowWidthMeters = 100.0,
            projectedBounds = projectedBounds,
        )

        val resolved = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = explicitFocus,
            config = DefaultTileContextConfig,
            defaultFocusWindowWidthMeters = 200.0,
        )

        assertEquals(projectedBounds, resolved.focus.projectedBounds)
        assertEquals(
            tilesIntersectingProjectedBounds(
                projection = routeModel.projection,
                bounds = expandBounds(
                    projectedBounds,
                    DefaultTileContextConfig.wayHaloMeters + DefaultTileContextConfig.nearbyWayContinuationMeters,
                ),
                zoom = DefaultTileContextConfig.downloadZoom,
            ).toSet(),
            resolved.localTileIds,
        )
    }
}
