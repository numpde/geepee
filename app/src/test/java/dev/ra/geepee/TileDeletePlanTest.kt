package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileDeletePlanTest {
    @Test
    fun tileDeleteModeUsesConsistentActionAndConfirmCopy() {
        assertEquals("Delete selected tiles", TileDeleteMode.Selected.actionLabel)
        assertEquals("Delete selected tiles?", TileDeleteMode.Selected.confirmTitle)
        assertEquals("Delete unused tiles", TileDeleteMode.Unused.actionLabel)
        assertEquals("Delete unused tiles?", TileDeleteMode.Unused.confirmTitle)
    }

    @Test
    fun deleteTilesDialogCopyExplainsSelectedTileDeletion() {
        val copy = deleteTilesDialogCopy(
            TileDeletePlan(
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
            TileDeletePlan(
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
