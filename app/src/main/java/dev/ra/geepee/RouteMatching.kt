package dev.ra.geepee

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

internal data class RouteMatcherConfig(
    val observationWindowSize: Int = 4,
    val maxCandidatesPerFix: Int = 12,
    val minSigmaMeters: Double = 8.0,
    val backwardAllowanceMeters: Double = 10.0,
    val emissionWeight: Double = 1.0,
    val transitionWeight: Double = 1.0,
    val reversePenalty: Double = 6.0,
    val reversePenaltyScaleMeters: Double = 18.0,
    val headingPenaltyWeight: Double = 1.4,
    val preliminaryContinuityScaleMeters: Double = 60.0,
    val minHeadingSpeedMetersPerSecond: Float = 1.5f,
    val maxBearingAccuracyDegrees: Float = 30f,
    val minTransitionDtSeconds: Double = 0.5,
    val baseTransitionToleranceMeters: Double = 6.0,
    val transitionToleranceAccuracyMultiplier: Double = 1.5,
    val transitionPenaltyOffsetMeters: Double = 12.0,
)

private data class RouteMatchObservation(
    val fix: LocationFix,
    val projectedFix: ProjectedPoint,
    val candidates: List<RouteAnalysis>,
)

private data class RouteMatcherState(
    val nearestEdgeIndex: Int?,
    val routeMeters: Double?,
)

