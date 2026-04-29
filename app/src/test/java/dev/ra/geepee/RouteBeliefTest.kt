package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBeliefTest {
    @Test
    fun observationSigmaConvertsAndroidHorizontalRadiusToOneDimensionalSigma() {
        val sigma = observationSigmaMeters(
            fix = locationFix(accuracyMeters = 15.1f),
            config = RouteBeliefConfig(minObservationSigmaMeters = 1.0),
        )

        assertEquals(10.0, sigma, 0.001)
    }

    @Test
    fun observationSigmaUsesConfiguredFloorAndMissingAccuracyDefault() {
        assertEquals(
            8.0,
            observationSigmaMeters(
                fix = locationFix(accuracyMeters = 1f),
                config = RouteBeliefConfig(minObservationSigmaMeters = 8.0),
            ),
            0.001,
        )
        assertEquals(
            30.0,
            observationSigmaMeters(
                fix = locationFix(accuracyMeters = null),
                config = RouteBeliefConfig(missingAccuracySigmaMeters = 30.0),
            ),
            0.001,
        )
    }

    @Test
    fun routeObservationCostPenalizesAccurateFarFixesMoreThanInaccurateFarFixes() {
        val accurateFarCost = routeObservationCost(offRouteMeters = 60.0, sigmaMeters = 8.0)
        val inaccurateFarCost = routeObservationCost(offRouteMeters = 60.0, sigmaMeters = 80.0)

        assertTrue(accurateFarCost > inaccurateFarCost)
    }

    @Test
    fun offRouteObservationCostWidensWithPoorAccuracy() {
        val config = RouteBeliefConfig(offRouteObservationScaleMeters = 120.0)

        assertEquals(
            offRouteObservationCost(sigmaMeters = 8.0, config = config),
            offRouteObservationCost(sigmaMeters = 80.0, config = config),
            0.001,
        )
        assertTrue(
            offRouteObservationCost(sigmaMeters = 240.0, config = config) >
                offRouteObservationCost(sigmaMeters = 80.0, config = config),
        )
    }

    @Test
    fun logProbabilityNormalizationIsStableAndSumsToOne() {
        val probabilities = normalizeLogProbabilities(listOf(-1000.0, -1001.0, -1002.0))

        assertEquals(1.0, probabilities.sum(), 0.000001)
        assertTrue(probabilities[0] > probabilities[1])
        assertTrue(probabilities[1] > probabilities[2])
    }

    @Test
    fun routeAdherenceComesOnlyFromPosteriorThresholds() {
        val config = RouteBeliefConfig(
            onRouteProbabilityThreshold = 0.8,
            offRouteProbabilityThreshold = 0.8,
        )

        assertEquals(
            RouteAdherence.OnRoute,
            classifyRouteAdherence(0.81, 0.19, config),
        )
        assertEquals(
            RouteAdherence.OffRoute,
            classifyRouteAdherence(0.19, 0.81, config),
        )
        assertEquals(
            RouteAdherence.Uncertain,
            classifyRouteAdherence(0.5, 0.5, config),
        )
    }
}

private fun locationFix(
    accuracyMeters: Float?,
): LocationFix {
    return LocationFix(
        lat = 0.0,
        lon = 0.0,
        accuracyMeters = accuracyMeters,
        headingDegrees = null,
        speedMetersPerSecond = null,
        timestampMillis = 1_000L,
    )
}
