package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    @Test
    fun permissionLossStopsActiveSessionAndClearsPendingStart() {
        val transition = SessionState(
            hasCoarsePermission = true,
            sessionActive = true,
            pendingSessionStart = true,
            isForeground = true,
        ).withPermissions(
            coarseGranted = false,
            fineGranted = false,
        )

        assertFalse(transition.state.hasLocationPermission)
        assertFalse(transition.state.sessionActive)
        assertFalse(transition.state.pendingSessionStart)
        assertTrue(transition.clearLiveState)
        assertEquals(false, transition.persistSessionActive)
    }

    @Test
    fun permissionResultStartsPendingSessionWhenRouteIsLoaded() {
        val transition = SessionState(
            pendingSessionStart = true,
            isForeground = true,
        ).onPermissionResult(
            coarseGranted = true,
            fineGranted = false,
            routeLoaded = true,
        )

        assertTrue(transition.state.hasLocationPermission)
        assertTrue(transition.state.sessionActive)
        assertFalse(transition.state.pendingSessionStart)
        assertTrue(transition.clearLiveState)
        assertEquals(true, transition.persistSessionActive)
    }

    @Test
    fun startRequiresPermissionAndRoute() {
        val missingPermission = SessionState().start(routeLoaded = true)
        assertFalse(missingPermission.state.sessionActive)
        assertNull(missingPermission.persistSessionActive)

        val missingRoute = SessionState(
            hasCoarsePermission = true,
        ).start(routeLoaded = false)
        assertFalse(missingRoute.state.sessionActive)
        assertNull(missingRoute.persistSessionActive)
    }

    @Test
    fun trackingFlagsDependOnForegroundAndPermission() {
        val state = SessionState(
            hasCoarsePermission = true,
            sessionActive = true,
            isForeground = true,
        )

        assertTrue(state.shouldTrackLocation)
        assertTrue(state.shouldTrackHeading)

        val background = state.withForeground(false).state
        assertFalse(background.shouldTrackLocation)
        assertFalse(background.shouldTrackHeading)
    }

    @Test
    fun stopClearsPendingStartAndPersistsInactiveState() {
        val transition = SessionState(
            hasCoarsePermission = true,
            sessionActive = true,
            pendingSessionStart = true,
            isForeground = true,
        ).stop()

        assertFalse(transition.state.sessionActive)
        assertFalse(transition.state.pendingSessionStart)
        assertTrue(transition.clearLiveState)
        assertEquals(false, transition.persistSessionActive)
    }
}
