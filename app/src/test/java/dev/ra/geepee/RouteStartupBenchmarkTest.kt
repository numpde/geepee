package dev.ra.geepee

import org.junit.Test

class RouteStartupBenchmarkTest {
    @Test
    fun benchmarkTiszaRouteApplyPath() {
        requireBenchmarkOptIn()
        val route = loadRouteMapInfoRouteModel()
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

        val applyWithoutFixNanos = benchmarkNanos(iterations = 30) {
            val runtime = RouteRuntimeState()
            runtime.applyRoute(route)
        }
        val applyWithOnRouteFixNanos = benchmarkNanos(iterations = 12) {
            val runtime = RouteRuntimeState()
            runtime.acceptFix(onRouteFix, sessionActive = true, batterySaverEnabled = true)
            runtime.applyRoute(route)
        }
        val applyWithFarFixNanos = benchmarkNanos(iterations = 12) {
            val runtime = RouteRuntimeState()
            runtime.acceptFix(farFix, sessionActive = true, batterySaverEnabled = true)
            runtime.applyRoute(route)
        }

        println(
            buildString {
                appendLine("STARTUP_BENCH route_points=${route.pointCount} route_edges=${route.edges.size}")
                appendLine("STARTUP_BENCH apply_without_fix_ms=${formatBenchmarkMillis(applyWithoutFixNanos)}")
                appendLine("STARTUP_BENCH apply_with_on_route_fix_ms=${formatBenchmarkMillis(applyWithOnRouteFixNanos)}")
                appendLine("STARTUP_BENCH apply_with_far_fix_ms=${formatBenchmarkMillis(applyWithFarFixNanos)}")
            },
        )
    }
}