internal class RouteMatcher(
    private val routeModel: RouteModel,
    private val config: RouteMatcherConfig = RouteMatcherConfig(),
) {
    private val observations = ArrayDeque<RouteMatchObservation>()
    private var state = RouteMatcherState(nearestEdgeIndex = null, routeMeters = null)

    fun reset() {
        observations.clear()
        state = RouteMatcherState(nearestEdgeIndex = null, routeMeters = null)
    }

    fun match(fix: LocationFix): RouteAnalysis {
        val lastTimestamp = observations.lastOrNull()?.fix?.timestampMillis
        if (lastTimestamp != null && fix.timestampMillis <= lastTimestamp) {
            reset()
        }

        val projectedFix = projectLocationFix(routeModel, fix)
        val candidates = trimCandidates(
            candidates = collectRouteCandidates(
                model = routeModel,
                projectedFix = projectedFix,
                previousNearestEdgeIndex = state.nearestEdgeIndex,
            ),
            fix = fix,
            previousRouteMeters = state.routeMeters,
        )

        if (candidates.isEmpty()) {
            return emptyRouteAnalysis(projectedFix)
        }

        observations += RouteMatchObservation(
            fix = fix,
            projectedFix = projectedFix,
            candidates = candidates,
        )
        while (observations.size > config.observationWindowSize) {
            observations.removeFirst()
        }

        val matched = solveBestCurrentMatch(observations.toList()) ?: candidates.minBy { it.offRouteMeters }
        state = RouteMatcherState(
            nearestEdgeIndex = matched.nearestEdgeIndex.takeIf { it >= 0 },
            routeMeters = matched.routeMeters,
        )
        return matched.withProgress(routeModel, fix)
    }

    private fun trimCandidates(
        candidates: List<RouteAnalysis>,
        fix: LocationFix,
        previousRouteMeters: Double?,
    ): List<RouteAnalysis> {
        if (candidates.size <= config.maxCandidatesPerFix) {
            return candidates
        }

        return candidates
            .sortedBy { candidate ->
                preliminaryCandidateScore(candidate, fix, previousRouteMeters)
            }
            .take(config.maxCandidatesPerFix)
    }

    private fun preliminaryCandidateScore(
        candidate: RouteAnalysis,
        fix: LocationFix,
        previousRouteMeters: Double?,
    ): Double {
        val emission = (candidate.offRouteMeters / effectiveSigmaMeters(fix)) * config.emissionWeight
        val continuity = if (previousRouteMeters == null) {
            0.0
        } else {
            abs(candidate.routeMeters - previousRouteMeters) / config.preliminaryContinuityScaleMeters
        }
        return emission + continuity
    }

    private fun solveBestCurrentMatch(observations: List<RouteMatchObservation>): RouteAnalysis? {
        if (observations.isEmpty()) {
            return null
        }

        var previousScores = DoubleArray(observations.first().candidates.size) { candidateIndex ->
            candidateCost(observations.first(), observations.first().candidates[candidateIndex])
        }

        for (observationIndex in 1 until observations.size) {
            val previousObservation = observations[observationIndex - 1]
            val currentObservation = observations[observationIndex]
            val currentScores = DoubleArray(currentObservation.candidates.size) { Double.POSITIVE_INFINITY }

            currentObservation.candidates.forEachIndexed { currentIndex, currentCandidate ->
                val currentCost = candidateCost(currentObservation, currentCandidate)
                previousObservation.candidates.forEachIndexed { previousIndex, previousCandidate ->
                    val score = previousScores[previousIndex] +
                        transitionCost(
                            previousObservation = previousObservation,
                            previousCandidate = previousCandidate,
                            currentObservation = currentObservation,
                            currentCandidate = currentCandidate,
                        ) +
                        currentCost
                    if (score < currentScores[currentIndex]) {
                        currentScores[currentIndex] = score
                    }
                }
            }

            previousScores = currentScores
        }

        val finalObservation = observations.last()
        val bestIndex = previousScores.indices.minByOrNull { previousScores[it] } ?: return null
        return finalObservation.candidates[bestIndex]
    }

    private fun candidateCost(
        observation: RouteMatchObservation,
        candidate: RouteAnalysis,
    ): Double {
        val sigma = effectiveSigmaMeters(observation.fix)
        val emission = (candidate.offRouteMeters * candidate.offRouteMeters) / (2.0 * sigma * sigma)
        return emission * config.emissionWeight + headingPenalty(observation.fix, candidate)
    }

    private fun transitionCost(
        previousObservation: RouteMatchObservation,
        previousCandidate: RouteAnalysis,
        currentObservation: RouteMatchObservation,
        currentCandidate: RouteAnalysis,
    ): Double {
        val routeDelta = currentCandidate.routeMeters - previousCandidate.routeMeters
        val dtSeconds = max(
            config.minTransitionDtSeconds,
            (currentObservation.fix.timestampMillis - previousObservation.fix.timestampMillis).toDouble() / 1_000.0,
        )
        val observedTravelMeters = hypot(
            currentObservation.projectedFix.x - previousObservation.projectedFix.x,
            currentObservation.projectedFix.y - previousObservation.projectedFix.y,
        )
        val speedTravelMeters = listOfNotNull(
            previousObservation.fix.speedMetersPerSecond?.toDouble(),
            currentObservation.fix.speedMetersPerSecond?.toDouble(),
        ).averageOrNull()?.times(dtSeconds)
        val expectedTravelMeters = max(observedTravelMeters, speedTravelMeters ?: 0.0)
        val toleranceMeters = max(
            config.baseTransitionToleranceMeters,
            max(
                previousObservation.fix.accuracyMeters?.toDouble() ?: 0.0,
                currentObservation.fix.accuracyMeters?.toDouble() ?: 0.0,
            ) * config.transitionToleranceAccuracyMultiplier,
        )
        val distancePenalty = abs(routeDelta - expectedTravelMeters) /
            (toleranceMeters + config.transitionPenaltyOffsetMeters)
        val reversePenalty = if (routeDelta < -config.backwardAllowanceMeters) {
            config.reversePenalty + abs(routeDelta) / config.reversePenaltyScaleMeters
        } else {
            0.0
        }
        return distancePenalty * config.transitionWeight + reversePenalty
    }

    private fun headingPenalty(
        fix: LocationFix,
        candidate: RouteAnalysis,
    ): Double {
        if (fix.headingDegrees == null || fix.speedMetersPerSecond == null) {
            return 0.0
        }
        if (fix.speedMetersPerSecond < config.minHeadingSpeedMetersPerSecond) {
            return 0.0
        }
        if (fix.bearingAccuracyDegrees != null && fix.bearingAccuracyDegrees > config.maxBearingAccuracyDegrees) {
            return 0.0
        }

        val routeHeading = normalizeHeadingDegrees(
            Math.toDegrees(atan2(candidate.routeTangentX, candidate.routeTangentY)),
        )
        val headingDelta = abs(normalizeSignedHeadingDegrees(routeHeading - fix.headingDegrees))
        return (headingDelta / 90.0) * config.headingPenaltyWeight
    }

    private fun effectiveSigmaMeters(fix: LocationFix): Double {
        return max(config.minSigmaMeters, fix.accuracyMeters?.toDouble() ?: config.minSigmaMeters)
    }
}

private fun RouteAnalysis.withProgress(
    routeModel: RouteModel,
    fix: LocationFix,
): RouteAnalysis {
    return copy(
        progressMeters = routeMeters,
        remainingMeters = max(0.0, routeModel.totalLengthMeters - routeMeters),
        progressRatio = if (routeModel.totalLengthMeters > 0.0) {
            routeMeters / routeModel.totalLengthMeters
        } else {
            0.0
        },
        accuracyMeters = fix.accuracyMeters,
    )
}

private fun List<Double>.averageOrNull(): Double? {
    if (isEmpty()) {
        return null
    }
    return sum() / size
}
