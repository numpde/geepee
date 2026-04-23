package dev.ra.geepee

import kotlin.math.max
import kotlin.math.min

internal enum class OrientationMode {
    NorthUp,
    CourseUp,
}

internal enum class RouteScale(val label: String, val windowWidthMeters: Double) {
    Fifty("50 m", 50.0),
    Hundred("100 m", 100.0),
    TwoHundred("200 m", 200.0),
    FourHundred("400 m", 400.0),
    Kilometer("1 km", 1000.0),
}

internal fun RouteScale.zoomIn(): RouteScale {
    val index = RouteScale.entries.indexOf(this)
    return RouteScale.entries[max(index - 1, 0)]
}

internal fun RouteScale.zoomOut(): RouteScale {
    val index = RouteScale.entries.indexOf(this)
    return RouteScale.entries[min(index + 1, RouteScale.entries.lastIndex)]
}

internal fun RouteScale.next(): RouteScale {
    val index = RouteScale.entries.indexOf(this)
    val nextIndex = (index + 1) % RouteScale.entries.size
    return RouteScale.entries[nextIndex]
}

internal fun RouteScale.scaleBarDistanceMeters(): Double {
    val target = windowWidthMeters * 0.28
    val candidates = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0)
    return candidates.lastOrNull { it <= target } ?: candidates.first()
}

internal fun formatAge(ageMillis: Long): String {
    val totalSeconds = ageMillis / 1_000L
    return when {
        totalSeconds < 60L -> "$totalSeconds sec"
        totalSeconds < 3_600L -> "${totalSeconds / 60L} min"
        else -> "${totalSeconds / 3_600L} hr"
    }
}
