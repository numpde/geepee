package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Test

class UiModelsTest {
    @Test
    fun formatAgeUsesExplicitUnits() {
        assertEquals("59 sec", formatAge(59_000L))
        assertEquals("1 min", formatAge(60_000L))
        assertEquals("59 min", formatAge(3_599_000L))
        assertEquals("1 hr", formatAge(3_600_000L))
    }
}
