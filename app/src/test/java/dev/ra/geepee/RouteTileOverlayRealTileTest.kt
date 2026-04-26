package dev.ra.geepee

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTileOverlayRealTileTest {
    @Test
    fun realDownloadedTileOverlayMatchesDirectRouteContext() {
        val fixture = loadOverlayTiszaFixture()
        val sourcePack = loadOverlayTileFixture("tile-context/10-571-356-local.json")
        val runtimePack = compileTileRuntimePack(sourcePack)

        val directContext = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(sourcePack),
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
        val fixture = loadOverlayTiszaFixture()
        val sourcePack = loadOverlayTileFixture("tile-context/10-571-356-local.json")
        val runtimePack = compileTileRuntimePack(sourcePack)
        val focusPoint = fixture.geoPoints[6_854]

        val directContext = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(sourcePack),
            config = DefaultTileContextConfig,
            nearbyWayFocusGeoPoint = focusPoint,
            nearbyWayFocusWindowWidthMeters = 1_000.0,
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
            focusGeoPoint = focusPoint,
            focusWindowWidthMeters = 1_000.0,
            config = DefaultTileContextConfig,
        )

        assertNearbyWaysEquivalent(directContext.nearbyWays, overlayNearbyWays, tolerance = 0.2)
    }

    @Test
    fun focusedRealDownloadedRuntimePackQueryMatchesFocusedDirectRouteContext() {
        val fixture = loadOverlayTiszaFixture()
        val sourcePack = loadOverlayTileFixture("tile-context/10-571-356-local.json")
        val runtimePack = compileTileRuntimePack(sourcePack)
        val focusPoint = fixture.geoPoints[6_854]

        val directContext = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(sourcePack),
            config = DefaultTileContextConfig,
            nearbyWayFocusGeoPoint = focusPoint,
            nearbyWayFocusWindowWidthMeters = 1_000.0,
        )
        val focusNearestEdgeIndex = collectRouteCandidates(
            model = fixture.routeModel,
            projectedFix = projectGeoPointToRouteProjection(focusPoint, fixture.routeModel.projection),
        ).minByOrNull(RouteAnalysis::offRouteMeters)?.nearestEdgeIndex ?: -1
        val runtimeNearbyWays = queryTileRuntimeNearbyWays(
            routeModel = fixture.routeModel,
            runtimePack = runtimePack,
            focusGeoPoint = focusPoint,
            focusNearestEdgeIndex = focusNearestEdgeIndex,
            focusWindowWidthMeters = 1_000.0,
            config = DefaultTileContextConfig,
        )

        assertNearbyWaysEquivalent(directContext.nearbyWays, runtimeNearbyWays, tolerance = 0.2)
    }

    private fun loadOverlayTileFixture(path: String): TileContextPack {
        val resource = requireNotNull(javaClass.classLoader?.getResource("dev/ra/geepee/$path")) {
            "Missing tile fixture resource: $path"
        }
        return tileContextPackFromJson(File(resource.toURI()).readText())
    }
}

private data class OverlayTiszaFixture(
    val geoPoints: List<GeoPoint>,
    val routeModel: RouteModel,
)

private fun loadOverlayTiszaFixture(): OverlayTiszaFixture {
    val routeFile = resolveOverlayRepoFile("routes/unneplos-tisza-ride.gpx")
    val document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(routeFile)
    val trackPoints = document.getElementsByTagNameNS("*", "trkpt")
    val geoPoints = buildList(trackPoints.length) {
        for (index in 0 until trackPoints.length) {
            val node = trackPoints.item(index)
            val attributes = node.attributes
            add(
                GeoPoint(
                    lat = attributes.getNamedItem("lat").nodeValue.toDouble(),
                    lon = attributes.getNamedItem("lon").nodeValue.toDouble(),
                ),
            )
        }
    }
    return OverlayTiszaFixture(
        geoPoints = geoPoints,
        routeModel = buildRouteModel(listOf(geoPoints)),
    )
}

private fun resolveOverlayRepoFile(relativePath: String): File {
    val cwd = File(requireNotNull(System.getProperty("user.dir")))
    var current: File? = cwd.absoluteFile
    repeat(8) {
        val candidate = current?.resolve(relativePath)
        if (candidate?.isFile == true) {
            return candidate
        }
        current = current?.parentFile
    }
    error("Could not locate repo file: $relativePath")
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
