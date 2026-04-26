package dev.ra.geepee

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log

internal data class LoadedRoute(
    val model: RouteModel,
    val displayName: String,
    val baseDisplayName: String,
    val isReversed: Boolean,
)

internal class RouteRepository(
    private val contentResolver: ContentResolver,
    private val appStateStore: AppStateStore,
    private val logTag: String,
) {
    fun loadRoute(uri: Uri, displayName: String?, reversed: Boolean): LoadedRoute {
        val resolvedName = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Route"
        val parsedSegments = contentResolver.openInputStream(uri)?.use(GpxParser::parse)
            ?: throw IllegalArgumentException("Could not open that GPX file.")
        val routeSegments = if (reversed) {
            reverseRouteSegments(parsedSegments)
        } else {
            parsedSegments
        }
        return LoadedRoute(
            model = buildRouteModel(routeSegments),
            displayName = routeDisplayName(resolvedName, reversed),
            baseDisplayName = resolvedName,
            isReversed = reversed,
        )
    }

    fun rememberSelectedRoute(uri: Uri, displayName: String, reversed: Boolean) {
        val previousUri = appStateStore.load().routeUri
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { error ->
            Log.w(logTag, "Could not persist route grant for uri=$uri", error)
        }
        if (previousUri != null && previousUri != uri) {
            releasePersistedReadGrant(previousUri)
        }
        appStateStore.saveRouteSelection(uri, displayName, reversed)
    }

    fun clearRememberedRoute() {
        appStateStore.load().routeUri?.let(::releasePersistedReadGrant)
        appStateStore.clearRouteSelection()
    }

    private fun releasePersistedReadGrant(uri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { error ->
            Log.w(logTag, "Could not release previous route grant for uri=$uri", error)
        }
    }
}

internal fun reverseRouteSegments(rawSegments: List<List<GeoPoint>>): List<List<GeoPoint>> {
    return rawSegments.asReversed().map { segment ->
        segment.asReversed()
    }
}

private fun routeDisplayName(baseDisplayName: String, reversed: Boolean): String {
    return if (reversed) {
        "$baseDisplayName (reversed)"
    } else {
        baseDisplayName
    }
}
