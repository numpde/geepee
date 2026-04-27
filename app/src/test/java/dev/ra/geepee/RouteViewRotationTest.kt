package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteViewRotationTest {
    @Test
    fun routeViewRotationDegrees_usesNegativeHeadingInCourseUp() {
        assertEquals(
            -123.5f,
            routeViewRotationDegrees(
                orientationMode = OrientationMode.CourseUp,
                headingDegrees = 123.5,
            ),
            0f,
        )
    }

    @Test
    fun routeViewRotationDegrees_defaultsToZeroWithoutHeadingInCourseUp() {
        assertEquals(
            0f,
            routeViewRotationDegrees(
                orientationMode = OrientationMode.CourseUp,
                headingDegrees = null,
            ),
            0f,
        )
    }

    @Test
    fun routeViewRotationDegrees_isZeroInNorthUp() {
        assertEquals(
            0f,
            routeViewRotationDegrees(
                orientationMode = OrientationMode.NorthUp,
                headingDegrees = 270.0,
            ),
            0f,
        )
    }
}
