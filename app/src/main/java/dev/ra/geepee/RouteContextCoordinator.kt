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

    fun loadingStatus(existingNearbyWayCount: Int): LocalNearbyWayDebugStatus {
        return LocalNearbyWayDebugStatus.loading(
            localTileCount = localTileCount,
            loadedLocalTileCount = loadedLocalTileCount,
            existingNearbyWayCount = existingNearbyWayCount,
        )
    }

    fun resolvedMapInfo(nearbyWays: List<RouteNearbyWaySnippet>): RouteMapInfoState {
        return RouteMapInfoState.resolvedNearbyWays(
            localTileCount = localTileCount,
            loadedLocalTileCount = loadedLocalTileCount,
            nearbyWays = nearbyWays,
        )
    }

    fun failedMapInfo(errorMessage: String): RouteMapInfoState {
        return RouteMapInfoState.failedNearbyWays(
            localTileCount = localTileCount,
            loadedLocalTileCount = loadedLocalTileCount,
            hasVisibleTileData = loadedLocalTileCount > 0,
            errorMessage = errorMessage,
        )
    }
}

internal data class NearbyWayQueryCacheKey(
    val routeFingerprint: String,
    val localTileRevisions: List<NearbyWayLoadedTileRevision>,
    val boundsMinXBucket: Int,
    val boundsMaxXBucket: Int,
    val boundsMinYBucket: Int,
    val boundsMaxYBucket: Int,
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
            val result = try {
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
                val focusRouteEdgeIndexes = nearbyWayRuntimeHintEdgeIndexes(
                    routeModel = routeModel,
                    focus = queryFocus.focus,
                    config = tileContextConfig,
                )
                val runtimeFallbackNearbyWays = dedupeNearbyWaysByFeatureId(
                    tileContextRepository.loadRuntimePacks(missingOverlayTileIds).flatMap { runtimePack ->
                        queryTileRuntimeNearbyWays(
                            routeModel = routeModel,
                            runtimePack = runtimePack,
                            focus = queryFocus.focus,
                            focusHintEdgeIndexes = focusRouteEdgeIndexes,
                            config = tileContextConfig,
                        )
                    }
                )
                if (missingOverlayTileIds.isNotEmpty()) {
                    warmRouteTileOverlays(routeModel, missingOverlayTileIds)
                }
                val nearbyWays = dedupeNearbyWaysByFeatureId(overlayNearbyWays + runtimeFallbackNearbyWays)
                tileCoverage.resolvedMapInfo(nearbyWays)
            } catch (error: Throwable) {
                Log.e(logTag, "Nearby-way rebuild failed", error)
                tileCoverage.failedMapInfo(error.javaClass.simpleName)
            }
            callbackExecutor.execute {
                if (requestId == nearbyWayRequestId && nearbyWayQueryKey == cacheKey) {
                    if (result.localNearbyWays?.errorMessage == null) {
                        nearbyWayResultCache[cacheKey] = result
                    }
                    onResult(result)
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

internal fun nearbyWayRuntimeHintEdgeIndexes(
    routeModel: RouteModel,
    focus: MapInfoFocus,
    config: TileContextConfig,
): List<Int> {
    return routeEdgeIndexesIntersectingBounds(
        model = routeModel,
        bounds = expandedNearbyWayMapInfoBounds(
            focus = focus,
            config = config,
        ),
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
    val projectedBounds = queryFocus.focus.projectedBounds
    val boundsBucketMeters = maxOf(25.0, queryFocus.focus.windowWidthMeters * 0.2)
    return NearbyWayQueryCacheKey(
        routeFingerprint = routeFingerprint(routeModel),
        localTileRevisions = tileCoverage.loadedTileRevisions,
        boundsMinXBucket = kotlin.math.floor(projectedBounds.minX / boundsBucketMeters).toInt(),
        boundsMaxXBucket = kotlin.math.floor(projectedBounds.maxX / boundsBucketMeters).toInt(),
        boundsMinYBucket = kotlin.math.floor(projectedBounds.minY / boundsBucketMeters).toInt(),
        boundsMaxYBucket = kotlin.math.floor(projectedBounds.maxY / boundsBucketMeters).toInt(),
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
