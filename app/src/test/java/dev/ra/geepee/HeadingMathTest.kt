package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingMathTest {
    @Test
    fun interpolateHeadingDegreesUsesShortestWraparoundPath() {
        val interpolated = interpolateHeadingDegrees(
            currentDegrees = 350.0,
            targetDegrees = 10.0,
            alpha = 0.5,
        )

        assertEquals(0.0, interpolated, 0.001)
    }

    @Test
    fun smoothHeadingBlendsAcrossSourceChanges() {
        val previous = SmoothedHeading(
            degrees = 90.0,
            source = HeadingSource.Sensor,
        )

        val smoothed = smoothHeading(
            previous = previous,
            target = HeadingReading(
                degrees = 180.0,
                source = HeadingSource.Course,
            ),
            batterySaverEnabled = false,
        )

        assertEquals(135.0, smoothed!!.degrees, 0.001)
        assertEquals(HeadingSource.Course, smoothed.source)
    }

    @Test
    fun smoothHeadingMovesPartwayTowardTarget() {
        val smoothed = smoothHeading(
            previous = SmoothedHeading(
                degrees = 0.0,
                source = HeadingSource.Sensor,
            ),
            target = HeadingReading(
                degrees = 90.0,
                source = HeadingSource.Sensor,
            ),
            batterySaverEnabled = false,
        )

        assertTrue(smoothed!!.degrees in 26.0..28.0)
    }

    @Test
    fun smoothHeadingReturnsNullWithoutTarget() {
        assertNull(
            smoothHeading(
                previous = SmoothedHeading(
                    degrees = 12.0,
                    source = HeadingSource.Sensor,
                ),
                target = null,
                batterySaverEnabled = false,
            ),
        )
    }
}
