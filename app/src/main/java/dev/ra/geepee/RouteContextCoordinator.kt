package dev.ra.geepee

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class NearbyWayQueryFocus(
    val focus: MapInfoFocus,
    val hintEdgeIndexes: List<Int>,
    val localTileIds: Set<DownloadTileId>,
)

internal data class NearbyWayLoadedTileRevision(
    val tileId: DownloadTileId,
    val updatedAtMillis: Long,
)

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
        val localTileIds = queryFocus.localTileIds
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
        val cacheKey = buildNearbyWayQueryCacheKey(
            routeModel = routeModel,
            queryFocus = queryFocus,
            loadedTileRevisions = loadedTileRevisions,
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
        val loadedLocalTileCount = loadedTileRevisions.size
        onStarted(
            LocalNearbyWayDebugStatus.loading(
                localTileCount = localTileIds.size,
                loadedLocalTileCount = loadedLocalTileCount,
                existingNearbyWayCount = existingLocalStatus?.nearbyWayCount ?: 0,
            ),
        )
        val requestId = ++nearbyWayRequestId
        nearbyWayExecutor.execute {
            val result = try {
                val runtimePacks = tileContextRepository.loadRuntimePacks(localTileIds)
                val nearbyWays = runtimePacks
                    .flatMap { runtimePack ->
                        queryTileRuntimeNearbyWays(
                            routeModel = routeModel,
                            runtimePack = runtimePack,
                            focusGeoPoint = queryFocus.focus.centerGeoPoint,
                            focusHintEdgeIndexes = queryFocus.hintEdgeIndexes,
                            focusWindowWidthMeters = queryFocus.focus.windowWidthMeters,
                            focusBoundsOverride = queryFocus.focus.projectedBounds,
                            config = tileContextConfig,
                        )
                    }
                    .groupBy(RouteNearbyWaySnippet::featureId)
                    .values
                    .map { snippets ->
                        snippets.maxBy { snippet ->
                            snippet.points.zipWithNext().sumOf { (start, end) ->
                                kotlin.math.hypot(end.x - start.x, end.y - start.y)
                            }
                        }
                    }
                    .sortedBy(RouteNearbyWaySnippet::featureId)
                RouteMapInfoState.resolvedNearbyWays(
                    localTileCount = localTileIds.size,
                    loadedLocalTileCount = runtimePacks.size,
                    nearbyWays = nearbyWays,
                )
            } catch (error: Throwable) {
                Log.e(logTag, "Nearby-way rebuild failed", error)
                RouteMapInfoState.failedNearbyWays(
                    localTileCount = localTileIds.size,
                    loadedLocalTileCount = loadedLocalTileCount,
                    hasVisibleTileData = loadedLocalTileCount > 0,
                    errorMessage = error.javaClass.simpleName,
                )
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
    val resolvedFocus = explicitFocus ?: MapInfoFocus(
        centerGeoPoint = analysis.nearestGeoPoint,
        windowWidthMeters = defaultFocusWindowWidthMeters,
        projectedBounds = nearbyWayFocusBounds(
            routeModel = routeModel,
            focusGeoPoint = analysis.nearestGeoPoint,
            focusWindowWidthMeters = defaultFocusWindowWidthMeters,
            haloMeters = config.wayHaloMeters,
            continuationMeters = config.nearbyWayContinuationMeters,
        ) ?: routeModel.bounds,
    )
    val projectedFocusBounds = resolvedFocus.projectedBounds
    val fallbackNearestEdgeIndex = if (
        explicitFocus == null ||
        distanceBetweenGeoPointsMeters(resolvedFocus.centerGeoPoint, analysis.nearestGeoPoint) <= 3.0
    ) {
        analysis.nearestEdgeIndex
    } else {
        analyzeProjectedPointNearRouteHint(
            model = routeModel,
            projectedFix = projectGeoPointToRouteProjection(resolvedFocus.centerGeoPoint, routeModel.projection),
            hintEdgeIndexes = listOfNotNull(analysis.nearestEdgeIndex.takeIf { it >= 0 }),
            maxHintDistanceMeters = resolvedFocus.windowWidthMeters / 2.0 +
                config.wayHaloMeters +
                config.nearbyWayContinuationMeters,
        ).nearestEdgeIndex
    }
    val focusHintEdgeIndexes = routeEdgeIndexesIntersectingBounds(
        model = routeModel,
        bounds = expandBounds(
            projectedFocusBounds,
            config.wayHaloMeters + config.nearbyWayContinuationMeters,
        ),
    ).ifEmpty {
        listOfNotNull(fallbackNearestEdgeIndex.takeIf { it >= 0 })
    }
    val localTileIds = tilesIntersectingProjectedBounds(
        projection = routeModel.projection,
        bounds = expandBounds(
            projectedFocusBounds,
            config.wayHaloMeters + config.nearbyWayContinuationMeters,
        ),
        zoom = config.downloadZoom,
    ).toSet()
    return NearbyWayQueryFocus(
        focus = resolvedFocus,
        hintEdgeIndexes = focusHintEdgeIndexes,
        localTileIds = localTileIds,
    )
}

internal fun buildNearbyWayQueryCacheKey(
    routeModel: RouteModel,
    queryFocus: NearbyWayQueryFocus,
    loadedTileRevisions: List<NearbyWayLoadedTileRevision>,
): NearbyWayQueryCacheKey {
    val projectedBounds = queryFocus.focus.projectedBounds
    val boundsBucketMeters = maxOf(25.0, queryFocus.focus.windowWidthMeters * 0.2)
    return NearbyWayQueryCacheKey(
        routeFingerprint = routeFingerprint(routeModel),
        localTileRevisions = loadedTileRevisions,
        boundsMinXBucket = kotlin.math.floor(projectedBounds.minX / boundsBucketMeters).toInt(),
        boundsMaxXBucket = kotlin.math.floor(projectedBounds.maxX / boundsBucketMeters).toInt(),
        boundsMinYBucket = kotlin.math.floor(projectedBounds.minY / boundsBucketMeters).toInt(),
        boundsMaxYBucket = kotlin.math.floor(projectedBounds.maxY / boundsBucketMeters).toInt(),
    )
}

private fun <K, V> accessOrderCache(maxEntries: Int): LinkedHashMap<K, V> {
    return object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }
}
