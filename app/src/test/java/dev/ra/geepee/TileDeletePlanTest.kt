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
        val plan = TileDeletePlan(
            mode = TileDeleteMode.Selected,
            tileIds = setOf(DownloadTileId(zoom = 10, x = 1, y = 2)),
            freedBytes = 2_400_000L,
        )

        assertEquals("Delete selected tiles?", plan.dialogTitle)
        assertTrue(plan.dialogMessage.contains("selected downloaded tiles"))
        assertTrue(plan.dialogMessage.contains("even if they are on the current route"))
        assertTrue(plan.dialogMessage.contains("about 2.4 MB"))
    }

    @Test
    fun deleteTilesDialogCopyExplainsUnusedTileDeletion() {
        val plan = TileDeletePlan(
            mode = TileDeleteMode.Unused,
            tileIds = setOf(DownloadTileId(zoom = 10, x = 1, y = 2)),
            freedBytes = 1_200_000L,
        )

        assertEquals("Delete unused tiles?", plan.dialogTitle)
        assertTrue(plan.dialogMessage.contains("not needed for the current route or current view"))
        assertTrue(plan.dialogMessage.contains("about 1.2 MB"))
    }
}
