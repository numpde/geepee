package dev.ra.geepee

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

internal data class RouteMatcherConfig(
    val beliefConfig: RouteBeliefConfig = RouteBeliefConfig(),
    val maxCandidatesPerFix: Int = 12,
    val maxStateHypotheses: Int = 4,
    val initialRouteProbability: Double = 0.5,
    val routeExitPenalty: Double = 1.6,
    val routeReentryPenalty: Double = 1.2,
    val offRouteStayPenalty: Double = 0.0,
    val routeRelocalizationPenalty: Double = 4.0,
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
    val hypothesisScoreMargin: Double = 1.2,
    val hypothesisRouteSeparationMeters: Double = 20.0,
) {
    init {
        require(maxCandidatesPerFix > 0) { "At least one candidate is required." }
        require(maxStateHypotheses > 0) { "At least one state hypothesis is required." }
        require(initialRouteProbability in 0.0..1.0) { "Initial route probability must be a probability." }
    }
}

private data class RouteMatcherState(
    val hypotheses: List<RouteMatchHypothesis> = emptyList(),
    val offRouteLogProbability: Double = ln(0.5),
    val lastFix: LocationFix? = null,
    val lastProjectedFix: ProjectedPoint? = null,
)

private data class RouteMatchHypothesis(
    val nearestEdgeIndex: Int?,
    val routeMeters: Double,
    val nearestPoint: ProjectedPoint,
    val logProbability: Double,
)

private data class ScoredRouteCandidate(
    val analysis: RouteAnalysis,
    val logProbability: Double,
    val posteriorProbability: Double,
)

private data class RouteTransitionPrior(
    val routeLogProbabilities: List<Double>,
    val offRouteLogProbability: Double,
)

private data class RouteBeliefUpdate(
    val sigmaMeters: Double,
    val routeProbability: Double,
    val offRouteProbability: Double,
    val offRouteLogProbability: Double,
    val rankedCandidates: List<ScoredRouteCandidate>,
)

internal data class RouteMatchDisplayHypothesis(
    val analysis: RouteAnalysis,
    val routeConditionalConfidence: Float,
    val isPrimary: Boolean,
)

internal data class RouteMatchResult(
    val analysis: RouteAnalysis,
    val hypotheses: List<RouteMatchDisplayHypothesis>,
    val belief: RouteBelief,
)

