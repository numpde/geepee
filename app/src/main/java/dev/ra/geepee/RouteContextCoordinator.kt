package dev.ra.geepee

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class NearbyWayQueryFocus(
    val focus: MapInfoFocus,
    val localTileIds: Set<DownloadTileId>,
    val expandedProjectedBounds: Bounds,
    val dataZoom: Int,
)

internal data class NearbyWayLoadedTileRevision(
    val tileId: DownloadTileId,
    val updatedAtMillis: Long,
)

internal data class NearbyWayLoadedTileCoverage(
    val tileRevision: NearbyWayLoadedTileRevision,
    val coveredLocalTileIds: Set<DownloadTileId>,
)

internal data class NearbyWayTileCoverage(
    val localTileIds: Set<DownloadTileId>,
    val loadedTileCoverages: List<NearbyWayLoadedTileCoverage>,
) {
    val localTileCount: Int
        get() = localTileIds.size

    val loadedLocalTileCount: Int
        get() = loadedTileCoverages.sumOf { coverage -> coverage.coveredLocalTileIds.size }

    val loadedTileIds: List<DownloadTileId>
        get() = loadedTileCoverages.map { coverage -> coverage.tileRevision.tileId }

    val loadedTileRevisions: List<NearbyWayLoadedTileRevision>
        get() = loadedTileCoverages.map(NearbyWayLoadedTileCoverage::tileRevision)

    fun resolvedOverlayTileCount(cachedOverlayTileIds: Set<DownloadTileId>): Int {
        return loadedTileCoverages.sumOf { coverage ->
            if (coverage.tileRevision.tileId in cachedOverlayTileIds) {
                coverage.coveredLocalTileIds.size
            } else {
                0
            }
        }
    }

    fun loadingStatus(existingNearbyWayCount: Int): LocalNearbyWayDebugStatus {
        return LocalNearbyWayDebugStatus.loading(
            localTileCount = localTileCount,
            downloadedLocalTileCount = loadedLocalTileCount,
            overlayReadyLocalTileCount = 0,
            existingNearbyWayCount = existingNearbyWayCount,
        )
    }

    fun resolvedMapInfo(
        nearbyWays: List<RouteNearbyWaySnippet>,
        overlayReadyLocalTileCount: Int,
        nearbyWaysLoading: Boolean = false,
    ): RouteMapInfoState {
        return RouteMapInfoState.resolvedNearbyWays(
            localTileCount = localTileCount,
            downloadedLocalTileCount = loadedLocalTileCount,
            overlayReadyLocalTileCount = overlayReadyLocalTileCount,
            nearbyWays = nearbyWays,
            nearbyWaysLoading = nearbyWaysLoading,
        )
    }

    fun failedMapInfo(errorMessage: String): RouteMapInfoState {
        return RouteMapInfoState.failedNearbyWays(
            localTileCount = localTileCount,
            downloadedLocalTileCount = loadedLocalTileCount,
            overlayReadyLocalTileCount = 0,
            hasVisibleTileData = false,
            errorMessage = errorMessage,
        )
    }
}

internal data class NearbyWayQueryCacheKey(
    val routeFingerprint: String,
    val localTileIds: List<DownloadTileId>,
    val localTileRevisions: List<NearbyWayLoadedTileRevision>,
    val focusBoundsBucket: NearbyWayFocusBoundsBucket,
)

internal data class NearbyWayFocusBoundsBucket(
    val minXBucket: Int,
    val maxXBucket: Int,
    val minYBucket: Int,
    val maxYBucket: Int,
)

private const val NEARBY_WAY_RESULT_CACHE_LIMIT = 64

