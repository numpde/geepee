package dev.ra.geepee

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh

private const val WEB_MERCATOR_MAX_LAT = 85.05112878
private const val TILE_CONTEXT_EARTH_RADIUS_METERS = 6_371_000.0
private const val DEFAULT_DOWNLOAD_ZOOM = 10
private const val DEFAULT_WAY_HALO_METERS = 80.0
private const val DEFAULT_NEARBY_WAY_CONTINUATION_METERS = 180.0
private const val DEFAULT_POI_HALO_METERS = 220.0
private const val DEFAULT_SERVICE_HALO_METERS = 500.0
private const val APPROX_TILE_ESTIMATE_BYTES = 180_000L
private const val ROUTE_TILE_ESTIMATE_BONUS_BYTES = 210_000L
private const val EDGE_ESTIMATE_BONUS_BYTES = 14_000L
private const val MAX_EDGE_ESTIMATE_BONUS_BYTES = 180_000L
private const val ROUTE_LENGTH_ESTIMATE_BYTES_PER_METER = 8.0
private const val MAX_ROUTE_LENGTH_ESTIMATE_BONUS_BYTES = 260_000L
private const val TILE_CONTEXT_PACK_SCHEMA_VERSION = 1
private const val TILE_GRID_PROXY_EDGE_MARGIN_PX = 1f

internal val DefaultTileContextConfig = TileContextConfig()

private val WAY_TAG_ALLOWLIST = linkedSetOf(
    "highway",
    "surface",
    "smoothness",
    "tracktype",
    "bicycle",
    "cycleway",
    "lit",
    "access",
    "junction",
    "oneway",
    "name",
)

private val POINT_TAG_ALLOWLIST = linkedSetOf(
    "amenity",
    "tourism",
    "shop",
    "highway",
    "barrier",
    "railway",
    "name",
)

internal data class TileContextConfig(
    val downloadZoom: Int = DEFAULT_DOWNLOAD_ZOOM,
    val wayHaloMeters: Double = DEFAULT_WAY_HALO_METERS,
    val nearbyWayContinuationMeters: Double = DEFAULT_NEARBY_WAY_CONTINUATION_METERS,
    val poiHaloMeters: Double = DEFAULT_POI_HALO_METERS,
    val serviceHaloMeters: Double = DEFAULT_SERVICE_HALO_METERS,
    val resolutionPolicy: TileResolutionPolicy = TileResolutionPolicy(),
) {
    val fetchHaloMeters: Double
        get() = max(wayHaloMeters, max(poiHaloMeters, serviceHaloMeters))
}

internal data class TileContextPack(
    val schemaVersion: Int = TILE_CONTEXT_PACK_SCHEMA_VERSION,
    val tileId: DownloadTileId,
    val queryBounds: GeoBounds,
    val fetchedAtMillis: Long,
    val features: List<TileContextFeature>,
)

internal data class TileContextFeature(
    val featureId: String,
    val geometryKind: TileGeometryKind,
    val tags: Map<String, String>,
    val geometry: List<GeoPoint>,
)

internal enum class TileGeometryKind {
    Way,
    Point,
}

internal data class DownloadTileId(
    val zoom: Int,
    val x: Int,
    val y: Int,
) {
    val cacheKey: String
        get() = "$zoom/$x/$y"
}

internal enum class TileDownloadStatus {
    Downloading,
    Cached,
    Error,
}

internal data class TileDownloadSnapshot(
    val status: TileDownloadStatus,
    val estimatedBytes: Long,
    val downloadedBytes: Long = 0L,
    val actualBytes: Long? = null,
    val errorMessage: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val downloadedAtMillis: Long = updatedAtMillis,
    val lastAccessedAtMillis: Long = updatedAtMillis,
) {
    val progressFraction: Float?
        get() = if (status == TileDownloadStatus.Downloading) {
            val denominator = max(actualBytes ?: estimatedBytes, 1L)
            (downloadedBytes.toDouble() / denominator.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            null
        }
}

internal val TileDownloadSnapshot.isDownloading: Boolean
    get() = status == TileDownloadStatus.Downloading

internal val TileDownloadSnapshot.isCached: Boolean
    get() = status == TileDownloadStatus.Cached

internal val TileDownloadSnapshot.isError: Boolean
    get() = status == TileDownloadStatus.Error

internal fun Map<DownloadTileId, TileDownloadSnapshot>.tileIdsWithStatus(
    status: TileDownloadStatus,
): Set<DownloadTileId> {
    return entries
        .asSequence()
        .filter { (_, snapshot) -> snapshot.status == status }
        .map(Map.Entry<DownloadTileId, TileDownloadSnapshot>::key)
        .toCollection(linkedSetOf())
}

internal fun Map<DownloadTileId, TileDownloadSnapshot>.cachedTileIds(): Set<DownloadTileId> =
    tileIdsWithStatus(TileDownloadStatus.Cached)

internal fun Map<DownloadTileId, TileDownloadSnapshot>.downloadingTileIds(): Set<DownloadTileId> =
    tileIdsWithStatus(TileDownloadStatus.Downloading)

internal class TileDownloadCancellation {
    @Volatile
    private var cancelled = false
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean
        get() = cancelled

    fun cancel() {
        if (cancelled) {
            return
        }
        cancelled = true
        callbacks.forEach { callback ->
            runCatching(callback)
        }
        callbacks.clear()
    }

    fun onCancel(callback: () -> Unit) {
        if (cancelled) {
            callback()
            return
        }
        callbacks += callback
        if (cancelled) {
            callbacks.remove(callback)
            callback()
        }
    }

    fun throwIfCancelled() {
        if (cancelled) {
            throw TileDownloadCancelledException()
        }
    }
}

internal class TileDownloadCancelledException : RuntimeException("Tile download cancelled")

internal data class ScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    fun contains(point: ScreenPoint): Boolean {
        return point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
    }

    fun isFullyWithin(
        width: Float,
        height: Float,
    ): Boolean {
        return left >= 0f && top >= 0f && right <= width && bottom <= height
    }

    fun intersects(other: ScreenRect): Boolean {
        return left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top
    }

    fun intersect(other: ScreenRect): ScreenRect? {
        if (!intersects(other)) {
            return null
        }
        return ScreenRect(
            left = max(left, other.left),
            top = max(top, other.top),
            right = min(right, other.right),
            bottom = min(bottom, other.bottom),
        )
    }
}

