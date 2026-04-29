package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
                belief = null,
                routeMatchHypotheses = emptyList(),
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
                tileContextConfig = TileContextConfig(downloadZoom = 10),
                tileDownloads = emptyMap(),
                routeContextState = RouteContextState(),
                debugGpsEnabled = false,
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

    @Test
    fun projectionPrefersMatchedRoutePointForExternalMapTarget() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val fix = LocationFix(
            lat = 1.0,
            lon = 1.0,
            accuracyMeters = 5f,
            headingDegrees = null,
            speedMetersPerSecond = null,
            timestampMillis = 42L,
        )
        val analysis = analyzeLocationAgainstModel(
            model = routeModel,
            fix = LocationFix(
                lat = 0.0,
                lon = 0.005,
                accuracyMeters = 5f,
                headingDegrees = null,
                speedMetersPerSecond = null,
                timestampMillis = 42L,
            ),
        )
        val belief = RouteBelief(
            fix = fix,
            sigmaMeters = 8.0,
            routeProbability = 0.1,
            offRouteProbability = 0.9,
            adherence = RouteAdherence.OffRoute,
            primaryRouteAnalysis = analysis,
            routeCandidates = emptyList(),
        )

        val uiState = buildGeePeeUiState(
            GeePeeUiProjectionInputs(
                routeLoadState = RouteLoadState(routeName = "Tisza"),
                routeModel = routeModel,
                currentFix = fix,
                analysis = analysis,
                belief = belief,
                routeMatchHypotheses = emptyList(),
                locationHistoryPoints = emptyList(),
                compass = null,
                sessionState = SessionState(sessionActive = true),
                appPreferences = AppPreferences(),
                tileContextConfig = DefaultTileContextConfig,
                tileDownloads = emptyMap(),
                routeContextState = RouteContextState(),
                debugGpsEnabled = false,
                locationProvidersEnabled = true,
                headingDegrees = null,
            ),
        )

        assertNotNull(uiState.currentReferenceGeoPoint)
        assertEquals(analysis.nearestGeoPoint, uiState.currentReferenceGeoPoint)
        assertEquals(RouteAdherence.OffRoute, uiState.routeAdherence)
    }

    @Test
    fun mapInfoAvailabilityTextReportsCurrentViewAvailability() {
        assertEquals(
            "Map info for this view: not downloaded",
            mapInfoAvailabilityText(
                LocalNearbyWayDebugStatus(
                    localTileCount = 9,
                    downloadedLocalTileCount = 0,
                    overlayReadyLocalTileCount = 0,
                    hasVisibleTileData = false,
                ),
            ),
        )

        assertEquals(
            "Map info for this view: loading…",
            mapInfoAvailabilityText(
                LocalNearbyWayDebugStatus(
                    localTileCount = 9,
                    downloadedLocalTileCount = 3,
                    overlayReadyLocalTileCount = 0,
                    hasVisibleTileData = false,
                    nearbyWaysLoading = true,
                ),
            ),
        )

        assertEquals(
            "Map info for this view: available",
            mapInfoAvailabilityText(
                LocalNearbyWayDebugStatus(
                    nearbyWayCount = 2,
                    localTileCount = 9,
                    downloadedLocalTileCount = 9,
                    overlayReadyLocalTileCount = 9,
                    hasVisibleTileData = true,
                ),
            ),
        )

        assertEquals(
            "Map info for this view: partly available",
            mapInfoAvailabilityText(
                LocalNearbyWayDebugStatus(
                    localTileCount = 9,
                    downloadedLocalTileCount = 5,
                    overlayReadyLocalTileCount = 3,
                    hasVisibleTileData = true,
                ),
            ),
        )
    }

    @Test
    fun projectionGroupsRouteMapInfoIntoSingleUiStateObject() {
        val routeModel = buildRouteModel(
            listOf(
                listOf(
                    GeoPoint(lat = 0.0, lon = 0.0),
                    GeoPoint(lat = 0.0, lon = 0.01),
                ),
            ),
        )
        val poi = RoutePoi(
            featureId = "node/water",
            kind = RoutePoiKind.DrinkingWater,
            name = "Pump",
            geoPoint = GeoPoint(0.0, 0.003),
            projectedPoint = ProjectedPoint(0.0, 0.0),
        )
        val nearbyWay = RouteNearbyWaySnippet(
            featureId = "way/branch",
            points = listOf(ProjectedPoint(0.0, 0.0), ProjectedPoint(10.0, 0.0)),
            bounds = Bounds(0.0, 10.0, 0.0, 0.0),
        )

        val uiState = buildGeePeeUiState(
            GeePeeUiProjectionInputs(
                routeLoadState = RouteLoadState(routeName = "Tisza"),
                routeModel = routeModel,
                currentFix = null,
                analysis = null,
                belief = null,
                routeMatchHypotheses = emptyList(),
                locationHistoryPoints = emptyList(),
                compass = null,
                sessionState = SessionState(sessionActive = true),
                appPreferences = AppPreferences(),
                tileContextConfig = DefaultTileContextConfig,
                tileDownloads = emptyMap(),
                routeContextState = RouteContextState(
                    pois = listOf(poi),
                    mapInfo = RouteMapInfoState(
                        localNearbyWays = LocalNearbyWayDebugStatus(
                            localTileCount = 1,
                            downloadedLocalTileCount = 1,
                            overlayReadyLocalTileCount = 1,
                            hasVisibleTileData = true,
                        ),
                        nearbyWays = listOf(nearbyWay),
                    ),
                ),
                debugGpsEnabled = false,
                locationProvidersEnabled = true,
                headingDegrees = null,
            ),
        )

        assertEquals(listOf(poi), uiState.mapInfo.pois)
        assertEquals(listOf(nearbyWay), uiState.mapInfo.nearbyWays)
        assertEquals("Map info for this view: available", uiState.mapInfo.availabilityText)
    }
}
