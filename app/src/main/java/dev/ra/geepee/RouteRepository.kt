package dev.ra.geepee

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log

internal data class LoadedRoute(
    val model: RouteModel,
    val displayName: String,
)

internal class RouteRepository(
    private val contentResolver: ContentResolver,
    private val appStateStore: AppStateStore,
    private val logTag: String,
) {
    fun loadRoute(uri: Uri, displayName: String?): LoadedRoute {
        val resolvedName = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Route"
        val parsedSegments = contentResolver.openInputStream(uri)?.use(GpxParser::parse)
            ?: throw IllegalArgumentException("Could not open that GPX file.")
        return LoadedRoute(
            model = buildRouteModel(parsedSegments),
            displayName = resolvedName,
        )
    }

    fun rememberSelectedRoute(uri: Uri, displayName: String) {
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
        appStateStore.saveRouteSelection(uri, displayName)
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
