package dev.ra.geepee

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutePoiSelectionsTest {
    @Test
    fun routePoiUiStateClearRemovesSelections() {
        val state = RoutePoiUiState(
            selectedPois = listOf(
                RoutePoiSelectionInfo(
                    kind = RoutePoiKind.Shelter,
                    title = "Shelter",
                    distanceMeters = 25.0,
                ),
            ),
        )

        assertEquals(emptyList<RoutePoiSelectionInfo>(), state.clear().selectedPois)
    }

    @Test
    fun routePoiUiStateClearOnTransformClearsOnlyForRealMovement() {
        val selected = listOf(
            RoutePoiSelectionInfo(
                kind = RoutePoiKind.Shelter,
                title = "Shelter",
                distanceMeters = 25.0,
            ),
        )
        val state = RoutePoiUiState(selectedPois = selected)

        assertEquals(
            selected,
            state.clearOnTransform(pan = Offset.Zero, zoom = 1f).selectedPois,
        )
        assertEquals(
            emptyList<RoutePoiSelectionInfo>(),
            state.clearOnTransform(pan = Offset(1f, 0f), zoom = 1f).selectedPois,
        )
    }

    @Test
    fun routePoiSelectionsFromMarkers_dedupesAndSortsByDistanceThenTitle() {
        val origin = GeoPoint(-1.0, 36.0)
        val closerShelter = RoutePoiScreenMarker(
            featureId = "shelter-1",
            kind = RoutePoiKind.Shelter,
            name = null,
            geoPoint = GeoPoint(-1.0001, 36.0),
            point = ScreenPoint(10f, 10f),
        )
        val fartherNamedWater = RoutePoiScreenMarker(
            featureId = "water-1",
            kind = RoutePoiKind.DrinkingWater,
            name = "Camp tap",
            geoPoint = GeoPoint(-1.0015, 36.0),
            point = ScreenPoint(12f, 12f),
        )
        val duplicateWater = fartherNamedWater.copy(point = ScreenPoint(14f, 14f))

        val selections = routePoiSelectionsFromMarkers(
            markers = listOf(fartherNamedWater, duplicateWater, closerShelter),
            origin = origin,
        )

        assertEquals(2, selections.size)
        assertEquals("Shelter", selections[0].title)
        assertEquals("Camp tap", selections[1].title)
    }

    @Test
    fun routePoiSelectionTitle_usesFallbackLabelWhenNameMissing() {
        val marker = RoutePoiScreenMarker(
            featureId = "toilets-1",
            kind = RoutePoiKind.Toilets,
            name = null,
            geoPoint = GeoPoint(-1.0, 36.0),
            point = ScreenPoint(0f, 0f),
        )

        assertEquals("Toilets", routePoiSelectionTitle(marker))
    }
}
