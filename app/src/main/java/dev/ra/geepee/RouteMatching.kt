package dev.ra.geepee

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

internal data class RouteMatcherConfig(
    val observationWindowSize: Int = 4,
    val maxCandidatesPerFix: Int = 12,
    val maxStateHypotheses: Int = 4,
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
    val continuityBreakDistanceMeters: Double = 24.0,
    val continuityBreakGapMeters: Double = 12.0,
    val continuityBreakNearestMeters: Double = 12.0,
    val continuityBreakAccuracyMultiplier: Double = 2.5,
    val hypothesisScoreMargin: Double = 1.2,
    val hypothesisRouteSeparationMeters: Double = 20.0,
)

private data class RouteMatchObservation(
    val fix: LocationFix,
    val projectedFix: ProjectedPoint,
    val candidates: List<RouteAnalysis>,
)

private data class RouteMatcherState(
    val hypotheses: List<RouteMatchHypothesis>,
)

private data class RouteMatchHypothesis(
    val nearestEdgeIndex: Int?,
    val routeMeters: Double,
    val score: Double,
)

private data class ScoredRouteCandidate(
    val analysis: RouteAnalysis,
    val score: Double,
)

internal class RouteMatcher(
    private val routeModel: RouteModel,
    private val config: RouteMatcherConfig = RouteMatcherConfig(),
) {
    private val observations = ArrayDeque<RouteMatchObservation>()
    private var state = RouteMatcherState(hypotheses = emptyList())

    fun reset() {
        observations.clear()
        state = RouteMatcherState(hypotheses = emptyList())
    }

    fun match(fix: LocationFix): RouteAnalysis {
        val lastTimestamp = observations.lastOrNull()?.fix?.timestampMillis
        if (lastTimestamp != null && fix.timestampMillis <= lastTimestamp) {
            reset()
        }

        val projectedFix = projectLocationFix(routeModel, fix)
        val allCandidates = collectRouteCandidates(
            model = routeModel,
            projectedFix = projectedFix,
            previousNearestEdgeIndexes = state.hypotheses.mapNotNull { hypothesis ->
                hypothesis.nearestEdgeIndex?.takeIf { it >= 0 }
            },
        )
        val nearestCandidate = allCandidates.minByOrNull { it.offRouteMeters }
        val candidates = trimCandidates(
            candidates = allCandidates,
            fix = fix,
            previousHypotheses = state.hypotheses,
            alwaysKeepNearest = nearestCandidate,
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

        val rankedCandidates = solveBestCurrentMatches(observations.toList())
        val matched = rankedCandidates.firstOrNull()?.analysis ?: candidates.minBy { it.offRouteMeters }
        val nearest = nearestCandidate ?: candidates.minBy { it.offRouteMeters }
        val finalRankedCandidates = if (shouldBreakContinuity(matched, nearest, fix)) {
            observations.clear()
            observations += RouteMatchObservation(
                fix = fix,
                projectedFix = projectedFix,
                candidates = candidates,
            )
            solveBestCurrentMatches(observations.toList())
                .ifEmpty { listOf(ScoredRouteCandidate(analysis = nearest, score = 0.0)) }
        } else {
            rankedCandidates.ifEmpty { listOf(ScoredRouteCandidate(analysis = matched, score = 0.0)) }
        }
        val finalMatch = finalRankedCandidates.first().analysis
        state = RouteMatcherState(
            hypotheses = selectStateHypotheses(finalRankedCandidates),
        )
        return finalMatch.withProgress(routeModel, fix)
    }

    private fun trimCandidates(
        candidates: List<RouteAnalysis>,
        fix: LocationFix,
        previousHypotheses: List<RouteMatchHypothesis>,
        alwaysKeepNearest: RouteAnalysis?,
    ): List<RouteAnalysis> {
        if (candidates.size <= config.maxCandidatesPerFix) {
            return candidates
        }

        val trimmed = candidates
            .sortedBy { candidate ->
                preliminaryCandidateScore(candidate, fix, previousHypotheses)
            }
            .take(config.maxCandidatesPerFix)
            .toMutableList()

        if (alwaysKeepNearest != null && trimmed.none { it.nearestEdgeIndex == alwaysKeepNearest.nearestEdgeIndex }) {
            if (trimmed.size >= config.maxCandidatesPerFix) {
                trimmed.removeAt(trimmed.lastIndex)
            }
            trimmed += alwaysKeepNearest
        }

        return trimmed
    }

    private fun preliminaryCandidateScore(
        candidate: RouteAnalysis,
        fix: LocationFix,
        previousHypotheses: List<RouteMatchHypothesis>,
    ): Double {
        val emission = (candidate.offRouteMeters / effectiveSigmaMeters(fix)) * config.emissionWeight
        val continuity = if (previousHypotheses.isEmpty()) {
            0.0
        } else {
            val bestHypothesisScore = previousHypotheses.minOf { it.score }
            previousHypotheses.minOf { hypothesis ->
                abs(candidate.routeMeters - hypothesis.routeMeters) / config.preliminaryContinuityScaleMeters +
                    max(0.0, hypothesis.score - bestHypothesisScore)
            }
        }
        return emission + continuity
    }

    private fun solveBestCurrentMatches(observations: List<RouteMatchObservation>): List<ScoredRouteCandidate> {
        if (observations.isEmpty()) {
            return emptyList()
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
        return previousScores.indices
            .map { candidateIndex ->
                ScoredRouteCandidate(
                    analysis = finalObservation.candidates[candidateIndex],
                    score = previousScores[candidateIndex],
                )
            }
            .sortedBy { it.score }
    }

    private fun selectStateHypotheses(
        rankedCandidates: List<ScoredRouteCandidate>,
    ): List<RouteMatchHypothesis> {
        if (rankedCandidates.isEmpty()) {
            return emptyList()
        }

        val bestScore = rankedCandidates.first().score
        val selected = mutableListOf<RouteMatchHypothesis>()
        for (candidate in rankedCandidates) {
            if (candidate.score - bestScore > config.hypothesisScoreMargin) {
                break
            }
            if (selected.any { existing ->
                    abs(existing.routeMeters - candidate.analysis.routeMeters) < config.hypothesisRouteSeparationMeters
                }
            ) {
                continue
            }
            selected += RouteMatchHypothesis(
                nearestEdgeIndex = candidate.analysis.nearestEdgeIndex.takeIf { it >= 0 },
                routeMeters = candidate.analysis.routeMeters,
                score = candidate.score,
            )
            if (selected.size >= config.maxStateHypotheses) {
                break
            }
        }
        return selected.ifEmpty {
            listOf(
                RouteMatchHypothesis(
                    nearestEdgeIndex = rankedCandidates.first().analysis.nearestEdgeIndex.takeIf { it >= 0 },
                    routeMeters = rankedCandidates.first().analysis.routeMeters,
                    score = rankedCandidates.first().score,
                ),
            )
        }
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

    private fun shouldBreakContinuity(
        matched: RouteAnalysis,
        nearest: RouteAnalysis,
        fix: LocationFix,
    ): Boolean {
        if (matched.nearestEdgeIndex == nearest.nearestEdgeIndex) {
            return false
        }

        val breakDistanceThreshold = max(
            config.continuityBreakDistanceMeters,
            effectiveSigmaMeters(fix) * config.continuityBreakAccuracyMultiplier,
        )
        if (matched.offRouteMeters < breakDistanceThreshold) {
            return false
        }
        if (nearest.offRouteMeters > config.continuityBreakNearestMeters) {
            return false
        }
        return matched.offRouteMeters - nearest.offRouteMeters >= config.continuityBreakGapMeters
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