internal class RouteContextCoordinator(
    private val tileContextRepository: TileContextRepository,
    private val tileContextConfig: TileContextConfig,
    private val callbackExecutor: Executor,
    private val logTag: String,
) {
    private val routeContextExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val nearbyWayExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var routeContextRequestId = 0L
    private var nearbyWayRequestId = 0L
    private var nearbyWayQueryKey: NearbyWayQueryCacheKey? = null
    private val nearbyWayResultCache =
        accessOrderCache<NearbyWayQueryCacheKey, RouteMapInfoState>(NEARBY_WAY_RESULT_CACHE_LIMIT)

    fun clear() {
        routeContextRequestId++
        nearbyWayRequestId++
        nearbyWayQueryKey = null
    }

    fun shutdown() {
        routeContextExecutor.shutdownNow()
        nearbyWayExecutor.shutdownNow()
    }

    fun rebuildRouteContext(
        routeModel: RouteModel,
        tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
        onResult: (List<RoutePoi>) -> Unit,
    ) {
        val requestId = ++routeContextRequestId
        routeContextExecutor.execute {
            val routeTileIds = cachedRouteTileIds(
                routeModel = routeModel,
                tileIds = tileDownloads.keys,
                config = tileContextConfig,
            )
            val result = try {
                val bundles = tileContextRepository.loadRouteTileOverlayBundles(
                    routeModel = routeModel,
                    tileIds = routeTileIds,
                    config = tileContextConfig,
                )
                val pois = mergeRouteTileOverlayPois(
                    routeModel = routeModel,
                    overlays = bundles.map(RouteTileOverlayBundle::overlay),
                )
                pois
            } catch (error: Throwable) {
                Log.e(logTag, "Route context rebuild failed", error)
                emptyList()
            }
            callbackExecutor.execute {
                if (requestId == routeContextRequestId) {
                    onResult(result)
                }
            }
        }
    }

    fun warmNearbyWayOverlays(
        routeModel: RouteModel,
        tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
    ) {
        val cachedTileIds = tileDownloads.cachedTileIds()
        val warmTileIds = routeMapInfoWarmTileIds(
            routeModel = routeModel,
            cachedTileIds = cachedTileIds,
            config = tileContextConfig,
        )
        if (warmTileIds.isEmpty()) {
            return
        }
        warmRouteTileOverlays(routeModel, warmTileIds)
    }

    private fun warmRouteTileOverlays(
        routeModel: RouteModel,
        tileIds: Collection<DownloadTileId>,
    ) {
        routeContextExecutor.execute {
            runCatching {
                tileContextRepository.loadRouteTileOverlayBundles(
                    routeModel = routeModel,
                    tileIds = tileIds,
                    config = tileContextConfig,
                )
            }.onFailure { error ->
                Log.w(logTag, "Route map-info overlay warmup failed", error)
            }
        }
    }

    fun rebuildNearbyWays(
        routeModel: RouteModel,
        analysis: RouteAnalysis,
        tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
        existingLocalStatus: LocalNearbyWayDebugStatus?,
        focus: MapInfoFocus?,
        defaultFocusWindowWidthMeters: Double,
        force: Boolean = false,
        onStarted: (LocalNearbyWayDebugStatus) -> Unit,
        onResult: (RouteMapInfoState) -> Unit,
    ) {
        val queryFocus = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = focus,
            config = tileContextConfig,
            defaultFocusWindowWidthMeters = defaultFocusWindowWidthMeters,
        )
        val tileCoverage = buildNearbyWayTileCoverage(
            queryFocus = queryFocus,
            tileDownloads = tileDownloads,
        )
        val cacheKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = queryFocus,
            tileCoverage = tileCoverage,
        )
        if (!force && nearbyWayQueryKey == cacheKey) {
            return
        }
        nearbyWayQueryKey = cacheKey
        val cachedResult = nearbyWayResultCache[cacheKey]
        if (cachedResult != null) {
            onResult(cachedResult)
            return
        }
        onStarted(tileCoverage.loadingStatus(existingLocalStatus?.nearbyWayCount ?: 0))
        val requestId = ++nearbyWayRequestId
        nearbyWayExecutor.execute {
            try {
                val cachedBundles = tileContextRepository.peekCachedRouteTileOverlayBundles(
                    routeModel = routeModel,
                    tileIds = tileCoverage.loadedTileIds,
                    config = tileContextConfig,
                )
                val cachedBundleTileIds = cachedBundles
                    .map { bundle -> bundle.overlay.tileId }
                    .toSet()
                val overlayNearbyWays = dedupeNearbyWaysByFeatureId(
                    cachedBundles.flatMap { bundle ->
                        queryRouteTileOverlayNearbyWays(
                            routeModel = routeModel,
                            bundle = bundle,
                            focus = queryFocus.focus,
                            config = tileContextConfig,
                        )
                    }
                )
                val missingOverlayTileIds = tileCoverage.loadedTileIds.filterNot(cachedBundleTileIds::contains)
                val overlayReadyLocalTileCount = tileCoverage.resolvedOverlayTileCount(cachedBundleTileIds)
                val partialResult = tileCoverage.resolvedMapInfo(
                    nearbyWays = overlayNearbyWays,
                    overlayReadyLocalTileCount = overlayReadyLocalTileCount,
                    nearbyWaysLoading = missingOverlayTileIds.isNotEmpty() && tileCoverage.loadedLocalTileCount > overlayReadyLocalTileCount,
                )
                callbackExecutor.execute {
                    if (requestId == nearbyWayRequestId && nearbyWayQueryKey == cacheKey) {
                        if (!partialResult.localNearbyWays!!.nearbyWaysLoading &&
                            partialResult.localNearbyWays.errorMessage == null
                        ) {
                            nearbyWayResultCache[cacheKey] = partialResult
                        }
                        onResult(partialResult)
                    }
                }
                if (missingOverlayTileIds.isEmpty()) {
                    return@execute
                }

                val warmedBundles = tileContextRepository.loadRouteTileOverlayBundles(
                    routeModel = routeModel,
                    tileIds = missingOverlayTileIds,
                    config = tileContextConfig,
                )
                val completedNearbyWays = dedupeNearbyWaysByFeatureId(
                    overlayNearbyWays + warmedBundles.flatMap { bundle ->
                        queryRouteTileOverlayNearbyWays(
                            routeModel = routeModel,
                            bundle = bundle,
                            focus = queryFocus.focus,
                            config = tileContextConfig,
                        )
                    }
                )
                val completedResult = tileCoverage.resolvedMapInfo(
                    nearbyWays = completedNearbyWays,
                    overlayReadyLocalTileCount = tileCoverage.loadedLocalTileCount,
                )
                callbackExecutor.execute {
                    if (requestId == nearbyWayRequestId && nearbyWayQueryKey == cacheKey) {
                        nearbyWayResultCache[cacheKey] = completedResult
                        onResult(completedResult)
                    }
                }
            } catch (error: Throwable) {
                Log.e(logTag, "Nearby-way rebuild failed", error)
                val failedResult = tileCoverage.failedMapInfo(error.javaClass.simpleName)
                callbackExecutor.execute {
                    if (requestId == nearbyWayRequestId && nearbyWayQueryKey == cacheKey) {
                        onResult(failedResult)
                    }
                }
            }
        }
    }
}

