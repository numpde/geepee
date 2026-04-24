package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTrackingPolicyTest {
    @Test
    fun defaultPolicyPrefersResponsiveness() {
        val config = liveTrackingConfig(batterySaverEnabled = false)

        assertEquals(2_000L, config.locationMinTimeMs)
        assertEquals(3f, config.locationMinDistanceMeters)
        assertEquals(40L, config.headingMinIntervalMs)
        assertEquals(1.5, config.headingMinDeltaDegrees, 0.0)
        assertEquals(20_000, config.headingSensorPeriodUs)
    }

    @Test
    fun batterySaverPolicyBacksOffTrackingCadence() {
        val config = liveTrackingConfig(batterySaverEnabled = true)

        assertEquals(4_000L, config.locationMinTimeMs)
        assertEquals(8f, config.locationMinDistanceMeters)
        assertEquals(90L, config.headingMinIntervalMs)
        assertEquals(3.0, config.headingMinDeltaDegrees, 0.0)
        assertEquals(40_000, config.headingSensorPeriodUs)
    }
}
