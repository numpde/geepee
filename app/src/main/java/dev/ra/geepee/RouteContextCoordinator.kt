package dev.ra.geepee

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class RouteContextRebuildResult(
    val pois: List<RoutePoi>,
)

internal data class NearbyWayRebuildResult(
    val nearbyWays: List<RouteNearbyWaySnippet>,
    val localNearbyWays: LocalNearbyWayDebugStatus,
)

internal data class NearbyWayQueryFocus(
    val mapInfoFocus: MapInfoFocus,
    val nearestEdgeIndex: Int,
    val centerTileId: DownloadTileId,
    val localTileIds: Set<DownloadTileId>,
)

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
    private var nearbyWayTileKey: Set<DownloadTileId>? = null

    fun clear() {
        routeContextRequestId++
        nearbyWayRequestId++
        nearbyWayTileKey = null
    }

    fun shutdown() {
        routeContextExecutor.shutdownNow()
        nearbyWayExecutor.shutdownNow()
    }

    fun rebuildRouteContext(
        routeModel: RouteModel,
        onResult: (RouteContextRebuildResult) -> Unit,
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
                RouteContextRebuildResult(pois = pois)
            } catch (error: Throwable) {
                Log.e(logTag, "Route context rebuild failed", error)
                RouteContextRebuildResult(pois = emptyList())
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
        onResult: (NearbyWayRebuildResult) -> Unit,
    ) {
        val queryFocus = resolveNearbyWayQueryFocus(
            routeModel = routeModel,
            analysis = analysis,
            explicitFocus = focus,
            config = tileContextConfig,
            defaultFocusWindowWidthMeters = defaultFocusWindowWidthMeters,
        )
        val centerTileId = queryFocus.centerTileId
        val localTileIds = queryFocus.localTileIds
        if (!force && nearbyWayTileKey == localTileIds) {
            return
        }
        nearbyWayTileKey = localTileIds
        val loadedLocalTileCount = localTileIds.count { tileDownloads[it]?.status == TileDownloadStatus.Cached }
        val currentTileAvailable = tileDownloads[centerTileId]?.status == TileDownloadStatus.Cached
        onStarted(
            LocalNearbyWayDebugStatus(
                localTileCount = localTileIds.size,
                loadedLocalTileCount = loadedLocalTileCount,
                currentTileAvailable = currentTileAvailable,
                nearbyWaysLoading = currentTileAvailable,
                nearbyWayCount = if (currentTileAvailable) existingLocalStatus?.nearbyWayCount ?: 0 else 0,
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
                            focusGeoPoint = queryFocus.mapInfoFocus.centerGeoPoint,
                            focusNearestEdgeIndex = queryFocus.nearestEdgeIndex,
                            focusWindowWidthMeters = queryFocus.mapInfoFocus.windowWidthMeters,
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
                NearbyWayRebuildResult(
                    nearbyWays = nearbyWays,
                    localNearbyWays = LocalNearbyWayDebugStatus(
                        localTileCount = localTileIds.size,
                        loadedLocalTileCount = runtimePacks.size,
                        currentTileAvailable = runtimePacks.any { it.tileId == centerTileId },
                        nearbyWayCount = nearbyWays.size,
                    ),
                )
            } catch (error: Throwable) {
                Log.e(logTag, "Nearby-way rebuild failed", error)
                NearbyWayRebuildResult(
                    nearbyWays = emptyList(),
                    localNearbyWays = LocalNearbyWayDebugStatus(
                        localTileCount = localTileIds.size,
                        loadedLocalTileCount = loadedLocalTileCount,
                        currentTileAvailable = currentTileAvailable,
                        nearbyWayCount = 0,
                        errorMessage = error.javaClass.simpleName,
                    ),
                )
            }
            callbackExecutor.execute {
                if (requestId == nearbyWayRequestId && nearbyWayTileKey == localTileIds) {
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
    val mapInfoFocus = explicitFocus ?: MapInfoFocus(
        centerGeoPoint = analysis.nearestGeoPoint,
        windowWidthMeters = defaultFocusWindowWidthMeters,
    )
    val focusNearestEdgeIndex = if (
        explicitFocus == null ||
        distanceBetweenGeoPointsMeters(mapInfoFocus.centerGeoPoint, analysis.nearestGeoPoint) <= 3.0
    ) {
        analysis.nearestEdgeIndex
    } else {
        analyzeProjectedPointNearRouteHint(
            model = routeModel,
            projectedFix = projectGeoPointToRouteProjection(mapInfoFocus.centerGeoPoint, routeModel.projection),
            hintEdgeIndexes = listOfNotNull(analysis.nearestEdgeIndex.takeIf { it >= 0 }),
            maxHintDistanceMeters = mapInfoFocus.windowWidthMeters / 2.0 +
                config.wayHaloMeters +
                config.nearbyWayContinuationMeters,
        ).nearestEdgeIndex
    }
    val centerTileId = tileIdForGeoPoint(mapInfoFocus.centerGeoPoint, config.downloadZoom)
    val localTileIds = neighboringTileIds(
        centerTile = centerTileId,
        radius = 1,
    )
    return NearbyWayQueryFocus(
        mapInfoFocus = mapInfoFocus,
        nearestEdgeIndex = focusNearestEdgeIndex,
        centerTileId = centerTileId,
        localTileIds = localTileIds,
    )
}