internal class RouteMatcher(
    private val routeModel: RouteModel,
    private val config: RouteMatcherConfig = RouteMatcherConfig(),
) {
    private var state = RouteMatcherState()

    fun reset() {
        state = RouteMatcherState()
    }

    fun match(fix: LocationFix): RouteMatchResult {
        val lastTimestamp = state.lastFix?.timestampMillis
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
            val sigmaMeters = observationSigmaMeters(fix, config.beliefConfig)
            val analysis = emptyRouteAnalysis(projectedFix)
            return RouteMatchResult(
                analysis = analysis,
                hypotheses = emptyList(),
                belief = RouteBelief(
                    fix = fix,
                    sigmaMeters = sigmaMeters,
                    routeProbability = 0.0,
                    offRouteProbability = 1.0,
                    adherence = RouteAdherence.OffRoute,
                    primaryRouteAnalysis = null,
                    routeCandidates = emptyList(),
                ),
            )
        }

        val beliefUpdate = scoreCurrentBelief(
            fix = fix,
            projectedFix = projectedFix,
            candidates = candidates,
        )
        val selectedCandidates = selectPlausibleCandidates(beliefUpdate.rankedCandidates)
        val routeCandidateBeliefs = buildRouteCandidateBeliefs(selectedCandidates, fix)
        val finalMatch = routeCandidateBeliefs.firstOrNull()?.analysis
            ?: nearestCandidate?.withProgress(routeModel, fix)
            ?: candidates.minBy { it.offRouteMeters }.withProgress(routeModel, fix)
        val adherence = classifyRouteAdherence(
            routeProbability = beliefUpdate.routeProbability,
            offRouteProbability = beliefUpdate.offRouteProbability,
            config = config.beliefConfig,
        )
        val stateOffRouteLogProbability = stateOffRouteLogProbability(
            selectedCandidates = selectedCandidates,
            rankedCandidates = beliefUpdate.rankedCandidates,
            offRouteLogProbability = beliefUpdate.offRouteLogProbability,
        )
        val stateNormalizer = logSumExp(
            selectedCandidates.map { candidate -> candidate.logProbability } + stateOffRouteLogProbability,
        )
        state = RouteMatcherState(
            hypotheses = normalizedStateHypotheses(
                selectedCandidates = selectedCandidates,
                stateNormalizer = stateNormalizer,
            ),
            offRouteLogProbability = stateOffRouteLogProbability - stateNormalizer,
            lastFix = fix,
            lastProjectedFix = projectedFix,
        )
        val belief = RouteBelief(
            fix = fix,
            sigmaMeters = beliefUpdate.sigmaMeters,
            routeProbability = beliefUpdate.routeProbability,
            offRouteProbability = beliefUpdate.offRouteProbability,
            adherence = adherence,
            primaryRouteAnalysis = finalMatch,
            routeCandidates = routeCandidateBeliefs,
        )
        return RouteMatchResult(
            analysis = finalMatch,
            hypotheses = buildDisplayHypotheses(routeCandidateBeliefs),
            belief = belief,
        )
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
        val emission = (candidate.offRouteMeters / observationSigmaMeters(fix, config.beliefConfig)) *
            config.emissionWeight
        val continuity = if (previousHypotheses.isEmpty()) {
            0.0
        } else {
            val bestLogProbability = previousHypotheses.maxOf { it.logProbability }
            previousHypotheses.minOf { hypothesis ->
                abs(candidate.routeMeters - hypothesis.routeMeters) / config.preliminaryContinuityScaleMeters +
                    max(0.0, bestLogProbability - hypothesis.logProbability)
            }
        }
        return emission + continuity
    }

    private fun scoreCurrentBelief(
        fix: LocationFix,
        projectedFix: ProjectedPoint,
        candidates: List<RouteAnalysis>,
    ): RouteBeliefUpdate {
        val sigmaMeters = observationSigmaMeters(fix, config.beliefConfig)
        val transitionPrior = transitionPrior(
            fix = fix,
            projectedFix = projectedFix,
            candidates = candidates,
        )
        val routeLogProbabilities = candidates.zip(transitionPrior.routeLogProbabilities).map { (candidate, prior) ->
            prior - candidateCost(
                fix = fix,
                candidate = candidate,
                sigmaMeters = sigmaMeters,
            )
        }
        val offRouteLogProbability = transitionPrior.offRouteLogProbability -
            offRouteObservationCost(
                sigmaMeters = sigmaMeters,
                config = config.beliefConfig,
            )
        val normalizer = logSumExp(routeLogProbabilities + offRouteLogProbability)
        val rankedCandidates = candidates.zip(routeLogProbabilities).map { (candidate, logProbability) ->
            ScoredRouteCandidate(
                analysis = candidate,
                logProbability = logProbability - normalizer,
                posteriorProbability = exp(logProbability - normalizer),
            )
        }.sortedByDescending { it.logProbability }
        val normalizedOffRouteLogProbability = offRouteLogProbability - normalizer
        val offRouteProbability = exp(normalizedOffRouteLogProbability)
        val routeProbability = rankedCandidates.sumOf { it.posteriorProbability }.coerceIn(0.0, 1.0)
        return RouteBeliefUpdate(
            sigmaMeters = sigmaMeters,
            routeProbability = routeProbability,
            offRouteProbability = offRouteProbability,
            offRouteLogProbability = normalizedOffRouteLogProbability,
            rankedCandidates = rankedCandidates,
        )
    }

    private fun transitionPrior(
        fix: LocationFix,
        projectedFix: ProjectedPoint,
        candidates: List<RouteAnalysis>,
    ): RouteTransitionPrior {
        val previousFix = state.lastFix
        val previousProjectedFix = state.lastProjectedFix
        val candidateCountPenalty = ln(candidates.size.toDouble())
        if (previousFix == null || previousProjectedFix == null) {
            return RouteTransitionPrior(
                routeLogProbabilities = candidates.map {
                    ln(config.initialRouteProbability.coerceAtLeast(Double.MIN_VALUE)) - candidateCountPenalty
                },
                offRouteLogProbability = ln((1.0 - config.initialRouteProbability).coerceAtLeast(Double.MIN_VALUE)),
            )
        }

        val routeContributions = candidates.map { mutableListOf<Double>() }
        val offRouteContributions = mutableListOf<Double>()

        state.hypotheses.forEach { previousHypothesis ->
            val routeTransitionLogits = candidates.map { candidate ->
                -transitionCost(
                    previousFix = previousFix,
                    previousProjectedFix = previousProjectedFix,
                    previousRouteMeters = previousHypothesis.routeMeters,
                    previousNearestPoint = previousHypothesis.nearestPoint,
                    currentFix = fix,
                    currentProjectedFix = projectedFix,
                    currentRouteMeters = candidate.routeMeters,
                    currentNearestPoint = candidate.nearestPoint,
                )
            }
            val destinationLogits = routeTransitionLogits + listOf(-config.routeExitPenalty)
            val destinationNormalizer = logSumExp(destinationLogits)
            routeTransitionLogits.forEachIndexed { index, transitionLogit ->
                routeContributions[index] += previousHypothesis.logProbability +
                    transitionLogit -
                    destinationNormalizer
            }
            offRouteContributions += previousHypothesis.logProbability -
                config.routeExitPenalty -
                destinationNormalizer
        }

        val offRouteRouteLogit = -config.routeReentryPenalty - candidateCountPenalty
        val offRouteDestinationLogits = List(candidates.size) { offRouteRouteLogit } +
            listOf(-config.offRouteStayPenalty)
        val offRouteDestinationNormalizer = logSumExp(offRouteDestinationLogits)
        candidates.indices.forEach { index ->
            routeContributions[index] += state.offRouteLogProbability +
                offRouteRouteLogit -
                offRouteDestinationNormalizer
        }
        offRouteContributions += state.offRouteLogProbability -
            config.offRouteStayPenalty -
            offRouteDestinationNormalizer

        return RouteTransitionPrior(
            routeLogProbabilities = routeContributions.map { contributions ->
                logSumExp(contributions)
            },
            offRouteLogProbability = logSumExp(offRouteContributions),
        )
    }

    private fun stateOffRouteLogProbability(
        selectedCandidates: List<ScoredRouteCandidate>,
        rankedCandidates: List<ScoredRouteCandidate>,
        offRouteLogProbability: Double,
    ): Double {
        val selectedEdges = selectedCandidates.mapTo(mutableSetOf()) { candidate ->
            candidate.analysis.nearestEdgeIndex
        }
        val discardedRouteLogProbabilities = rankedCandidates.mapNotNull { candidate ->
            candidate.logProbability.takeIf {
                candidate.analysis.nearestEdgeIndex !in selectedEdges
            }
        }
        return logSumExp(discardedRouteLogProbabilities + offRouteLogProbability)
    }

    private fun normalizedStateHypotheses(
        selectedCandidates: List<ScoredRouteCandidate>,
        stateNormalizer: Double,
    ): List<RouteMatchHypothesis> {
        if (selectedCandidates.isEmpty()) {
            return emptyList()
        }
        return selectedCandidates.map { candidate ->
            RouteMatchHypothesis(
                nearestEdgeIndex = candidate.analysis.nearestEdgeIndex.takeIf { it >= 0 },
                routeMeters = candidate.analysis.routeMeters,
                nearestPoint = candidate.analysis.nearestPoint,
                logProbability = candidate.logProbability - stateNormalizer,
            )
        }
    }

    private fun selectPlausibleCandidates(
        rankedCandidates: List<ScoredRouteCandidate>,
    ): List<ScoredRouteCandidate> {
        if (rankedCandidates.isEmpty()) {
            return emptyList()
        }

        val bestLogProbability = rankedCandidates.first().logProbability
        val selected = mutableListOf<ScoredRouteCandidate>()
        for (candidate in rankedCandidates) {
            if (bestLogProbability - candidate.logProbability > config.hypothesisScoreMargin) {
                break
            }
            if (selected.any { existing ->
                    abs(existing.analysis.routeMeters - candidate.analysis.routeMeters) < config.hypothesisRouteSeparationMeters
                }
            ) {
                continue
            }
            selected += candidate
            if (selected.size >= config.maxStateHypotheses) {
                break
            }
        }
        return selected.ifEmpty {
            listOf(rankedCandidates.first())
        }
    }

    private fun buildRouteCandidateBeliefs(
        selectedCandidates: List<ScoredRouteCandidate>,
        fix: LocationFix,
    ): List<RouteCandidateBelief> {
        if (selectedCandidates.isEmpty()) {
            return emptyList()
        }

        val selectedRouteProbability = selectedCandidates.sumOf { it.posteriorProbability }
            .takeIf { it > 0.0 }
            ?: 1.0
        return selectedCandidates.mapIndexed { index, candidate ->
            RouteCandidateBelief(
                analysis = candidate.analysis.withProgress(routeModel, fix),
                posteriorProbability = candidate.posteriorProbability,
                routeConditionalProbability = candidate.posteriorProbability / selectedRouteProbability,
                isPrimary = index == 0,
            )
        }
    }

    private fun buildDisplayHypotheses(
        selectedCandidates: List<RouteCandidateBelief>,
    ): List<RouteMatchDisplayHypothesis> {
        return selectedCandidates.map { candidate ->
            RouteMatchDisplayHypothesis(
                analysis = candidate.analysis,
                routeConditionalConfidence = candidate.routeConditionalProbability.toFloat(),
                isPrimary = candidate.isPrimary,
            )
        }
    }

    private fun candidateCost(
        fix: LocationFix,
        candidate: RouteAnalysis,
        sigmaMeters: Double,
    ): Double {
        return routeObservationCost(
            offRouteMeters = candidate.offRouteMeters,
            sigmaMeters = sigmaMeters,
        ) * config.emissionWeight + headingPenalty(fix, candidate)
    }

    private fun transitionCost(
        previousFix: LocationFix,
        previousProjectedFix: ProjectedPoint,
        previousRouteMeters: Double,
        previousNearestPoint: ProjectedPoint,
        currentFix: LocationFix,
        currentProjectedFix: ProjectedPoint,
        currentRouteMeters: Double,
        currentNearestPoint: ProjectedPoint,
    ): Double {
        val routeDelta = currentRouteMeters - previousRouteMeters
        val dtSeconds = max(
            config.minTransitionDtSeconds,
            (currentFix.timestampMillis - previousFix.timestampMillis).toDouble() / 1_000.0,
        )
        val observedTravelMeters = hypot(
            currentProjectedFix.x - previousProjectedFix.x,
            currentProjectedFix.y - previousProjectedFix.y,
        )
        val speedTravelMeters = listOfNotNull(
            previousFix.speedMetersPerSecond?.toDouble(),
            currentFix.speedMetersPerSecond?.toDouble(),
        ).averageOrNull()?.times(dtSeconds)
        val expectedTravelMeters = max(observedTravelMeters, speedTravelMeters ?: 0.0)
        val toleranceMeters = max(
            config.baseTransitionToleranceMeters,
            max(
                observationSigmaMeters(previousFix, config.beliefConfig),
                observationSigmaMeters(currentFix, config.beliefConfig),
            ) * config.transitionToleranceAccuracyMultiplier,
        )
        val distancePenalty = abs(routeDelta - expectedTravelMeters) /
            (toleranceMeters + config.transitionPenaltyOffsetMeters)
        val reversePenalty = if (routeDelta < -config.backwardAllowanceMeters) {
            config.reversePenalty + abs(routeDelta) / config.reversePenaltyScaleMeters
        } else {
            0.0
        }
        val progressCost = distancePenalty * config.transitionWeight + reversePenalty
        val routeSpatialTravelMeters = hypot(
            currentNearestPoint.x - previousNearestPoint.x,
            currentNearestPoint.y - previousNearestPoint.y,
        )
        val relocalizationCost = config.routeRelocalizationPenalty +
            abs(routeSpatialTravelMeters - expectedTravelMeters) /
            (toleranceMeters + config.transitionPenaltyOffsetMeters)
        return minOf(progressCost, relocalizationCost)
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
