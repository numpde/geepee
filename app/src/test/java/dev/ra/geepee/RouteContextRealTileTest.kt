package dev.ra.geepee

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
        val focus = buildRouteMapInfoFocus(
            routeModel = fixture.routeModel,
            focusPoint = focusPoint,
            widthMeters = 1_000.0,
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
    return buildRouteFixtureLocationFix(
        geoPoints = geoPoints,
        index = index,
        timestampMillis = timestampMillis,
    )
}
