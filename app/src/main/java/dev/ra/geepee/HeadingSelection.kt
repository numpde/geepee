package dev.ra.geepee

private const val COURSE_HEADING_MIN_SPEED_MPS = 1.8f
private const val COURSE_HEADING_MAX_ACCURACY_DEGREES = 20f

internal fun usableCourseHeadingDegrees(
    fix: LocationFix?,
    minSpeedMetersPerSecond: Float = COURSE_HEADING_MIN_SPEED_MPS,
    maxBearingAccuracyDegrees: Float = COURSE_HEADING_MAX_ACCURACY_DEGREES,
): Double? {
    if (fix == null) {
        return null
    }

    val speed = fix.speedMetersPerSecond ?: return null
    val accuracy = fix.bearingAccuracyDegrees ?: return null
    return fix.headingDegrees
        ?.takeIf { bearing ->
            speed >= minSpeedMetersPerSecond &&
                accuracy <= maxBearingAccuracyDegrees &&
                bearing.isFinite()
        }
        ?.toDouble()
}

internal fun currentHeadingReading(
    fix: LocationFix?,
    sensorHeadingDegrees: Double?,
): HeadingReading? {
    return usableCourseHeadingDegrees(fix)?.let { headingDegrees ->
        HeadingReading(
            degrees = headingDegrees,
            source = HeadingSource.Course,
        )
    } ?: sensorHeadingDegrees?.let { headingDegrees ->
        HeadingReading(
            degrees = headingDegrees,
            source = HeadingSource.Sensor,
        )
    }
}

internal fun displayHeadingDegrees(
    smoothedHeading: SmoothedHeading?,
    fix: LocationFix?,
    sensorHeadingDegrees: Double?,
): Double? {
    return smoothedHeading?.degrees ?: currentHeadingReading(fix, sensorHeadingDegrees)?.degrees
}
