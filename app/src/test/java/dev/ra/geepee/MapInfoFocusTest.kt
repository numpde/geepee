package dev.ra.geepee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInfoFocusTest {
    private val matchedPoint = GeoPoint(-1.24211, 36.83407)
    private val defaultBounds = Bounds(-100.0, 100.0, -80.0, 80.0)

    @Test
    fun mapInfoFocusChanged_returnsFalseWhenResolvedBoundsAreEqual() {
        val previous = MapInfoFocus(
            centerGeoPoint = matchedPoint,
            windowWidthMeters = 200.0,
            projectedBounds = defaultBounds,
        )
        val current = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24212, 36.83408),
            windowWidthMeters = 202.0,
            projectedBounds = defaultBounds,
        )

        assertFalse(mapInfoFocusChanged(previous, current))
    }

    @Test
    fun mapInfoFocusChanged_returnsTrueWhenResolvedBoundsChange() {
        val previous = MapInfoFocus(
            centerGeoPoint = matchedPoint,
            windowWidthMeters = 200.0,
            projectedBounds = defaultBounds,
        )
        val current = previous.copy(
            projectedBounds = Bounds(-120.0, 80.0, -80.0, 80.0),
        )

        assertTrue(mapInfoFocusChanged(previous, current))
    }

    @Test
    fun mapInfoFocusChanged_returnsTrueWhenWindowWidthChangesEnough() {
        val previous = MapInfoFocus(
            centerGeoPoint = matchedPoint,
            windowWidthMeters = 200.0,
            projectedBounds = defaultBounds,
        )
        val current = previous.copy(
            windowWidthMeters = 240.0,
        )

        assertTrue(mapInfoFocusChanged(previous, current))
    }

    @Test
    fun isDefaultMapInfoFocus_returnsTrueForMatchedPointAtDefaultWidth() {
        val focus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24212, 36.83408),
            windowWidthMeters = 200.0,
            projectedBounds = defaultBounds,
        )

        assertTrue(
            isDefaultMapInfoFocus(
                focus = focus,
                defaultWindowWidthMeters = 200.0,
                matchedGeoPoint = matchedPoint,
            ),
        )
    }

    @Test
    fun isDefaultMapInfoFocus_returnsFalseWhenWidthOrLocationDrift() {
        val shiftedWidthFocus = MapInfoFocus(
            centerGeoPoint = matchedPoint,
            windowWidthMeters = 260.0,
            projectedBounds = Bounds(-130.0, 130.0, -80.0, 80.0),
        )
        assertFalse(
            isDefaultMapInfoFocus(
                focus = shiftedWidthFocus,
                defaultWindowWidthMeters = 200.0,
                matchedGeoPoint = matchedPoint,
            ),
        )

        val shiftedLocationFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24250, 36.83450),
            windowWidthMeters = 200.0,
            projectedBounds = defaultBounds,
        )
        assertFalse(
            isDefaultMapInfoFocus(
                focus = shiftedLocationFocus,
                defaultWindowWidthMeters = 200.0,
                matchedGeoPoint = matchedPoint,
            ),
        )
    }

    @Test
    fun shouldAcceptMapInfoFocusUpdate_rejectsInitialDefaultFocus() {
        val focus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24212, 36.83408),
            windowWidthMeters = 200.0,
            projectedBounds = defaultBounds,
        )

        assertFalse(
            shouldAcceptMapInfoFocusUpdate(
                previous = null,
                current = focus,
                defaultWindowWidthMeters = 200.0,
                matchedGeoPoint = matchedPoint,
            ),
        )
    }

    @Test
    fun shouldAcceptMapInfoFocusUpdate_acceptsChangedNonDefaultFocus() {
        val previous = MapInfoFocus(
            centerGeoPoint = matchedPoint,
            windowWidthMeters = 200.0,
            projectedBounds = defaultBounds,
        )
        val current = previous.copy(
            projectedBounds = Bounds(-120.0, 80.0, -80.0, 80.0),
        )

        assertTrue(
            shouldAcceptMapInfoFocusUpdate(
                previous = previous,
                current = current,
                defaultWindowWidthMeters = 200.0,
                matchedGeoPoint = matchedPoint,
            ),
        )
    }

    @Test
    fun nearbyWayMapInfoFocusOrDefault_preservesExplicitFocus() {
        val explicitFocus = MapInfoFocus(
            centerGeoPoint = GeoPoint(-1.24250, 36.83450),
            windowWidthMeters = 260.0,
            projectedBounds = Bounds(-130.0, 130.0, -90.0, 90.0),
        )
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(-1.24300, 36.83300),
                    GeoPoint(-1.24100, 36.83500),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = matchedPoint.lat,
                lon = matchedPoint.lon,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        assertEquals(
            explicitFocus,
            nearbyWayMapInfoFocusOrDefault(
                explicitFocus = explicitFocus,
                routeModel = routeModel,
                analysis = analysis,
                config = DefaultTileContextConfig,
                defaultWindowWidthMeters = 200.0,
            ),
        )
    }

    @Test
    fun nearbyWayMapInfoFocusOrDefault_buildsDefaultMatchedFocus() {
        val routeModel = buildRouteModel(
            rawSegments = listOf(
                listOf(
                    GeoPoint(-1.24300, 36.83300),
                    GeoPoint(-1.24100, 36.83500),
                ),
            ),
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = matchedPoint.lat,
                lon = matchedPoint.lon,
                accuracyMeters = 4f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 0L,
            ),
        )

        val focus = nearbyWayMapInfoFocusOrDefault(
            explicitFocus = null,
            routeModel = routeModel,
            analysis = analysis,
            config = DefaultTileContextConfig,
            defaultWindowWidthMeters = 200.0,
        )

        assertEquals(analysis.nearestGeoPoint, focus.centerGeoPoint)
        assertEquals(200.0, focus.windowWidthMeters, 0.0)
        assertEquals(
            nearbyWayFocusBounds(
                routeModel = routeModel,
                focusGeoPoint = analysis.nearestGeoPoint,
                focusWindowWidthMeters = 200.0,
                haloMeters = DefaultTileContextConfig.wayHaloMeters,
                continuationMeters = DefaultTileContextConfig.nearbyWayContinuationMeters,
            ),
            focus.projectedBounds,
        )
    }

    @Test
    fun expandedNearbyWayMapInfoBounds_addsHaloAndContinuationPadding() {
        val focus = MapInfoFocus(
            centerGeoPoint = matchedPoint,
            windowWidthMeters = 200.0,
            projectedBounds = Bounds(-100.0, 100.0, -80.0, 80.0),
        )

        assertEquals(
            expandBounds(
                focus.projectedBounds,
                DefaultTileContextConfig.wayHaloMeters + DefaultTileContextConfig.nearbyWayContinuationMeters,
            ),
            expandedNearbyWayMapInfoBounds(
                focus = focus,
                config = DefaultTileContextConfig,
            ),
        )
    }
}
