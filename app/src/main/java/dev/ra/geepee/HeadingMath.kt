package dev.ra.geepee

internal enum class HeadingSource {
    Course,
    Sensor,
}

internal data class HeadingReading(
    val degrees: Double,
    val source: HeadingSource,
)

internal data class SmoothedHeading(
    val degrees: Double,
    val source: HeadingSource,
)

private const val COURSE_HEADING_ALPHA = 0.5
private const val SENSOR_HEADING_ALPHA = 0.3
private const val COURSE_HEADING_ALPHA_BATTERY_SAVER = 0.36
private const val SENSOR_HEADING_ALPHA_BATTERY_SAVER = 0.2

internal fun smoothHeading(
    previous: SmoothedHeading?,
    target: HeadingReading?,
    batterySaverEnabled: Boolean,
): SmoothedHeading? {
    if (target == null) {
        return null
    }

    if (previous == null) {
        return SmoothedHeading(
            degrees = normalizeHeadingDegrees(target.degrees),
            source = target.source,
        )
    }

    val alpha = when (target.source) {
        HeadingSource.Course -> if (batterySaverEnabled) {
            COURSE_HEADING_ALPHA_BATTERY_SAVER
        } else {
            COURSE_HEADING_ALPHA
        }

        HeadingSource.Sensor -> if (batterySaverEnabled) {
            SENSOR_HEADING_ALPHA_BATTERY_SAVER
        } else {
            SENSOR_HEADING_ALPHA
        }
    }

    return SmoothedHeading(
        degrees = interpolateHeadingDegrees(previous.degrees, target.degrees, alpha),
        source = target.source,
    )
}

internal fun interpolateHeadingDegrees(
    currentDegrees: Double,
    targetDegrees: Double,
    alpha: Double,
): Double {
    val normalizedAlpha = alpha.coerceIn(0.0, 1.0)
    val delta = normalizeSignedHeadingDegrees(targetDegrees - currentDegrees)
    return normalizeHeadingDegrees(currentDegrees + delta * normalizedAlpha)
}

internal fun normalizeHeadingDegrees(value: Double): Double {
    val normalized = value % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

internal fun normalizeSignedHeadingDegrees(value: Double): Double {
    val normalized = normalizeHeadingDegrees(value)
    return if (normalized > 180.0) normalized - 360.0 else normalized
}
