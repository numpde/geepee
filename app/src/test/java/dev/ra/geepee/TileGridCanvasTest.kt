package dev.ra.geepee

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridCanvasTest {
    @Test
    fun previewCachedTileUsesSubtleGreenFillAndVisibleDownloadedBorder() {
        val paint = tileGridCellPaint(
            tile = displayTile(
                downloadState = TileGridDownloadState.Cached,
                hasCachedCoverage = true,
            ),
            colors = TestColors,
            visualStyle = TileGridVisualStyle.Preview,
        )

        assertEquals(0.045f, paint.stateFill.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0.075f, paint.cachedCoverageFill.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0.22f, paint.cachedCoverageBorderColor.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0.85f, paint.cachedCoverageBorderWidthDp, 0.0001f)
        assertEquals(0.32f, paint.borderColor.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(1.15f, paint.borderWidthDp, 0.0001f)
        assertTrue(paint.cachedCoverageBorderColor.alpha > paint.cachedCoverageFill.alpha)
        assertTrue(paint.borderColor.alpha > paint.cachedCoverageFill.alpha)
    }

    @Test
    fun previewPartialTileKeepsCoverageQuieterThanCachedTileBorder() {
        val paint = tileGridCellPaint(
            tile = displayTile(
                downloadState = TileGridDownloadState.Partial,
                hasCachedCoverage = true,
            ),
            colors = TestColors,
            visualStyle = TileGridVisualStyle.Preview,
        )

        assertEquals(0.028f, paint.stateFill.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0.075f, paint.cachedCoverageFill.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0.22f, paint.cachedCoverageBorderColor.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0.24f, paint.borderColor.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(1.05f, paint.borderWidthDp, 0.0001f)
        assertTrue(paint.cachedCoverageBorderColor.alpha > paint.cachedCoverageFill.alpha)
        assertTrue(paint.borderColor.alpha > paint.cachedCoverageFill.alpha)
    }

    @Test
    fun liveOverlaySuppressesDownloadedTileFills() {
        val paint = tileGridCellPaint(
            tile = displayTile(
                downloadState = TileGridDownloadState.Cached,
                hasCachedCoverage = true,
            ),
            colors = TestColors,
            visualStyle = TileGridVisualStyle.LiveOverlay,
        )

        assertEquals(0f, paint.routeFill.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0f, paint.stateFill.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0f, paint.cachedCoverageFill.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0f, paint.cachedCoverageBorderColor.alpha, COLOR_ALPHA_TOLERANCE)
        assertEquals(0f, paint.cachedCoverageBorderWidthDp, 0.0001f)
        assertEquals(0.22f, paint.borderColor.alpha, COLOR_ALPHA_TOLERANCE)
    }

    @Test
    fun selectedCoverageBorderUsesSelectionColor() {
        val tileId = DownloadTileId(zoom = 12, x = 100, y = 200)
        val screenRect = ScreenRect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        val paint = tileGridCellPaint(
            tile = displayTile(
                downloadState = TileGridDownloadState.Cached,
                hasCachedCoverage = true,
                coverageTileId = tileId,
                selectedTileIds = setOf(tileId),
                screenRect = screenRect,
            ),
            colors = TestColors,
            visualStyle = TileGridVisualStyle.Preview,
        )

        assertEquals(TestColors.nearbyWay.red, paint.cachedCoverageBorderColor.red, COLOR_ALPHA_TOLERANCE)
        assertEquals(0.42f, paint.cachedCoverageBorderColor.alpha, COLOR_ALPHA_TOLERANCE)
    }

    private fun displayTile(
        downloadState: TileGridDownloadState?,
        hasCachedCoverage: Boolean = false,
        coverageTileId: DownloadTileId = DownloadTileId(zoom = 12, x = 100, y = 200),
        selectedTileIds: Set<DownloadTileId> = emptySet(),
        screenRect: ScreenRect = ScreenRect(left = 0f, top = 0f, right = 100f, bottom = 100f),
    ): TileGridDisplayTile {
        return TileGridDisplayTile(
            tileId = coverageTileId,
            screenRect = screenRect,
            routeMetrics = TileRouteMetrics(
                intersectsRoute = false,
                intersectingEdgeCount = 0,
                intersectingRouteMeters = 0.0,
            ),
            downloadState = downloadState,
            progressFraction = null,
            representedCoverage = TileGridRepresentedCoverage(
                coverageTiles = if (hasCachedCoverage) {
                    listOf(TileCoverageRect(tileId = coverageTileId, screenRect = screenRect))
                } else {
                    emptyList()
                },
                selectedTileIds = selectedTileIds,
            ),
            downloadRequests = emptyList(),
            estimatedBytes = 0L,
            label = null,
        )
    }
}

private const val COLOR_ALPHA_TOLERANCE = 0.003f

private val TestColors = GeePeeColors(
    paper = Color(0xFF000001),
    ink = Color(0xFF000002),
    line = Color(0xFF000003),
    mist = Color(0xFF000004),
    routeAhead = Color(0xFF000005),
    nearbyWay = Color(0xFF000006),
    onRoute = Color(0xFF000007),
    drifting = Color(0xFF000008),
    offRoute = Color(0xFF000009),
    warning = Color(0xFF00000A),
)
