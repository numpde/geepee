package dev.ra.geepee

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun TileDeleteDialog(
    plan: TileDeletePlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = plan.dialogCopy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = copy.title)
        },
        text = {
            Text(text = copy.message)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = plan.tileCount > 0,
            ) {
                Text(text = "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        },
    )
}
