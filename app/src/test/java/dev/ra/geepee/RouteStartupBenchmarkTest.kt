package dev.ra.geepee

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class RouteStartupBenchmarkTest {
    @Test
    fun benchmarkTiszaRouteApplyPath() {
        requireBenchmarkOptIn()
        val route = loadTiszaRouteModelForStartupBench()
        val onRouteFix = LocationFix(
            lat = 48.128297,
            lon = 22.540450,
            accuracyMeters = 5f,
            headingDegrees = 90f,
            speedMetersPerSecond = 4f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 10f,
        )
        val farFix = LocationFix(
            lat = -1.286389,
            lon = 36.817223,
            accuracyMeters = 12f,
            headingDegrees = 135f,
            speedMetersPerSecond = 0f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 20f,
        )

        val warmupRuntime = RouteRuntimeState().also { runtime ->
            runtime.acceptFix(onRouteFix, sessionActive = true, batterySaverEnabled = true)
            runtime.applyRoute(route)
        }
        check(warmupRuntime.currentAnalysis != null)

        val applyWithoutFixNanos = startupBenchmarkNanos(iterations = 30) {
            val runtime = RouteRuntimeState()
            runtime.applyRoute(route)
        }
        val applyWithOnRouteFixNanos = startupBenchmarkNanos(iterations = 12) {
            val runtime = RouteRuntimeState()
            runtime.acceptFix(onRouteFix, sessionActive = true, batterySaverEnabled = true)
            runtime.applyRoute(route)
        }
        val applyWithFarFixNanos = startupBenchmarkNanos(iterations = 12) {
            val runtime = RouteRuntimeState()
            runtime.acceptFix(farFix, sessionActive = true, batterySaverEnabled = true)
            runtime.applyRoute(route)
        }

        println(
            buildString {
                appendLine("STARTUP_BENCH route_points=${route.pointCount} route_edges=${route.edges.size}")
                appendLine("STARTUP_BENCH apply_without_fix_ms=${startupFormatNanosMillis(applyWithoutFixNanos)}")
                appendLine("STARTUP_BENCH apply_with_on_route_fix_ms=${startupFormatNanosMillis(applyWithOnRouteFixNanos)}")
                appendLine("STARTUP_BENCH apply_with_far_fix_ms=${startupFormatNanosMillis(applyWithFarFixNanos)}")
            },
        )
    }
}

private fun startupBenchmarkNanos(
    iterations: Int,
    block: () -> Unit,
): Long {
    val startedAt = System.nanoTime()
    repeat(iterations) {
        block()
    }
    return (System.nanoTime() - startedAt) / iterations.toLong()
}

private fun startupFormatNanosMillis(nanos: Long): String {
    return "%.3f".format(nanos / 1_000_000.0)
}

private fun loadTiszaRouteModelForStartupBench(): RouteModel {
    val routeFile = resolveRepoFileForStartupBench("repos/geepee/routes/unneplos-tisza-ride.gpx")
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
    return buildRouteModel(listOf(geoPoints))
}

private fun resolveRepoFileForStartupBench(relativePath: String): File {
    val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
        "Expected a working directory for repo fixture lookup."
    }
    var current: File? = File(workingDirectory).absoluteFile
    repeat(8) {
        val candidate = current?.resolve(relativePath)
        if (candidate?.isFile == true) {
            return candidate
        }
        current = current?.parentFile
    }
    error("Could not locate repo file: $relativePath from $workingDirectory")
}
