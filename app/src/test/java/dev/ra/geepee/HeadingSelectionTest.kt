package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingSelectionTest {
    @Test
    fun currentHeadingReadingPrefersReliableCourseHeading() {
        val fix = LocationFix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 72f,
            speedMetersPerSecond = 3.5f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 6f,
        )

        val reading = currentHeadingReading(
            fix = fix,
            sensorHeadingDegrees = 210.0,
        )

        assertEquals(HeadingSource.Course, reading?.source)
        assertEquals(72.0, reading?.degrees ?: 0.0, 0.0)
    }

    @Test
    fun currentHeadingReadingFallsBackToSensorWhenCourseHeadingIsUnreliable() {
        val fix = LocationFix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 72f,
            speedMetersPerSecond = 0.8f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 6f,
        )

        val reading = currentHeadingReading(
            fix = fix,
            sensorHeadingDegrees = 210.0,
        )

        assertEquals(HeadingSource.Sensor, reading?.source)
        assertEquals(210.0, reading?.degrees ?: 0.0, 0.0)
    }

    @Test
    fun currentHeadingReadingReturnsNullWhenNeitherSourceIsUsable() {
        val fix = LocationFix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = null,
        )

        assertNull(
            currentHeadingReading(
                fix = fix,
                sensorHeadingDegrees = null,
            ),
        )
    }

    @Test
    fun displayHeadingDegreesPrefersSmoothedHeading() {
        val heading = displayHeadingDegrees(
            smoothedHeading = SmoothedHeading(
                degrees = 18.0,
                source = HeadingSource.Sensor,
            ),
            fix = null,
            sensorHeadingDegrees = 210.0,
        )

        assertEquals(18.0, heading ?: 0.0, 0.0)
    }

    @Test
    fun usableCourseHeadingRequiresSpeedAndAccuracy() {
        val fix = LocationFix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 4f,
            headingDegrees = 72f,
            speedMetersPerSecond = 3.5f,
            timestampMillis = 1_000L,
            bearingAccuracyDegrees = 30f,
        )

        assertNull(usableCourseHeadingDegrees(fix))
        assertTrue(usableCourseHeadingDegrees(fix.copy(bearingAccuracyDegrees = 8f)) != null)
    }
}
