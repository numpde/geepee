package dev.ra.geepee

private const val LOCATION_MIN_TIME_MS = 2_000L
private const val LOCATION_MIN_DISTANCE_METERS = 3f
private const val LOCATION_MIN_TIME_BATTERY_SAVER_MS = 4_000L
private const val LOCATION_MIN_DISTANCE_BATTERY_SAVER_METERS = 8f
private const val HEADING_MIN_INTERVAL_MS = 40L
private const val HEADING_MIN_DELTA_DEGREES = 1.5
private const val HEADING_MIN_INTERVAL_BATTERY_SAVER_MS = 90L
private const val HEADING_MIN_DELTA_BATTERY_SAVER_DEGREES = 3.0
private const val HEADING_SENSOR_PERIOD_US = 20_000
private const val HEADING_SENSOR_PERIOD_BATTERY_SAVER_US = 40_000

internal fun liveTrackingConfig(batterySaverEnabled: Boolean): LiveTrackingConfig {
    return if (batterySaverEnabled) {
        LiveTrackingConfig(
            locationMinTimeMs = LOCATION_MIN_TIME_BATTERY_SAVER_MS,
            locationMinDistanceMeters = LOCATION_MIN_DISTANCE_BATTERY_SAVER_METERS,
            headingMinIntervalMs = HEADING_MIN_INTERVAL_BATTERY_SAVER_MS,
            headingMinDeltaDegrees = HEADING_MIN_DELTA_BATTERY_SAVER_DEGREES,
            headingSensorPeriodUs = HEADING_SENSOR_PERIOD_BATTERY_SAVER_US,
        )
    } else {
        LiveTrackingConfig(
            locationMinTimeMs = LOCATION_MIN_TIME_MS,
            locationMinDistanceMeters = LOCATION_MIN_DISTANCE_METERS,
            headingMinIntervalMs = HEADING_MIN_INTERVAL_MS,
            headingMinDeltaDegrees = HEADING_MIN_DELTA_DEGREES,
            headingSensorPeriodUs = HEADING_SENSOR_PERIOD_US,
        )
    }
}
