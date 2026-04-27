package dev.ra.geepee

import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteContextRealTileTest {
    @Test
    fun realDownloadedTileProducesNearbyWaysForTiszaRoute() {
        val fixture = loadTiszaFixture()
        val pack = loadTileFixture("tile-context/10-571-356-local.json")

        val context = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(pack),
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
        val fixture = loadTiszaFixture()
        val pack = loadTileFixture("tile-context/10-571-356-local.json")
        val context = buildRouteContext(
            routeModel = fixture.routeModel,
            packs = listOf(pack),
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
        val fixture = loadTiszaFixture()
        val pack = loadTileFixture("tile-context/10-571-356-local.json")
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
            packs = listOf(pack),
            config = DefaultTileContextConfig,
            focus = focus,
        )

        assertTrue(
            "Expected focused nearby-way build to keep local visible snippets",
            nearbyWays.isNotEmpty(),
        )
    }

    private fun loadTileFixture(path: String): TileContextPack {
        val resource = requireNotNull(javaClass.classLoader?.getResource("dev/ra/geepee/$path")) {
            "Missing tile fixture resource: $path"
        }
        return tileContextPackFromJson(File(resource.toURI()).readText())
    }
}

private data class TiszaFixture(
    val geoPoints: List<GeoPoint>,
    val routeModel: RouteModel,
)

private fun loadTiszaFixture(): TiszaFixture {
    val routeFile = resolveRepoFileForRealTileTest("routes/unneplos-tisza-ride.gpx")
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
    require(geoPoints.size >= 2) { "Expected at least two GPX points in the Tisza fixture." }
    return TiszaFixture(
        geoPoints = geoPoints,
        routeModel = buildRouteModel(listOf(geoPoints)),
    )
}

private fun TiszaFixture.fixAt(
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

private fun resolveRepoFileForRealTileTest(relativePath: String): File {
    val cwd = File(requireNotNull(System.getProperty("user.dir")) { "Missing user.dir system property" })
    val direct = File(cwd, relativePath)
    if (direct.exists()) {
        return direct
    }
    val homeRepos = File(
        requireNotNull(System.getProperty("user.home")) { "Missing user.home system property" },
        "repos/geepee/$relativePath",
    )
    if (homeRepos.exists()) {
        return homeRepos
    }
    error("Could not resolve repo file for real tile test: $relativePath")
}
