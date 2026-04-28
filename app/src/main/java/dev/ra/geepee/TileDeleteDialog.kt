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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = plan.dialogTitle)
        },
        text = {
            Text(text = plan.dialogMessage)
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
