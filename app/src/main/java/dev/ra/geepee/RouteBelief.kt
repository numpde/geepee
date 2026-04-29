package dev.ra.geepee

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

internal data class RouteBeliefConfig(
    val minObservationSigmaMeters: Double = 8.0,
    val missingAccuracySigmaMeters: Double = 24.0,
    val androidAccuracy68ToSigmaDivisor: Double = 1.51,
    val accuracyScale: Double = 1.0,
    val offRouteObservationScaleMeters: Double = 120.0,
    val onRouteProbabilityThreshold: Double = 0.80,
    val offRouteProbabilityThreshold: Double = 0.80,
) {
    init {
        require(minObservationSigmaMeters > 0.0) { "Minimum observation sigma must be positive." }
        require(missingAccuracySigmaMeters > 0.0) { "Missing-accuracy sigma must be positive." }
        require(androidAccuracy68ToSigmaDivisor > 0.0) { "Accuracy divisor must be positive." }
        require(accuracyScale > 0.0) { "Accuracy scale must be positive." }
        require(offRouteObservationScaleMeters > 0.0) { "Off-route observation scale must be positive." }
        require(onRouteProbabilityThreshold in 0.0..1.0) { "On-route threshold must be a probability." }
        require(offRouteProbabilityThreshold in 0.0..1.0) { "Off-route threshold must be a probability." }
    }
}

internal enum class RouteAdherence {
    OnRoute,
    Uncertain,
    OffRoute,
}

internal data class RouteCandidateBelief(
    val analysis: RouteAnalysis,
    val posteriorProbability: Double,
    val routeConditionalProbability: Double,
    val isPrimary: Boolean,
)

internal data class RouteBelief(
    val fix: LocationFix,
    val sigmaMeters: Double,
    val routeProbability: Double,
    val offRouteProbability: Double,
    val adherence: RouteAdherence,
    val primaryRouteAnalysis: RouteAnalysis?,
    val routeCandidates: List<RouteCandidateBelief>,
)

internal fun observationSigmaMeters(
    fix: LocationFix,
    config: RouteBeliefConfig = RouteBeliefConfig(),
): Double {
    val accuracySigma = fix.accuracyMeters
        ?.takeIf { it > 0f }
        ?.toDouble()
        ?.let { accuracyMeters ->
            accuracyMeters / config.androidAccuracy68ToSigmaDivisor * config.accuracyScale
        }
        ?: config.missingAccuracySigmaMeters
    return max(config.minObservationSigmaMeters, accuracySigma)
}

internal fun routeObservationCost(
    offRouteMeters: Double,
    sigmaMeters: Double,
): Double {
    require(sigmaMeters > 0.0) { "Observation sigma must be positive." }
    val normalizedDistance = offRouteMeters / sigmaMeters
    return 0.5 * normalizedDistance * normalizedDistance + ln(sigmaMeters)
}

internal fun offRouteObservationCost(
    sigmaMeters: Double,
    config: RouteBeliefConfig = RouteBeliefConfig(),
): Double {
    require(sigmaMeters > 0.0) { "Observation sigma must be positive." }
    return ln(max(config.offRouteObservationScaleMeters, sigmaMeters))
}

internal fun classifyRouteAdherence(
    routeProbability: Double,
    offRouteProbability: Double,
    config: RouteBeliefConfig = RouteBeliefConfig(),
): RouteAdherence {
    return when {
        routeProbability >= config.onRouteProbabilityThreshold -> RouteAdherence.OnRoute
        offRouteProbability >= config.offRouteProbabilityThreshold -> RouteAdherence.OffRoute
        else -> RouteAdherence.Uncertain
    }
}

internal fun normalizeLogProbabilities(logProbabilities: List<Double>): List<Double> {
    if (logProbabilities.isEmpty()) {
        return emptyList()
    }
    val normalizer = logSumExp(logProbabilities)
    return logProbabilities.map { logProbability ->
        exp(logProbability - normalizer)
    }
}

internal fun logSumExp(values: List<Double>): Double {
    require(values.isNotEmpty()) { "Cannot normalize an empty probability set." }
    val finiteValues = values.filter(Double::isFinite)
    if (finiteValues.isEmpty()) {
        return Double.NEGATIVE_INFINITY
    }
    val maxValue = finiteValues.max()
    return maxValue + ln(finiteValues.sumOf { value -> exp(value - maxValue) })
}
