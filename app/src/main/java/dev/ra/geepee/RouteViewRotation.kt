package dev.ra.geepee

internal fun routeViewRotationDegrees(
    orientationMode: OrientationMode,
    headingDegrees: Double?,
): Float {
    return if (orientationMode == OrientationMode.CourseUp) {
        -(headingDegrees?.toFloat() ?: 0f)
    } else {
        0f
    }
}
