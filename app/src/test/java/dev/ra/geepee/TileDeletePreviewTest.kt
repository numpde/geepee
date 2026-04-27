package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileDeletePreviewTest {
    @Test
    fun deleteTilesActionLabelReflectsSelectionState() {
        assertEquals("Delete unused tiles", deleteTilesActionLabel(emptySet()))
        assertEquals(
            "Delete selected tiles",
            deleteTilesActionLabel(setOf(DownloadTileId(zoom = 10, x = 1, y = 2))),
        )
    }

    @Test
    fun deleteTilesDialogCopyExplainsSelectedTileDeletion() {
        val copy = deleteTilesDialogCopy(
            TileDeletePreview(
                mode = TileDeleteMode.Selected,
                tileIds = setOf(DownloadTileId(zoom = 10, x = 1, y = 2)),
                freedBytes = 2_400_000L,
            ),
        )

        assertEquals("Delete selected tiles?", copy.title)
        assertTrue(copy.message.contains("selected downloaded tiles"))
        assertTrue(copy.message.contains("even if they are on the current route"))
        assertTrue(copy.message.contains("about 2.4 MB"))
    }

    @Test
    fun deleteTilesDialogCopyExplainsUnusedTileDeletion() {
        val copy = deleteTilesDialogCopy(
            TileDeletePreview(
                mode = TileDeleteMode.Unused,
                tileIds = setOf(DownloadTileId(zoom = 10, x = 1, y = 2)),
                freedBytes = 1_200_000L,
            ),
        )

        assertEquals("Delete unused tiles?", copy.title)
        assertTrue(copy.message.contains("not needed for the current route or current view"))
        assertTrue(copy.message.contains("about 1.2 MB"))
    }
}
