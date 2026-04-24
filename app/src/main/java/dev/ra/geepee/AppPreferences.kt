package dev.ra.geepee

internal data class AppPreferences(
    val batterySaverEnabled: Boolean = true,
    val darkModeEnabled: Boolean = true,
    val orientationMode: OrientationMode = OrientationMode.CourseUp,
    val routeScale: RouteScale = RouteScale.TwoHundred,
)
