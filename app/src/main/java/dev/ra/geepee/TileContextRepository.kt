package dev.ra.geepee

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

internal class TileContextRepository(
    context: Context,
) {
    private val cacheRoot = File(context.filesDir, "tile-context/v$TILE_CONTEXT_SCHEMA_VERSION")
    private val manifestFile = File(cacheRoot, "manifest.json")
    private val cachedTiles = linkedMapOf<DownloadTileId, TileDownloadSnapshot>()

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

    @Throws(IOException::class)
    fun downloadTile(
        tileId: DownloadTileId,
        config: TileContextConfig,
        onProgress: (downloadedBytes: Long, contentLengthBytes: Long?) -> Unit,
    ): TileDownloadSnapshot {
        val queryBounds = expandGeoBoundsByMeters(tileGeoBounds(tileId), config.fetchHaloMeters)
        val connection = openOverpassConnection(buildOverpassQuery(queryBounds))
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException(
                    errorText?.takeIf { it.isNotBlank() }
                        ?: "Overpass request failed with HTTP $responseCode",
                )
            }

            val tileFile = tileFileFor(tileId).apply { parentFile?.mkdirs() }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            var downloadedBytes = 0L
            val responseBytes = ByteArrayOutputStream()

            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    responseBytes.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    onProgress(downloadedBytes, contentLength)
                }
            }
            val pack = normalizeOverpassTilePack(
                tileId = tileId,
                config = config,
                overpassJson = responseBytes.toString(Charsets.UTF_8.name()),
            )
            tileFile.writeText(pack.toJsonString())

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