internal data class TileRouteMetrics(
    val intersectsRoute: Boolean,
    val intersectingEdgeCount: Int,
    val intersectingRouteMeters: Double,
)

internal enum class TileGridOutlineStyle {
    Solid,
    ViewProxyDashed,
}

internal enum class TileGridDownloadState {
    Downloading,
    Cached,
    Partial,
    Error,
}

internal enum class TileGridSelectionState {
    Unselected,
    PartiallySelected,
    FullySelected,
}

private val EmptyTileRouteMetrics = TileRouteMetrics(
    intersectsRoute = false,
    intersectingEdgeCount = 0,
    intersectingRouteMeters = 0.0,
)

internal data class TileGridDisplayTile(
    val tileId: DownloadTileId,
    val screenRect: ScreenRect,
    val outlineStyle: TileGridOutlineStyle = TileGridOutlineStyle.Solid,
    val routeMetrics: TileRouteMetrics,
    val downloadState: TileGridDownloadState?,
    val progressFraction: Float?,
    val cachedCoverageTiles: List<TileCoverageRect>,
    val selectedCachedTileIds: Set<DownloadTileId>,
    val downloadRequests: List<TileDownloadRequest>,
    val estimatedBytes: Long,
    val label: String?,
) {
    val cachedTileIds: Set<DownloadTileId>
        get() = cachedCoverageTiles.mapTo(linkedSetOf(), TileCoverageRect::tileId)

    val hasCachedCoverage: Boolean
        get() = cachedCoverageTiles.isNotEmpty()

    val cachedCoverageRects: List<ScreenRect>
        get() = cachedCoverageTiles.map(TileCoverageRect::screenRect)

    val selectedCoverageTiles: List<TileCoverageRect>
        get() = cachedCoverageTiles
            .filter { coverageTile -> coverageTile.tileId in selectedCachedTileIds }

    val selectedCoverageRects: List<ScreenRect>
        get() = selectedCoverageTiles.map(TileCoverageRect::screenRect)

    val selectionState: TileGridSelectionState
        get() = when {
            selectedCachedTileIds.isEmpty() -> TileGridSelectionState.Unselected
            selectedCachedTileIds.size == cachedCoverageTiles.size -> TileGridSelectionState.FullySelected
            else -> TileGridSelectionState.PartiallySelected
        }

    val selected: Boolean
        get() = selectionState == TileGridSelectionState.FullySelected

    fun toggledSelection(currentSelectedTileIds: Set<DownloadTileId>): Set<DownloadTileId> {
        if (!hasCachedCoverage) {
            return currentSelectedTileIds
        }
        return currentSelectedTileIds.toMutableSet().also { tileIds ->
            val shouldDeselect = cachedTileIds.all(currentSelectedTileIds::contains)
            if (shouldDeselect) {
                tileIds.removeAll(cachedTileIds)
            } else {
                tileIds.addAll(cachedTileIds)
            }
        }.toSet()
    }
}

internal data class TileCoverageRect(
    val tileId: DownloadTileId,
    val screenRect: ScreenRect,
)

internal data class TileGridRenderModel(
    val tiles: List<TileGridDisplayTile>,
) {
    fun tileAt(point: ScreenPoint): TileGridDisplayTile? {
        return tiles.lastOrNull { it.screenRect.contains(point) }
    }

    fun fullyVisibleWithin(
        width: Float,
        height: Float,
    ): TileGridRenderModel {
        return TileGridRenderModel(
            tiles = tiles.filter { tile ->
                tile.screenRect.isFullyWithin(width = width, height = height)
            },
        )
    }
}

internal data class TileDownloadRequest(
    val tileId: DownloadTileId,
    val estimatedBytes: Long,
)

private fun shouldDisplayTileInRouteView(
    routeMetrics: TileRouteMetrics,
    downloadState: TileGridDownloadState?,
): Boolean {
    return routeMetrics.intersectsRoute || downloadState != null
}

internal fun normalizeOverpassTilePack(
    tileId: DownloadTileId,
    config: TileContextConfig,
    overpassJson: String,
    fetchedAtMillis: Long = System.currentTimeMillis(),
): TileContextPack {
    val root = Json.parseToJsonElement(overpassJson).jsonObject
    val elements = root["elements"]?.jsonArray ?: JsonArray(emptyList())
    val features = buildList {
        elements.forEach { element ->
            normalizeOverpassElement(element.jsonObject)?.let(::add)
        }
    }
    return TileContextPack(
        tileId = tileId,
        queryBounds = expandGeoBoundsByMeters(tileGeoBounds(tileId), config.fetchHaloMeters),
        fetchedAtMillis = fetchedAtMillis,
        features = features.sortedBy(TileContextFeature::featureId),
    )
}