internal fun resolveNearbyWayQueryFocus(
    routeModel: RouteModel,
    analysis: RouteAnalysis,
    explicitFocus: MapInfoFocus?,
    config: TileContextConfig,
    defaultFocusWindowWidthMeters: Double,
): NearbyWayQueryFocus {
    val resolvedFocus = nearbyWayMapInfoFocusOrDefault(
        explicitFocus = explicitFocus,
        routeModel = routeModel,
        analysis = analysis,
        config = config,
        defaultWindowWidthMeters = defaultFocusWindowWidthMeters,
    )
    val expandedProjectedBounds = expandedNearbyWayMapInfoBounds(
        focus = resolvedFocus,
        config = config,
    )
    val tileResolution = resolveTileResolution(
        windowWidthMeters = resolvedFocus.windowWidthMeters,
        policy = config.resolutionPolicy,
    )
    val localTileIds = tilesIntersectingProjectedBounds(
        projection = routeModel.projection,
        bounds = expandedProjectedBounds,
        zoom = tileResolution.dataZoom,
    ).toSet()
    return NearbyWayQueryFocus(
        focus = resolvedFocus,
        localTileIds = localTileIds,
        expandedProjectedBounds = expandedProjectedBounds,
        dataZoom = tileResolution.dataZoom,
    )
}

