package dev.ra.geepee

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt

internal fun openLocationInExternalMap(
    context: Context,
    point: GeoPoint,
    label: String = "GeePee",
    windowWidthMeters: Double? = null,
) {
    val zoom = mapZoomForWindowWidthMeters(
        latitude = point.lat,
        windowWidthMeters = windowWidthMeters,
        viewportWidthPx = context.resources.displayMetrics.widthPixels,
    )
    val geoDisplayUri = Uri.parse(
        "geo:${
            String.format(Locale.US, "%.6f", point.lat)
        },${
            String.format(Locale.US, "%.6f", point.lon)
        }?z=$zoom",
    )
    if (tryStartChooser(context, geoDisplayUri)) {
        return
    }

    val geoQueryUri = Uri.parse(
        "geo:0,0?q=${
            String.format(
                Locale.US,
                "%.6f,%.6f",
                point.lat,
                point.lon,
            )
        }",
    )
    if (tryStartChooser(context, geoQueryUri)) {
        return
    }

    val webUri = osmWebUri(point = point, zoom = zoom)
    try {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW, webUri),
                "Open in…",
            ),
        )
    } catch (_: ActivityNotFoundException) {
        // No external handler available.
    }
}

internal fun openLocationInOsmBrowser(
    context: Context,
    point: GeoPoint,
    windowWidthMeters: Double? = null,
) {
    val zoom = mapZoomForWindowWidthMeters(
        latitude = point.lat,
        windowWidthMeters = windowWidthMeters,
        viewportWidthPx = context.resources.displayMetrics.widthPixels,
    )
    val webUri = osmWebUri(point = point, zoom = zoom)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    } catch (_: ActivityNotFoundException) {
        // No external handler available.
    }
}

internal fun mapZoomForWindowWidthMeters(
    latitude: Double,
    windowWidthMeters: Double?,
    viewportWidthPx: Int,
): Int {
    if (windowWidthMeters == null || viewportWidthPx <= 0) {
        return 16
    }
    val metersPerPixel = windowWidthMeters / viewportWidthPx.toDouble()
    if (metersPerPixel <= 0.0) {
        return 16
    }
    val latitudeCos = cos(latitude * PI / 180.0).coerceAtLeast(0.01)
    val zoom = log2((156543.03392 * latitudeCos) / metersPerPixel)
    return zoom.roundToInt().coerceIn(1, 23)
}

internal fun osmWebUri(
    point: GeoPoint,
    zoom: Int,
): Uri {
    return Uri.parse(osmWebUrl(point = point, zoom = zoom))
}

internal fun osmWebUrl(
    point: GeoPoint,
    zoom: Int,
): String {
    return "https://www.openstreetmap.org/#map=$zoom/${
        String.format(Locale.US, "%.6f", point.lat)
    }/${
        String.format(Locale.US, "%.6f", point.lon)
    }"
}

private fun tryStartChooser(
    context: Context,
    uri: Uri,
): Boolean {
    return try {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW, uri),
                "Open in…",
            ),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
