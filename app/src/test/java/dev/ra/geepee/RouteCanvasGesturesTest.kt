package dev.ra.geepee

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCanvasGesturesTest {
    @Test
    fun isBoundedDoubleTap_acceptsNearbySecondTap() {
        assertTrue(
            isBoundedDoubleTap(
                firstTapPosition = Offset(100f, 100f),
                secondTapPosition = Offset(120f, 110f),
                maxDistancePx = 32f,
            ),
        )
    }

    @Test
    fun isBoundedDoubleTap_rejectsFarSecondTap() {
        assertFalse(
            isBoundedDoubleTap(
                firstTapPosition = Offset(100f, 100f),
                secondTapPosition = Offset(180f, 100f),
                maxDistancePx = 32f,
            ),
        )
    }

    @Test
    fun isBoundedDoubleTap_acceptsTapExactlyOnDistanceBoundary() {
        assertTrue(
            isBoundedDoubleTap(
                firstTapPosition = Offset(100f, 100f),
                secondTapPosition = Offset(132f, 100f),
                maxDistancePx = 32f,
            ),
        )
    }
}
