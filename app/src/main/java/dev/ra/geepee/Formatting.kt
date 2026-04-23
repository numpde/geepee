package dev.ra.geepee

import java.util.Locale
import kotlin.math.roundToInt

fun formatDistance(meters: Double): String {
    if (!meters.isFinite()) {
        return "-"
    }
    return if (meters >= 1000.0) {
        val decimals = if (meters >= 10_000.0) 1 else 2
        String.format(Locale.US, "%.${decimals}f km", meters / 1000.0)
    } else {
        "${meters.roundToInt()} m"
    }
}
