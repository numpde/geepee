package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLoadStateTest {
    @Test
    fun beginLoadingClearsIssueAndMarksLoading() {
        val state = RouteLoadState(routeName = "Old", issueMessage = "Bad").beginLoading()

        assertEquals("Old", state.routeName)
        assertNull(state.issueMessage)
        assertTrue(state.routeLoading)
    }

    @Test
    fun loadSucceededSetsNameAndClearsLoading() {
        val state = RouteLoadState(issueMessage = "Bad", routeLoading = true).loadSucceeded("Tisza")

        assertEquals("Tisza", state.routeName)
        assertNull(state.issueMessage)
        assertFalse(state.routeLoading)
    }

    @Test
    fun clearRouteResetsEverything() {
        val state = RouteLoadState(routeName = "Tisza", issueMessage = "Bad", routeLoading = true).clearRoute()

        assertNull(state.routeName)
        assertNull(state.issueMessage)
        assertFalse(state.routeLoading)
    }
}