internal fun buildNearbyWayTileCoverage(
    queryFocus: NearbyWayQueryFocus,
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
): NearbyWayTileCoverage {
    val cachedTileRevisions = tileDownloads
        .mapNotNull { (tileId, snapshot) ->
            if (!snapshot.isCached) {
                return@mapNotNull null
            }
            if (tileId.zoom > queryFocus.dataZoom) {
                return@mapNotNull null
            }
            NearbyWayLoadedTileRevision(
                tileId = tileId,
                updatedAtMillis = snapshot.updatedAtMillis,
            )
        }
        .sortedWith(
            compareByDescending<NearbyWayLoadedTileRevision> { revision -> revision.tileId.zoom }
                .thenBy { revision -> revision.tileId.cacheKey },
        )
    val loadedCoverageByTileId = linkedMapOf<DownloadTileId, MutableSet<DownloadTileId>>()
    queryFocus.localTileIds
        .sortedBy(DownloadTileId::cacheKey)
        .forEach { localTileId ->
            val bestLoadedTileRevision = cachedTileRevisions.firstOrNull { loadedTileRevision ->
                tileContainsTile(
                    containerTileId = loadedTileRevision.tileId,
                    childTileId = localTileId,
                )
            } ?: return@forEach
            loadedCoverageByTileId
                .getOrPut(bestLoadedTileRevision.tileId) { linkedSetOf() }
                .add(localTileId)
        }
    val loadedTileCoverages = loadedCoverageByTileId.entries
        .map { (tileId, coveredLocalTileIds) ->
            NearbyWayLoadedTileCoverage(
                tileRevision = cachedTileRevisions.first { revision -> revision.tileId == tileId },
                coveredLocalTileIds = coveredLocalTileIds.toSet(),
            )
        }
        .sortedBy { coverage -> coverage.tileRevision.tileId.cacheKey }
    return NearbyWayTileCoverage(
        localTileIds = queryFocus.localTileIds,
        loadedTileCoverages = loadedTileCoverages,
    )
}

internal fun buildNearbyWayQueryCacheKey(
    routeModel: RouteModel,
    queryFocus: NearbyWayQueryFocus,
    tileCoverage: NearbyWayTileCoverage,
): NearbyWayQueryCacheKey {
    return NearbyWayQueryCacheKey(
        routeFingerprint = routeFingerprint(routeModel),
        localTileIds = queryFocus.localTileIds.sortedBy(DownloadTileId::cacheKey),
        localTileRevisions = tileCoverage.loadedTileRevisions,
        focusBoundsBucket = nearbyWayFocusBoundsBucket(queryFocus.focus),
    )
}

internal fun nearbyWayFocusBoundsBucket(focus: MapInfoFocus): NearbyWayFocusBoundsBucket {
    val projectedBounds = focus.projectedBounds
    val boundsBucketMeters = maxOf(25.0, focus.windowWidthMeters * 0.2)
    return NearbyWayFocusBoundsBucket(
        minXBucket = kotlin.math.floor(projectedBounds.minX / boundsBucketMeters).toInt(),
        maxXBucket = kotlin.math.floor(projectedBounds.maxX / boundsBucketMeters).toInt(),
        minYBucket = kotlin.math.floor(projectedBounds.minY / boundsBucketMeters).toInt(),
        maxYBucket = kotlin.math.floor(projectedBounds.maxY / boundsBucketMeters).toInt(),
    )
}

internal fun routeMapInfoWarmTileIds(
    routeModel: RouteModel,
    cachedTileIds: Set<DownloadTileId>,
    config: TileContextConfig,
): Set<DownloadTileId> {
    if (cachedTileIds.isEmpty()) {
        return emptySet()
    }
    val routeTiles = cachedRouteTileIds(
        routeModel = routeModel,
        tileIds = cachedTileIds,
        config = config,
    )
    if (routeTiles.isEmpty()) {
        return emptySet()
    }
    val warmTileCandidates = buildSet {
        routeTiles.forEach { tileId ->
            addAll(neighboringTileIds(tileId, radius = 1))
        }
    }
    return warmTileCandidates.intersect(cachedTileIds)
}

internal fun cachedRouteTileIds(
    routeModel: RouteModel,
    tileIds: Collection<DownloadTileId>,
    config: TileContextConfig,
): Set<DownloadTileId> {
    return tileIds
        .filter { tileId ->
            tileRouteMetrics(
                routeModel = routeModel,
                tileBounds = projectedBoundsForGeoBounds(tileGeoBounds(tileId), routeModel.projection),
                haloMeters = config.fetchHaloMeters,
            ).intersectsRoute
        }
        .toSet()
}

internal fun tileContainsTile(
    containerTileId: DownloadTileId,
    childTileId: DownloadTileId,
): Boolean {
    if (containerTileId.zoom > childTileId.zoom) {
        return false
    }
    val zoomDelta = childTileId.zoom - containerTileId.zoom
    return (childTileId.x shr zoomDelta) == containerTileId.x &&
        (childTileId.y shr zoomDelta) == containerTileId.y
}

private fun <K, V> accessOrderCache(maxEntries: Int): LinkedHashMap<K, V> {
    return object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }
}