internal fun TileContextPack.toJsonString(): String {
    return buildJsonObject {
        put("schemaVersion", JsonPrimitive(schemaVersion))
        put(
            "tile",
            buildJsonObject {
                put("zoom", JsonPrimitive(tileId.zoom))
                put("x", JsonPrimitive(tileId.x))
                put("y", JsonPrimitive(tileId.y))
            },
        )
        put(
            "queryBounds",
            buildJsonObject {
                put("west", JsonPrimitive(queryBounds.west))
                put("south", JsonPrimitive(queryBounds.south))
                put("east", JsonPrimitive(queryBounds.east))
                put("north", JsonPrimitive(queryBounds.north))
            },
        )
        put("fetchedAtMillis", JsonPrimitive(fetchedAtMillis))
        put(
            "features",
            buildJsonArray {
                features.forEach { feature ->
                    add(
                        buildJsonObject {
                            put("featureId", JsonPrimitive(feature.featureId))
                            put("geometryKind", JsonPrimitive(feature.geometryKind.name))
                            put(
                                "tags",
                                buildJsonObject {
                                    feature.tags.forEach { (key, value) ->
                                        put(key, JsonPrimitive(value))
                                    }
                                },
                            )
                            put(
                                "geometry",
                                buildJsonArray {
                                    feature.geometry.forEach { point ->
                                        add(
                                            buildJsonArray {
                                                add(JsonPrimitive(point.lat))
                                                add(JsonPrimitive(point.lon))
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()
}

internal fun tileContextPackFromJson(payload: String): TileContextPack {
    val root = Json.parseToJsonElement(payload).jsonObject
    val tile = root.getValue("tile").jsonObject
    val bounds = root.getValue("queryBounds").jsonObject
    val features = root["features"]?.jsonArray ?: JsonArray(emptyList())
    return TileContextPack(
        schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: TILE_CONTEXT_PACK_SCHEMA_VERSION,
        tileId = DownloadTileId(
            zoom = tile.getValue("zoom").jsonPrimitive.int,
            x = tile.getValue("x").jsonPrimitive.int,
            y = tile.getValue("y").jsonPrimitive.int,
        ),
        queryBounds = GeoBounds(
            west = bounds.getValue("west").jsonPrimitive.double,
            south = bounds.getValue("south").jsonPrimitive.double,
            east = bounds.getValue("east").jsonPrimitive.double,
            north = bounds.getValue("north").jsonPrimitive.double,
        ),
        fetchedAtMillis = root["fetchedAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
        features = buildList {
            features.forEach { featureElement ->
                val feature = featureElement.jsonObject
                val geometry = feature["geometry"]?.jsonArray ?: JsonArray(emptyList())
                add(
                    TileContextFeature(
                        featureId = feature.getValue("featureId").jsonPrimitive.content,
                        geometryKind = enumValueOf(feature.getValue("geometryKind").jsonPrimitive.content),
                        tags = feature.getValue("tags").jsonObject.let { tagsObject ->
                            buildMap {
                                tagsObject.forEach { (key, value) ->
                                    put(key, value.jsonPrimitive.content)
                                }
                            }
                        },
                        geometry = buildList {
                            geometry.forEach { coordinateElement ->
                                val coordinate = coordinateElement.jsonArray
                                add(
                                    GeoPoint(
                                        lat = coordinate[0].jsonPrimitive.double,
                                        lon = coordinate[1].jsonPrimitive.double,
                                    ),
                                )
                            }
                        },
                    ),
                )
            }
        },
    )
}

internal fun buildTileGridRenderModel(
    routeModel: RouteModel,
    bounds: Bounds,
    canvasWidth: Float,
    canvasHeight: Float,
    config: TileContextConfig,
    tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    selectedTileIds: Set<DownloadTileId> = emptySet(),
): TileGridRenderModel {
    if (canvasWidth <= 0f || canvasHeight <= 0f) {
        return TileGridRenderModel(emptyList())
    }
    val tileResolution = resolveTileResolution(
        windowWidthMeters = max(1.0, bounds.maxX - bounds.minX),
        policy = config.resolutionPolicy,
    )
    val displayRouteMetricsById = buildRouteTileMetricsIndex(
        routeModel = routeModel,
        config = config,
        zoom = tileResolution.displayZoom,
    )
    val dataRouteMetricsById = buildRouteTileMetricsIndex(
        routeModel = routeModel,
        config = config,
        zoom = tileResolution.dataZoom,
    )
    val dataTileRequestsByDisplayTileId = buildDisplayTileDownloadRequests(
        routeTileMetricsById = dataRouteMetricsById,
        displayZoom = tileResolution.displayZoom,
        tileSnapshots = tileSnapshots,
    )
    val visibleTileIds = tilesIntersectingProjectedBounds(
        projection = routeModel.projection,
        bounds = bounds,
        zoom = tileResolution.displayZoom,
    )
    val cachedAverageBytes = cachedAverageBytes(tileSnapshots)
    val viewportScreenRect = ScreenRect(
        left = 0f,
        top = 0f,
        right = canvasWidth,
        bottom = canvasHeight,
    )
    val tiles = visibleTileIds.mapNotNull { tileId ->
        val geoBounds = tileGeoBounds(tileId)
        val projectedBounds = projectedBoundsForGeoBounds(geoBounds, routeModel.projection)
        val actualScreenRect = projectedBoundsToScreenRect(
            projectedBounds = projectedBounds,
            viewBounds = bounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
        )
        val routeMetrics = displayRouteMetricsById[tileId] ?: EmptyTileRouteMetrics
        val (displayRect, outlineStyle) = resolveTileDisplayRect(
            screenRect = actualScreenRect,
            routeMetrics = routeMetrics,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
        )
        val representation = resolveDisplayTileRepresentation(
            displayTileId = tileId,
            displayZoom = tileResolution.displayZoom,
            displayRect = displayRect,
            outlineStyle = outlineStyle,
            viewportScreenRect = viewportScreenRect,
            routeModel = routeModel,
            viewBounds = bounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            downloadRequests = dataTileRequestsByDisplayTileId[tileId].orEmpty(),
            tileSnapshots = tileSnapshots,
        )
        val downloadState = resolveDisplayTileDownloadState(
            downloadRequests = representation.downloadRequests,
            tileSnapshots = tileSnapshots,
            hasCachedCoverage = representation.cachedCoverage.coverageTiles.isNotEmpty(),
        )
        if (!shouldDisplayTileInRouteView(routeMetrics, downloadState.state)) {
            return@mapNotNull null
        }
        val estimatedBytes = if (representation.downloadRequests.isNotEmpty()) {
            representation.downloadRequests.sumOf(TileDownloadRequest::estimatedBytes)
        } else {
            estimateTileBytes(routeMetrics, cachedAverageBytes)
        }
        TileGridDisplayTile(
            tileId = tileId,
            screenRect = displayRect,
            outlineStyle = outlineStyle,
            routeMetrics = routeMetrics,
            downloadState = downloadState.state,
            progressFraction = downloadState.progressFraction,
            cachedCoverageTiles = representation.cachedCoverage.coverageTiles,
            selectedCachedTileIds = representation.cachedCoverage.coverageTiles
                .mapTo(linkedSetOf()) { coverageTile -> coverageTile.tileId }
                .intersect(selectedTileIds),
            downloadRequests = representation.downloadRequests,
            estimatedBytes = estimatedBytes,
            label = tileLabel(
                routeMetrics = routeMetrics,
                downloadState = downloadState.state,
                progressFraction = downloadState.progressFraction,
                estimatedBytes = estimatedBytes,
                minDimensionPx = min(displayRect.width, displayRect.height),
            ),
        )
    }

    return TileGridRenderModel(tiles)
}

private fun resolveDisplayTileRepresentation(
    displayTileId: DownloadTileId,
    displayZoom: Int,
    displayRect: ScreenRect,
    outlineStyle: TileGridOutlineStyle,
    viewportScreenRect: ScreenRect,
    routeModel: RouteModel,
    viewBounds: Bounds,
    canvasWidth: Float,
    canvasHeight: Float,
    downloadRequests: List<TileDownloadRequest>,
    tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
): DisplayTileRepresentation {
    val representedScreenRect = when (outlineStyle) {
        TileGridOutlineStyle.ViewProxyDashed -> viewportScreenRect
        TileGridOutlineStyle.Solid -> displayRect
    }
    val representedDownloadRequests = downloadRequests.filter { request ->
        request.tileId.intersectsRepresentedScreenRect(
            routeModel = routeModel,
            viewBounds = viewBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            representedScreenRect = representedScreenRect,
        )
    }
    val cachedTileIds = tileSnapshots
        .mapNotNull { (tileId, snapshot) ->
            if (!snapshot.isCached) {
                return@mapNotNull null
            }
            if (!tileId.representsDisplayTile(displayTileId, displayZoom)) {
                return@mapNotNull null
            }
            if (!tileId.intersectsRepresentedScreenRect(
                routeModel = routeModel,
                viewBounds = viewBounds,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                representedScreenRect = representedScreenRect,
            )) {
                return@mapNotNull null
            }
            tileId
        }
        .sortedBy(DownloadTileId::cacheKey)
        .toCollection(linkedSetOf())
    return DisplayTileRepresentation(
        downloadRequests = representedDownloadRequests,
        cachedCoverage = DisplayTileCoverage(
            coverageTiles = cachedTileIds.mapNotNull { cachedTileId ->
                cachedTileId.screenRectInViewport(
                    routeModel = routeModel,
                    viewBounds = viewBounds,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                ).intersect(representedScreenRect)?.let { coverageRect ->
                    TileCoverageRect(
                        tileId = cachedTileId,
                        screenRect = coverageRect,
                    )
                }
            },
        ),
    )
}

private fun resolveTileDisplayRect(
    screenRect: ScreenRect,
    routeMetrics: TileRouteMetrics,
    canvasWidth: Float,
    canvasHeight: Float,
): Pair<ScreenRect, TileGridOutlineStyle> {
    if (shouldUseViewportProxyOutline(screenRect, routeMetrics, canvasWidth, canvasHeight)) {
        return buildViewportProxyTileRect(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
        ) to TileGridOutlineStyle.ViewProxyDashed
    }
    return screenRect to TileGridOutlineStyle.Solid
}

internal fun shouldUseViewportProxyOutline(
    screenRect: ScreenRect,
    routeMetrics: TileRouteMetrics,
    canvasWidth: Float,
    canvasHeight: Float,
): Boolean {
    if (!routeMetrics.intersectsRoute) {
        return false
    }
    return screenRect.left < -TILE_GRID_PROXY_EDGE_MARGIN_PX &&
        screenRect.top < -TILE_GRID_PROXY_EDGE_MARGIN_PX &&
        screenRect.right > canvasWidth + TILE_GRID_PROXY_EDGE_MARGIN_PX &&
        screenRect.bottom > canvasHeight + TILE_GRID_PROXY_EDGE_MARGIN_PX
}

private fun buildViewportProxyTileRect(
    canvasWidth: Float,
    canvasHeight: Float,
): ScreenRect {
    val minDimension = min(canvasWidth, canvasHeight)
    val inset = (minDimension * 0.08f).coerceIn(18f, 56f)
    return ScreenRect(
        left = inset,
        top = inset,
        right = max(inset, canvasWidth - inset),
        bottom = max(inset, canvasHeight - inset),
    )
}

internal fun buildRouteTileMetricsIndex(
    routeModel: RouteModel,
    config: TileContextConfig,
    zoom: Int = config.downloadZoom,
): Map<DownloadTileId, TileRouteMetrics> {
    val geoBounds = geoBoundsForProjectedBounds(routeModel.bounds, routeModel.projection)
    val expandedGeoBounds = expandGeoBoundsByMeters(geoBounds, config.fetchHaloMeters)
    val candidates = tilesForGeoBounds(expandedGeoBounds, zoom)
    return buildMap(candidates.size) {
        candidates.forEach { tileId ->
            val metrics = tileRouteMetrics(
                routeModel = routeModel,
                tileBounds = projectedBoundsForGeoBounds(tileGeoBounds(tileId), routeModel.projection),
                haloMeters = config.fetchHaloMeters,
            )
            if (metrics.intersectsRoute) {
                put(tileId, metrics)
            }
        }
    }
}

internal fun tilesForRoute(
    routeModel: RouteModel,
    config: TileContextConfig,
): Set<DownloadTileId> {
    return buildRouteTileMetricsIndex(routeModel, config).keys
}

private data class ResolvedDisplayTileDownloadState(
    val state: TileGridDownloadState?,
    val progressFraction: Float?,
)

private data class DisplayTileCoverage(
    val coverageTiles: List<TileCoverageRect>,
)

private data class DisplayTileRepresentation(
    val downloadRequests: List<TileDownloadRequest>,
    val cachedCoverage: DisplayTileCoverage,
)

private fun buildDisplayTileDownloadRequests(
    routeTileMetricsById: Map<DownloadTileId, TileRouteMetrics>,
    displayZoom: Int,
    tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
): Map<DownloadTileId, List<TileDownloadRequest>> {
    val cachedAverageBytes = cachedAverageBytes(tileSnapshots)
    return routeTileMetricsById.entries
        .groupBy(
            keySelector = { (tileId, _) -> parentTileIdAtZoom(tileId, displayZoom) },
            valueTransform = { (tileId, routeMetrics) ->
                TileDownloadRequest(
                    tileId = tileId,
                    estimatedBytes = tileSnapshots[tileId]?.actualBytes
                        ?: estimateTileBytes(routeMetrics, cachedAverageBytes),
                )
            },
        )
        .mapValues { (_, requests) ->
            requests.sortedBy { request -> request.tileId.cacheKey }
        }
}

private fun resolveDisplayTileDownloadState(
    downloadRequests: List<TileDownloadRequest>,
    tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
    hasCachedCoverage: Boolean,
): ResolvedDisplayTileDownloadState {
    if (downloadRequests.isEmpty()) {
        return ResolvedDisplayTileDownloadState(
            state = if (hasCachedCoverage) TileGridDownloadState.Partial else null,
            progressFraction = null,
        )
    }
    val snapshots = downloadRequests.mapNotNull { request ->
        tileSnapshots[request.tileId]?.let { snapshot -> request to snapshot }
    }
    if (snapshots.isEmpty()) {
        return ResolvedDisplayTileDownloadState(
            state = if (hasCachedCoverage) TileGridDownloadState.Partial else null,
            progressFraction = null,
        )
    }
    val cachedCount = snapshots.count { (_, snapshot) -> snapshot.isCached }
    val hasDownloading = snapshots.any { (_, snapshot) -> snapshot.isDownloading }
    val hasError = snapshots.any { (_, snapshot) -> snapshot.isError }
    val state = when {
        hasDownloading -> TileGridDownloadState.Downloading
        cachedCount == downloadRequests.size -> TileGridDownloadState.Cached
        cachedCount > 0 -> TileGridDownloadState.Partial
        hasCachedCoverage -> TileGridDownloadState.Partial
        hasError -> TileGridDownloadState.Error
        else -> null
    }
    val progressFraction = if (hasDownloading) {
        val totalBytes = downloadRequests.sumOf { request -> request.estimatedBytes.coerceAtLeast(1L) }
        val downloadedBytes = downloadRequests.sumOf { request ->
            when (val snapshot = tileSnapshots[request.tileId]) {
                null -> 0L
                else -> when (snapshot.status) {
                    TileDownloadStatus.Cached -> snapshot.actualBytes ?: request.estimatedBytes
                    TileDownloadStatus.Downloading -> snapshot.downloadedBytes
                    TileDownloadStatus.Error -> 0L
                }
            }
        }
        (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else {
        null
    }
    return ResolvedDisplayTileDownloadState(
        state = state,
        progressFraction = progressFraction,
    )
}

private fun parentTileIdAtZoom(
    tileId: DownloadTileId,
    parentZoom: Int,
): DownloadTileId {
    if (tileId.zoom <= parentZoom) {
        return tileId
    }
    val zoomDelta = tileId.zoom - parentZoom
    return DownloadTileId(
        zoom = parentZoom,
        x = tileId.x shr zoomDelta,
        y = tileId.y shr zoomDelta,
    )
}

private fun DownloadTileId.screenRectInViewport(
    routeModel: RouteModel,
    viewBounds: Bounds,
    canvasWidth: Float,
    canvasHeight: Float,
): ScreenRect {
    return projectedBoundsToScreenRect(
        projectedBounds = projectedBoundsForGeoBounds(
            tileGeoBounds(this),
            routeModel.projection,
        ),
        viewBounds = viewBounds,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )
}

private fun DownloadTileId.intersectsRepresentedScreenRect(
    routeModel: RouteModel,
    viewBounds: Bounds,
    canvasWidth: Float,
    canvasHeight: Float,
    representedScreenRect: ScreenRect,
): Boolean {
    return screenRectInViewport(
        routeModel = routeModel,
        viewBounds = viewBounds,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    ).intersects(representedScreenRect)
}

private fun DownloadTileId.representsDisplayTile(
    displayTileId: DownloadTileId,
    displayZoom: Int,
): Boolean {
    return parentTileIdAtZoom(this, displayZoom) == displayTileId ||
        tileContainsTile(this, displayTileId)
}

internal fun childTileScreenRectWithinDisplayTile(
    displayTileId: DownloadTileId,
    displayScreenRect: ScreenRect,
    childTileId: DownloadTileId,
): ScreenRect {
    require(childTileId.zoom >= displayTileId.zoom) {
        "Child tile ${childTileId.cacheKey} must not be coarser than display tile ${displayTileId.cacheKey}"
    }
    if (childTileId.zoom == displayTileId.zoom) {
        return displayScreenRect
    }
    val zoomDelta = childTileId.zoom - displayTileId.zoom
    val gridSize = 1 shl zoomDelta
    val childOffsetX = childTileId.x - (displayTileId.x shl zoomDelta)
    val childOffsetY = childTileId.y - (displayTileId.y shl zoomDelta)
    val cellWidth = displayScreenRect.width / gridSize.toFloat()
    val cellHeight = displayScreenRect.height / gridSize.toFloat()
    return ScreenRect(
        left = displayScreenRect.left + cellWidth * childOffsetX.toFloat(),
        top = displayScreenRect.top + cellHeight * childOffsetY.toFloat(),
        right = displayScreenRect.left + cellWidth * (childOffsetX + 1).toFloat(),
        bottom = displayScreenRect.top + cellHeight * (childOffsetY + 1).toFloat(),
    )
}

internal fun tileIdForGeoPoint(
    point: GeoPoint,
    zoom: Int,
): DownloadTileId {
    val tileCount = 1 shl zoom
    val x = floor(longitudeToTileX(normalizeLongitude(point.lon), zoom)).toInt().coerceIn(0, tileCount - 1)
    val y = floor(latitudeToTileY(point.lat, zoom)).toInt().coerceIn(0, tileCount - 1)
    return DownloadTileId(zoom = zoom, x = x, y = y)
}

internal fun neighboringTileIds(
    centerTile: DownloadTileId,
    radius: Int,
): Set<DownloadTileId> {
    val tileCount = 1 shl centerTile.zoom
    return buildSet {
        for (x in max(0, centerTile.x - radius)..min(tileCount - 1, centerTile.x + radius)) {
            for (y in max(0, centerTile.y - radius)..min(tileCount - 1, centerTile.y + radius)) {
                add(DownloadTileId(zoom = centerTile.zoom, x = x, y = y))
            }
        }
    }
}

internal fun tileGeoBounds(tileId: DownloadTileId): GeoBounds {
    val tileCount = 1 shl tileId.zoom
    val west = tileId.x.toDouble() / tileCount * 360.0 - 180.0
    val east = (tileId.x + 1).toDouble() / tileCount * 360.0 - 180.0
    val north = mercatorTileYToLat(tileId.y, tileId.zoom)
    val south = mercatorTileYToLat(tileId.y + 1, tileId.zoom)
    return GeoBounds(
        west = west,
        south = south,
        east = east,
        north = north,
    )
}

internal fun expandGeoBoundsByMeters(bounds: GeoBounds, meters: Double): GeoBounds {
    if (meters <= 0.0) {
        return bounds
    }
    val latitudeDelta = Math.toDegrees(meters / TILE_CONTEXT_EARTH_RADIUS_METERS)
    val centerLatitude = ((bounds.north + bounds.south) / 2.0).coerceIn(-WEB_MERCATOR_MAX_LAT, WEB_MERCATOR_MAX_LAT)
    val longitudeScale = cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.01)
    val longitudeDelta = Math.toDegrees(meters / (TILE_CONTEXT_EARTH_RADIUS_METERS * longitudeScale))
    return GeoBounds(
        west = bounds.west - longitudeDelta,
        south = max(-WEB_MERCATOR_MAX_LAT, bounds.south - latitudeDelta),
        east = bounds.east + longitudeDelta,
        north = min(WEB_MERCATOR_MAX_LAT, bounds.north + latitudeDelta),
    )
}

internal fun tilesIntersectingProjectedBounds(
    projection: Projection,
    bounds: Bounds,
    zoom: Int,
): List<DownloadTileId> {
    val geoBounds = geoBoundsForProjectedBounds(bounds, projection)
    return tilesForGeoBounds(geoBounds, zoom)
}

internal fun tileIntersectsProjectedBounds(
    tileId: DownloadTileId,
    projection: Projection,
    bounds: Bounds,
): Boolean {
    val tileBounds = projectedBoundsForGeoBounds(tileGeoBounds(tileId), projection)
    return tileBounds.minX <= bounds.maxX &&
        tileBounds.maxX >= bounds.minX &&
        tileBounds.minY <= bounds.maxY &&
        tileBounds.maxY >= bounds.minY
}

internal fun Collection<DownloadTileId>.tilesIntersectingProjectedBounds(
    projection: Projection,
    bounds: Bounds,
): Set<DownloadTileId> {
    return filterTo(linkedSetOf()) { tileId ->
        tileIntersectsProjectedBounds(
            tileId = tileId,
            projection = projection,
            bounds = bounds,
        )
    }
}

internal fun Collection<DownloadTileId>.tilesIntersectingRoute(
    routeModel: RouteModel,
    config: TileContextConfig,
): Set<DownloadTileId> {
    return filterTo(linkedSetOf()) { tileId ->
        tileRouteMetrics(
            routeModel = routeModel,
            tileBounds = projectedBoundsForGeoBounds(tileGeoBounds(tileId), routeModel.projection),
            haloMeters = config.fetchHaloMeters,
        ).intersectsRoute
    }
}

private fun normalizeOverpassElement(element: JsonObject): TileContextFeature? {
    val type = element["type"]?.jsonPrimitive?.contentOrNull
    val tags = element["tags"]?.jsonObject ?: return null
    return when (type) {
        "way" -> {
            if ("highway" !in tags) {
                null
            } else {
                val geometry = parseGeometryArray(element["geometry"]?.jsonArray)
                if (geometry.size < 2) {
                    null
                } else {
                    TileContextFeature(
                        featureId = "way/${element.getValue("id").jsonPrimitive.long}",
                        geometryKind = TileGeometryKind.Way,
                        tags = filterTags(tags, WAY_TAG_ALLOWLIST),
                        geometry = geometry,
                    )
                }
            }
        }
        "node" -> {
            val filteredTags = filterTags(tags, POINT_TAG_ALLOWLIST)
            if (filteredTags.isEmpty()) {
                null
            } else {
                val lat = element["lat"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN
                val lon = element["lon"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN
                if (lat.isNaN() || lon.isNaN()) {
                    null
                } else {
                    TileContextFeature(
                        featureId = "node/${element.getValue("id").jsonPrimitive.long}",
                        geometryKind = TileGeometryKind.Point,
                        tags = filteredTags,
                        geometry = listOf(GeoPoint(lat = lat, lon = lon)),
                    )
                }
            }
        }
        else -> null
    }
}

private fun parseGeometryArray(geometryArray: JsonArray?): List<GeoPoint> {
    if (geometryArray == null) {
        return emptyList()
    }
    return buildList {
        geometryArray.forEach { coordinateElement ->
            val coordinate = coordinateElement as? JsonObject ?: return@forEach
            val lat = coordinate["lat"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            val lon = coordinate["lon"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            add(GeoPoint(lat = lat, lon = lon))
        }
    }
}

private fun filterTags(
    source: JsonObject,
    allowlist: Set<String>,
): Map<String, String> {
    return buildMap {
        source.forEach { (key, value) ->
            if (key in allowlist) {
                put(key, value.jsonPrimitive.content)
            }
        }
    }
}

private fun tilesForGeoBounds(
    bounds: GeoBounds,
    zoom: Int,
): List<DownloadTileId> {
    val tileCount = 1 shl zoom
    val west = normalizeLongitude(bounds.west)
    val east = normalizeLongitude(bounds.east)
    val clampedNorth = bounds.north.coerceIn(-WEB_MERCATOR_MAX_LAT, WEB_MERCATOR_MAX_LAT)
    val clampedSouth = bounds.south.coerceIn(-WEB_MERCATOR_MAX_LAT, WEB_MERCATOR_MAX_LAT)
    val xStart = floor(longitudeToTileX(west, zoom)).toInt().coerceIn(0, tileCount - 1)
    val xEnd = floor(longitudeToTileX(east, zoom)).toInt().coerceIn(0, tileCount - 1)
    val yStart = floor(latitudeToTileY(clampedNorth, zoom)).toInt().coerceIn(0, tileCount - 1)
    val yEnd = floor(latitudeToTileY(clampedSouth, zoom)).toInt().coerceIn(0, tileCount - 1)

    val tiles = mutableListOf<DownloadTileId>()
    for (x in min(xStart, xEnd)..max(xStart, xEnd)) {
        for (y in min(yStart, yEnd)..max(yStart, yEnd)) {
            tiles += DownloadTileId(zoom = zoom, x = x, y = y)
        }
    }
    return tiles
}

internal fun projectedBoundsToScreenRect(
    projectedBounds: Bounds,
    viewBounds: Bounds,
    canvasWidth: Float,
    canvasHeight: Float,
): ScreenRect {
    val topLeft = projectedPointToScreenPoint(
        point = ProjectedPoint(projectedBounds.minX, projectedBounds.maxY),
        bounds = viewBounds,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )
    val bottomRight = projectedPointToScreenPoint(
        point = ProjectedPoint(projectedBounds.maxX, projectedBounds.minY),
        bounds = viewBounds,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    )
    return ScreenRect(
        left = min(topLeft.x, bottomRight.x),
        top = min(topLeft.y, bottomRight.y),
        right = max(topLeft.x, bottomRight.x),
        bottom = max(topLeft.y, bottomRight.y),
    )
}

internal fun tileRouteMetrics(
    routeModel: RouteModel,
    tileBounds: Bounds,
    haloMeters: Double,
): TileRouteMetrics {
    val expandedTileBounds = Bounds(
        minX = tileBounds.minX - haloMeters,
        maxX = tileBounds.maxX + haloMeters,
        minY = tileBounds.minY - haloMeters,
        maxY = tileBounds.maxY + haloMeters,
    )
    var intersectingEdgeCount = 0
    var intersectingRouteMeters = 0.0
    routeModel.edges.forEach { edge ->
        if (edgeBoundsIntersect(edge.bounds, expandedTileBounds)) {
            intersectingEdgeCount += 1
            intersectingRouteMeters += edge.lengthMeters
        }
    }
    return TileRouteMetrics(
        intersectsRoute = intersectingEdgeCount > 0,
        intersectingEdgeCount = intersectingEdgeCount,
        intersectingRouteMeters = intersectingRouteMeters,
    )
}

private fun cachedAverageBytes(
    tileSnapshots: Map<DownloadTileId, TileDownloadSnapshot>,
): Long? {
    val cachedBytes = tileSnapshots.values
        .filter { it.isCached }
        .mapNotNull { it.actualBytes }
    if (cachedBytes.isEmpty()) {
        return null
    }
    return cachedBytes.average().roundToInt().toLong()
}

private fun estimateTileBytes(
    routeMetrics: TileRouteMetrics,
    cachedAverageBytes: Long?,
): Long {
    val baseBytes = cachedAverageBytes?.let { average ->
        if (routeMetrics.intersectsRoute) {
            max(average, APPROX_TILE_ESTIMATE_BYTES)
        } else {
            max((average * 0.6).roundToInt().toLong(), APPROX_TILE_ESTIMATE_BYTES / 2)
        }
    } ?: APPROX_TILE_ESTIMATE_BYTES
    val routeBonus = if (routeMetrics.intersectsRoute) ROUTE_TILE_ESTIMATE_BONUS_BYTES else 0L
    val edgeBonus = min(
        MAX_EDGE_ESTIMATE_BONUS_BYTES,
        routeMetrics.intersectingEdgeCount.toLong() * EDGE_ESTIMATE_BONUS_BYTES,
    )
    val lengthBonus = min(
        MAX_ROUTE_LENGTH_ESTIMATE_BONUS_BYTES,
        (routeMetrics.intersectingRouteMeters * ROUTE_LENGTH_ESTIMATE_BYTES_PER_METER).roundToInt().toLong(),
    )
    return baseBytes + routeBonus + edgeBonus + lengthBonus
}

private fun tileLabel(
    routeMetrics: TileRouteMetrics,
    downloadState: TileGridDownloadState?,
    progressFraction: Float?,
    estimatedBytes: Long,
    minDimensionPx: Float,
): String? {
    return when (downloadState) {
        TileGridDownloadState.Downloading -> {
            if (minDimensionPx < 92f) {
                return null
            }
            val percent = ((progressFraction ?: 0f) * 100f).roundToInt()
            "$percent%"
        }
        TileGridDownloadState.Error -> {
            if (minDimensionPx < 92f) {
                return null
            }
            "Error"
        }
        TileGridDownloadState.Cached -> {
            if (!routeMetrics.intersectsRoute || minDimensionPx < 118f) {
                return null
            }
            formatTileMegabytes(estimatedBytes, approximate = false)
        }
        TileGridDownloadState.Partial -> {
            null
        }
        null -> {
            if (!routeMetrics.intersectsRoute || minDimensionPx < 118f) {
                return null
            }
            formatTileMegabytes(estimatedBytes, approximate = true)
        }
    }
}

private fun formatTileMegabytes(
    bytes: Long,
    approximate: Boolean,
): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    val prefix = if (approximate) "~" else ""
    return String.format(Locale.US, "%s%.1f MB", prefix, megabytes)
}

private fun normalizeLongitude(longitude: Double): Double {
    return longitude.coerceIn(-180.0, 180.0)
}

private fun longitudeToTileX(longitude: Double, zoom: Int): Double {
    val tileCount = 1 shl zoom
    return ((longitude + 180.0) / 360.0) * tileCount
}

private fun latitudeToTileY(latitude: Double, zoom: Int): Double {
    val tileCount = 1 shl zoom
    val radians = Math.toRadians(latitude.coerceIn(-WEB_MERCATOR_MAX_LAT, WEB_MERCATOR_MAX_LAT))
    return (1.0 - asinh(kotlin.math.tan(radians)) / PI) / 2.0 * tileCount
}

private fun mercatorTileYToLat(tileY: Int, zoom: Int): Double {
    val tileCount = 1 shl zoom
    val mercator = PI * (1.0 - 2.0 * tileY.toDouble() / tileCount.toDouble())
    return Math.toDegrees(atan(sinh(mercator)))
}

private fun edgeBoundsIntersect(
    edgeBounds: Bounds,
    tileBounds: Bounds,
): Boolean {
    return edgeBounds.maxX >= tileBounds.minX &&
        edgeBounds.minX <= tileBounds.maxX &&
        edgeBounds.maxY >= tileBounds.minY &&
        edgeBounds.minY <= tileBounds.maxY
}
