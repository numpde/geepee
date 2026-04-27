package dev.ra.geepee

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class NearbyWayQueryFocus(
    val focus: MapInfoFocus,
    val localTileIds: Set<DownloadTileId>,
)

internal data class NearbyWayLoadedTileRevision(
    val tileId: DownloadTileId,
    val updatedAtMillis: Long,
)

internal data class NearbyWayTileCoverage(
    val localTileIds: Set<DownloadTileId>,
    val loadedTileRevisions: List<NearbyWayLoadedTileRevision>,
) {
    val localTileCount: Int
        get() = localTileIds.size

    val loadedLocalTileCount: Int
        get() = loadedTileRevisions.size

    val loadedTileIds: List<DownloadTileId>
        get() = loadedTileRevisions.map(NearbyWayLoadedTileRevision::tileId)

    fun resolvedOverlayTileCount(cachedOverlayTileIds: Set<DownloadTileId>): Int {
        return loadedTileIds.count(cachedOverlayTileIds::contains)
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
        onResult: (List<RoutePoi>) -> Unit,
    ) {
        val requestId = ++routeContextRequestId
        routeContextExecutor.execute {
            val routeTileIds = tilesForRoute(routeModel, tileContextConfig)
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
        val cachedTileIds = tileDownloads
            .asSequence()
            .filter { (_, snapshot) -> snapshot.status == TileDownloadStatus.Cached }
            .map(Map.Entry<DownloadTileId, TileDownloadSnapshot>::key)
            .toSet()
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
            localTileIds = queryFocus.localTileIds,
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
    val localTileIds = tilesIntersectingProjectedBounds(
        projection = routeModel.projection,
        bounds = expandedProjectedBounds,
        zoom = config.downloadZoom,
    ).toSet()
    return NearbyWayQueryFocus(
        focus = resolvedFocus,
        localTileIds = localTileIds,
    )
}

internal fun buildNearbyWayTileCoverage(
    localTileIds: Set<DownloadTileId>,
    tileDownloads: Map<DownloadTileId, TileDownloadSnapshot>,
): NearbyWayTileCoverage {
    val loadedTileRevisions = localTileIds.mapNotNull { tileId ->
        tileDownloads[tileId]
            ?.takeIf { it.status == TileDownloadStatus.Cached }
            ?.let { snapshot ->
                NearbyWayLoadedTileRevision(
                    tileId = tileId,
                    updatedAtMillis = snapshot.updatedAtMillis,
                )
            }
    }.sortedBy { it.tileId.cacheKey }
    return NearbyWayTileCoverage(
        localTileIds = localTileIds,
        loadedTileRevisions = loadedTileRevisions,
    )
}

internal fun buildNearbyWayQueryCacheKey(
    routeModel: RouteModel,
    queryFocus: NearbyWayQueryFocus,
    tileCoverage: NearbyWayTileCoverage,
): NearbyWayQueryCacheKey {
    return NearbyWayQueryCacheKey(
        routeFingerprint = routeFingerprint(routeModel),
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
    val routeTiles = tilesForRoute(routeModel, config)
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

private fun <K, V> accessOrderCache(maxEntries: Int): LinkedHashMap<K, V> {
    return object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }
}
