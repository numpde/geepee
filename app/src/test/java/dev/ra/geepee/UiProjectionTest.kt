package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiProjectionTest {
    @Test
    fun projectionUsesRouteLoadStateSessionStateAndPreferences() {
        val uiState = buildGeePeeUiState(
            GeePeeUiProjectionInputs(
                routeLoadState = RouteLoadState(routeName = "Tisza", routeLoading = true),
                routeModel = null,
                currentFix = null,
                analysis = null,
                locationHistoryPoints = emptyList(),
                compass = null,
                sessionState = SessionState(
                    hasCoarsePermission = true,
                    hasFinePermission = false,
                    sessionActive = false,
                ),
                appPreferences = AppPreferences(
                    batterySaverEnabled = false,
                    darkModeEnabled = false,
                    orientationMode = OrientationMode.NorthUp,
                    routeScale = RouteScale.TenKilometers,
                ),
                locationProvidersEnabled = true,
                headingDegrees = null,
            ),
        )

        assertEquals("Tisza", uiState.routeName)
        assertFalse(uiState.darkModeEnabled)
        assertEquals(OrientationMode.NorthUp, uiState.orientationMode)
        assertEquals(RouteScale.TenKilometers, uiState.routeScale)
        assertFalse(uiState.batterySaverEnabled)
        assertEquals("Reading route", uiState.status.headline)
    }
}
