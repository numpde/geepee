package dev.ra.geepee

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteContextRealTileTest {
    @Test
    fun realDownloadedTileProducesNearbyWaysForTiszaRoute() {
        val fixture = loadRouteMapInfoFixture()

        val context = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(fixture.sourcePack),
            config = DefaultTileContextConfig,
        )

        assertFalse("Expected real cached tile to contribute nearby ways", context.nearbyWays.isEmpty())
        assertTrue(
            "Expected at least one nearby-way snippet with visible geometry",
            context.nearbyWays.any { it.points.size >= 2 },
        )
    }

    @Test
    fun realDownloadedTileCanProjectNearbyWaysIntoVisibleWindowAtInteriorTiszaPoint() {
        val fixture = loadRouteMapInfoFixture()
        val context = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(fixture.sourcePack),
            config = DefaultTileContextConfig,
        )
        val analysis = fixture.fixAt(
            index = 6_854,
            timestampMillis = 1_000L,
        ).let { fix ->
            analyzeLocationAgainstModel(
                model = fixture.routeModel,
                fix = fix,
            )
        }

        val render = buildRouteRenderModel(
            routeModel = fixture.routeModel,
            analysis = analysis,
            nearbyWays = context.nearbyWays,
            localWindowWidthMeters = 350.0,
            canvasWidth = 1080f,
            canvasHeight = 1920f,
        )

        assertTrue(
            "Expected at least one nearby way polyline to be visible at the interior cached section",
            render.nearbyWayPolylines.isNotEmpty(),
        )
    }

    @Test
    fun focusedNearbyWayBuildKeepsVisibleSnippetsAtInteriorTiszaPoint() {
        val fixture = loadRouteMapInfoFixture()
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

        val nearbyWays = buildRouteNearbyWays(
            routeModel = fixture.routeModel,
            packs = listOf(fixture.sourcePack),
            config = DefaultTileContextConfig,
            focus = focus,
        )

        assertTrue(
            "Expected focused nearby-way build to keep local visible snippets",
            nearbyWays.isNotEmpty(),
        )
    }

}

private fun RouteMapInfoFixture.fixAt(
    index: Int,
    timestampMillis: Long,
): LocationFix {
    val point = geoPoints[index]
    val previousPoint = geoPoints[maxOf(0, index - 1)]
    val nextPoint = geoPoints[minOf(geoPoints.lastIndex, index + 1)]
    return LocationFix(
        lat = point.lat,
        lon = point.lon,
        accuracyMeters = 4f,
        headingDegrees = bearingDegreesForRealTileTest(previousPoint, nextPoint).toFloat(),
        speedMetersPerSecond = 4f,
        timestampMillis = timestampMillis,
        bearingAccuracyDegrees = 8f,
    )
}

private fun bearingDegreesForRealTileTest(
    start: GeoPoint,
    end: GeoPoint,
): Double {
    val startLat = start.lat * PI / 180.0
    val endLat = end.lat * PI / 180.0
    val deltaLon = (end.lon - start.lon) * PI / 180.0
    val y = sin(deltaLon) * cos(endLat)
    val x = cos(startLat) * sin(endLat) - sin(startLat) * cos(endLat) * cos(deltaLon)
    val bearing = Math.toDegrees(atan2(y, x))
    return (bearing + 360.0) % 360.0
}
