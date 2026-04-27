package dev.ra.geepee

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.FutureTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val TILE_CONTEXT_SCHEMA_VERSION = 1
private const val OVERPASS_CONNECT_TIMEOUT_MILLIS = 20_000
private const val OVERPASS_READ_TIMEOUT_MILLIS = 90_000
private const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
private const val TILE_CONTEXT_REPOSITORY_LOG_TAG = "TileContextRepository"
private const val TILE_RUNTIME_MEMORY_CACHE_LIMIT = 32
private const val ROUTE_TILE_OVERLAY_MEMORY_CACHE_LIMIT = 96

private enum class RouteTileOverlayLoadMode {
    CachedOnly,
    BuildIfMissing,
}

internal class TileContextRepository(
    private val cacheRoot: File,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "tile-context/v$TILE_CONTEXT_SCHEMA_VERSION"),
    )

    private val manifestFile = File(cacheRoot, "manifest.json")
    private val runtimeRoot = File(cacheRoot, "runtime")
    private val routeOverlayRoot = File(cacheRoot, "route-overlays")
    private val cachedTiles = linkedMapOf<DownloadTileId, TileDownloadSnapshot>()
    private val runtimePackCache = accessOrderCache<DownloadTileId, TileRuntimePack>(TILE_RUNTIME_MEMORY_CACHE_LIMIT)
    private val routeTileOverlayCache =
        accessOrderCache<RouteTileOverlayCacheKey, RouteTileOverlay>(ROUTE_TILE_OVERLAY_MEMORY_CACHE_LIMIT)
    private val routeTileOverlayLoadsInFlight = mutableMapOf<RouteTileOverlayCacheKey, FutureTask<RouteTileOverlay?>>()
    private val derivedCacheLock = Any()

    init {
        cacheRoot.mkdirs()
        cachedTiles.putAll(loadManifest())
    }

    @Synchronized
    fun cachedTileSnapshots(): Map<DownloadTileId, TileDownloadSnapshot> {
        return cachedTiles.toMap()
    }

    fun loadTilePack(tileId: DownloadTileId): TileContextPack? {
        val tileFile = tileFileFor(tileId)
        if (!tileFile.exists()) {
            return null
        }
        return runCatching {
            tileContextPackFromJson(tileFile.readText())
        }.getOrNull()
    }

    fun loadTilePacks(tileIds: Collection<DownloadTileId>): List<TileContextPack> {
        return tileIds.mapNotNull(::loadTilePack)
    }

    fun loadRuntimePack(tileId: DownloadTileId): TileRuntimePack? {
        synchronized(derivedCacheLock) {
            runtimePackCache[tileId]?.let { return it }
        }
        val runtimeFile = runtimeFileFor(tileId)
        if (runtimeFile.exists()) {
            runCatching {
                tileRuntimePackFromByteArray(runtimeFile.readBytes())
            }.getOrNull()?.let { restored ->
                cacheRuntimePack(restored)
                return restored
            }
        }
        val sourcePack = loadTilePack(tileId) ?: return null
        return runCatching {
            compileTileRuntimePack(sourcePack).also { compiled ->
                persistRuntimePack(compiled)
                cacheRuntimePack(compiled)
            }
        }.getOrNull()
    }

    fun loadRuntimePacks(tileIds: Collection<DownloadTileId>): List<TileRuntimePack> {
        return tileIds.mapNotNull(::loadRuntimePack)
    }

    fun loadRouteTileOverlayBundle(
        routeModel: RouteModel,
        tileId: DownloadTileId,
        config: TileContextConfig,
    ): RouteTileOverlayBundle? {
        val routeFingerprint = routeFingerprint(routeModel)
        return loadRouteTileOverlayBundle(
            routeModel = routeModel,
            routeFingerprint = routeFingerprint,
            tileId = tileId,
            config = config,
            loadMode = RouteTileOverlayLoadMode.BuildIfMissing,
        )
    }

    private fun loadRouteTileOverlayBundle(
        routeModel: RouteModel,
        routeFingerprint: String,
        tileId: DownloadTileId,
        config: TileContextConfig,
        loadMode: RouteTileOverlayLoadMode,
    ): RouteTileOverlayBundle? {
        val runtimePack = loadRuntimePack(tileId) ?: return null
        val cacheKey = RouteTileOverlayCacheKey(
            routeFingerprint = routeFingerprint,
            tileId = runtimePack.tileId,
            fetchedAtMillis = runtimePack.fetchedAtMillis,
        )
        synchronized(derivedCacheLock) {
            routeTileOverlayCache[cacheKey]?.let { cachedOverlay ->
                return RouteTileOverlayBundle(runtimePack = runtimePack, overlay = cachedOverlay)
            }
        }
        val overlayFile = routeOverlayFileFor(cacheKey)
        if (loadMode == RouteTileOverlayLoadMode.CachedOnly) {
            val cachedOverlay = if (overlayFile.exists()) {
                runCatching {
                    routeTileOverlayFromByteArray(overlayFile.readBytes())
                }.getOrNull()
            } else {
                null
            }
            cachedOverlay?.let(::cacheRouteTileOverlay)
            return cachedOverlay?.let { RouteTileOverlayBundle(runtimePack = runtimePack, overlay = it) }
        }
        val overlayTask: FutureTask<RouteTileOverlay?>
        val createdTask: Boolean
        synchronized(derivedCacheLock) {
            routeTileOverlayCache[cacheKey]?.let { cachedOverlay ->
                return RouteTileOverlayBundle(runtimePack = runtimePack, overlay = cachedOverlay)
            }
            val existingTask = routeTileOverlayLoadsInFlight[cacheKey]
            if (existingTask != null) {
                overlayTask = existingTask
                createdTask = false
            } else {
                overlayTask = FutureTask<RouteTileOverlay?> {
                    if (overlayFile.exists()) {
                        runCatching {
                            routeTileOverlayFromByteArray(overlayFile.readBytes())
                        }.getOrNull()
                    } else {
                        runCatching {
                            buildRouteTileOverlay(
                                routeModel = routeModel,
                                runtimePack = runtimePack,
                                config = config,
                            ).also { compiled ->
                                persistRouteTileOverlay(routeFingerprint, compiled)
                            }
                        }.getOrNull()
                    }
                }
                routeTileOverlayLoadsInFlight[cacheKey] = overlayTask
                createdTask = true
            }
        }
        val overlay = try {
            if (createdTask) {
                overlayTask.run()
            }
            runCatching { overlayTask.get() }.getOrNull()
        } finally {
            if (createdTask) {
                synchronized(derivedCacheLock) {
                    routeTileOverlayLoadsInFlight.remove(cacheKey, overlayTask)
                }
            }
        }
        overlay?.let(::cacheRouteTileOverlay)
        return overlay?.let { RouteTileOverlayBundle(runtimePack = runtimePack, overlay = it) }
    }

    fun loadRouteTileOverlayBundles(
        routeModel: RouteModel,
        tileIds: Collection<DownloadTileId>,
        config: TileContextConfig,
    ): List<RouteTileOverlayBundle> {
        val routeFingerprint = routeFingerprint(routeModel)
        return tileIds.mapNotNull { tileId ->
            loadRouteTileOverlayBundle(
                routeModel = routeModel,
                routeFingerprint = routeFingerprint,
                tileId = tileId,
                config = config,
                loadMode = RouteTileOverlayLoadMode.BuildIfMissing,
            )
        }
    }

    fun peekCachedRouteTileOverlayBundles(
        routeModel: RouteModel,
        tileIds: Collection<DownloadTileId>,
        config: TileContextConfig,
    ): List<RouteTileOverlayBundle> {
        val routeFingerprint = routeFingerprint(routeModel)
        return tileIds.mapNotNull { tileId ->
            loadRouteTileOverlayBundle(
                routeModel = routeModel,
                routeFingerprint = routeFingerprint,
                tileId = tileId,
                config = config,
                loadMode = RouteTileOverlayLoadMode.CachedOnly,
            )
        }
    }

    internal fun storeTilePack(pack: TileContextPack) {
        val tileFile = tileFileFor(pack.tileId).apply { parentFile?.mkdirs() }
        tileFile.writeText(pack.toJsonString())
        invalidateDerivedCaches(pack.tileId)
        val runtimePack = compileTileRuntimePack(pack)
        persistRuntimePack(runtimePack)
        cacheRuntimePack(runtimePack)
    }

    @Throws(IOException::class)
    fun downloadTile(
        tileId: DownloadTileId,
        config: TileContextConfig,
        cancellation: TileDownloadCancellation,
        onProgress: (downloadedBytes: Long, contentLengthBytes: Long?) -> Unit,
    ): TileDownloadSnapshot {
        cancellation.throwIfCancelled()
        val queryBounds = expandGeoBoundsByMeters(tileGeoBounds(tileId), config.fetchHaloMeters)
        val connection = openOverpassConnection(buildOverpassQuery(queryBounds))
        cancellation.onCancel {
            connection.disconnect()
        }
        try {
            cancellation.throwIfCancelled()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException(
                    errorText?.takeIf { it.isNotBlank() }
                        ?: "Overpass request failed with HTTP $responseCode",
                )
            }

            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            var downloadedBytes = 0L
            val responseBytes = ByteArrayOutputStream()

            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    cancellation.throwIfCancelled()
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    responseBytes.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    onProgress(downloadedBytes, contentLength)
                }
            }
            cancellation.throwIfCancelled()
            val pack = normalizeOverpassTilePack(
                tileId = tileId,
                config = config,
                overpassJson = responseBytes.toString(Charsets.UTF_8.name()),
            )
            cancellation.throwIfCancelled()
            runCatching {
                storeTilePack(pack)
            }.onFailure { error ->
                Log.w(TILE_CONTEXT_REPOSITORY_LOG_TAG, "Failed to persist tile runtime pack for ${tileId.cacheKey}", error)
            }
            cancellation.throwIfCancelled()

            val snapshot = TileDownloadSnapshot(
                status = TileDownloadStatus.Cached,
                estimatedBytes = downloadedBytes,
                actualBytes = downloadedBytes,
                updatedAtMillis = System.currentTimeMillis(),
            )
            persistCachedTile(tileId, snapshot)
            return snapshot
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    private fun persistCachedTile(
        tileId: DownloadTileId,
        snapshot: TileDownloadSnapshot,
    ) {
        cachedTiles[tileId] = snapshot
        saveManifest()
    }

    @Synchronized
    private fun loadManifest(): Map<DownloadTileId, TileDownloadSnapshot> {
        if (!manifestFile.exists()) {
            return emptyMap()
        }
        return runCatching {
            val root = Json.parseToJsonElement(manifestFile.readText()).jsonObject
            val tiles = root["tiles"]?.jsonArray ?: JsonArray(emptyList())
            buildMap {
                tiles.forEach { entryElement ->
                    val entry = entryElement.jsonObject
                    val tileId = DownloadTileId(
                        zoom = entry.getValue("zoom").jsonPrimitive.int,
                        x = entry.getValue("x").jsonPrimitive.int,
                        y = entry.getValue("y").jsonPrimitive.int,
                    )
                    val tileFile = tileFileFor(tileId)
                    if (!tileFile.exists()) {
                        return@forEach
                    }
                    val actualBytes = entry["actualBytes"]?.jsonPrimitive?.longOrNull ?: tileFile.length()
                    put(
                        tileId,
                        TileDownloadSnapshot(
                            status = TileDownloadStatus.Cached,
                            estimatedBytes = actualBytes,
                            actualBytes = actualBytes,
                            updatedAtMillis = entry["updatedAtMillis"]?.jsonPrimitive?.longOrNull ?: tileFile.lastModified(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    private fun saveManifest() {
        val payload = buildJsonObject {
            put("schemaVersion", JsonPrimitive(TILE_CONTEXT_SCHEMA_VERSION))
            put(
                "tiles",
                buildJsonArray {
                    cachedTiles.forEach { (tileId, snapshot) ->
                        if (snapshot.status == TileDownloadStatus.Cached) {
                            add(
                                buildJsonObject {
                                    put("zoom", JsonPrimitive(tileId.zoom))
                                    put("x", JsonPrimitive(tileId.x))
                                    put("y", JsonPrimitive(tileId.y))
                                    put("actualBytes", JsonPrimitive(snapshot.actualBytes ?: snapshot.estimatedBytes))
                                    put("updatedAtMillis", JsonPrimitive(snapshot.updatedAtMillis))
                                },
                            )
                        }
                    }
                },
            )
        }
        manifestFile.writeText(payload.toString())
    }

    private fun tileFileFor(tileId: DownloadTileId): File {
        return File(
            cacheRoot,
            "tiles/${tileId.zoom}/${tileId.x}/${tileId.y}.json",
        )
    }

    private fun runtimeFileFor(tileId: DownloadTileId): File {
        return File(
            runtimeRoot,
            "tiles/${tileId.zoom}/${tileId.x}/${tileId.y}.bin",
        )
    }

    private fun persistRuntimePack(pack: TileRuntimePack) {
        val runtimeFile = runtimeFileFor(pack.tileId).apply { parentFile?.mkdirs() }
        runtimeFile.writeBytes(tileRuntimePackToByteArray(pack))
    }

    private fun routeOverlayFileFor(
        routeFingerprint: String,
        tileId: DownloadTileId,
        fetchedAtMillis: Long,
    ): File {
        return routeOverlayFileFor(
            RouteTileOverlayCacheKey(
                routeFingerprint = routeFingerprint,
                tileId = tileId,
                fetchedAtMillis = fetchedAtMillis,
            ),
        )
    }

    private fun routeOverlayFileFor(cacheKey: RouteTileOverlayCacheKey): File {
        return File(
            routeOverlayRoot,
            "${cacheKey.routeFingerprint}/${cacheKey.tileId.zoom}/${cacheKey.tileId.x}/${cacheKey.tileId.y}-${cacheKey.fetchedAtMillis}.bin",
        )
    }

    private fun persistRouteTileOverlay(
        routeFingerprint: String,
        overlay: RouteTileOverlay,
    ) {
        val overlayFile = routeOverlayFileFor(
            routeFingerprint = routeFingerprint,
            tileId = overlay.tileId,
            fetchedAtMillis = overlay.sourceFetchedAtMillis,
        ).apply { parentFile?.mkdirs() }
        overlayFile.writeBytes(routeTileOverlayToByteArray(overlay))
    }

    private fun cacheRuntimePack(pack: TileRuntimePack) {
        synchronized(derivedCacheLock) {
            runtimePackCache[pack.tileId] = pack
        }
    }

    private fun cacheRouteTileOverlay(overlay: RouteTileOverlay) {
        synchronized(derivedCacheLock) {
            routeTileOverlayCache[
                RouteTileOverlayCacheKey(
                    routeFingerprint = overlay.routeFingerprint,
                    tileId = overlay.tileId,
                    fetchedAtMillis = overlay.sourceFetchedAtMillis,
                ),
            ] = overlay
        }
    }

    private fun invalidateDerivedCaches(tileId: DownloadTileId) {
        synchronized(derivedCacheLock) {
            runtimePackCache.remove(tileId)
            val staleOverlayKeys = routeTileOverlayCache.keys
                .filter { key -> key.tileId == tileId }
            staleOverlayKeys.forEach(routeTileOverlayCache::remove)
            val staleOverlayTasks = routeTileOverlayLoadsInFlight.keys
                .filter { key -> key.tileId == tileId }
            staleOverlayTasks.forEach(routeTileOverlayLoadsInFlight::remove)
        }
        deleteRouteOverlayFiles(tileId)
    }

    private fun deleteRouteOverlayFiles(tileId: DownloadTileId) {
        routeOverlayRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.forEach { routeFingerprintDir ->
                val xDir = File(routeFingerprintDir, "${tileId.zoom}/${tileId.x}")
                val staleFiles = xDir.listFiles()
                    ?.filter { file ->
                        file.isFile && file.name.startsWith("${tileId.y}-") && file.extension == "bin"
                    }
                    .orEmpty()
                staleFiles.forEach(File::delete)
            }
    }

    private fun openOverpassConnection(query: String): HttpURLConnection {
        val requestBody = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        return (URL(OVERPASS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = OVERPASS_CONNECT_TIMEOUT_MILLIS
            readTimeout = OVERPASS_READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }
        }
    }

    private fun buildOverpassQuery(bounds: GeoBounds): String {
        val south = bounds.south
        val west = bounds.west
        val north = bounds.north
        val east = bounds.east
        return """
            [out:json][timeout:45];
            (
              way["highway"]($south,$west,$north,$east);
              node["barrier"]($south,$west,$north,$east);
              node["railway"="level_crossing"]($south,$west,$north,$east);
              node["highway"~"crossing|traffic_signals|stop|give_way|mini_roundabout|motorway_junction"]($south,$west,$north,$east);
              nw["amenity"="drinking_water"]($south,$west,$north,$east);
              nw["amenity"="toilets"]($south,$west,$north,$east);
              nw["amenity"="shelter"]($south,$west,$north,$east);
              nw["tourism"="picnic_site"]($south,$west,$north,$east);
              nw["amenity"="bicycle_repair_station"]($south,$west,$north,$east);
              nw["shop"="bicycle"]($south,$west,$north,$east);
            );
            out body geom($south,$west,$north,$east);
        """.trimIndent()
    }
}

private data class RouteTileOverlayCacheKey(
    val routeFingerprint: String,
    val tileId: DownloadTileId,
    val fetchedAtMillis: Long,
)

private fun <K, V> accessOrderCache(maxEntries: Int): LinkedHashMap<K, V> {
    return object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }
}
