package dev.ra.geepee

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SetupTopOverlay(
    state: GeePeeUiState,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()

    Column(
        modifier = modifier.widthIn(max = 340.dp),
    ) {
        if (state.routeModel != null) {
            state.routeName?.let { routeName ->
                Text(
                    text = routeName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink.copy(alpha = 0.7f),
                )
            }
        } else {
            Text(
                text = state.status.headline,
                style = MaterialTheme.typography.displaySmall,
                color = colors.ink,
            )
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Text(
                text = state.status.detail,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
internal fun SetupActions(
    hasRoute: Boolean,
    hasCachedTiles: Boolean,
    sessionRunning: Boolean,
    onPickRoute: () -> Unit,
    onReverseRoute: () -> Unit,
    onDeleteTiles: () -> Unit,
    deleteTilesLabel: String,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showMenu = hasRoute || hasCachedTiles

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasRoute) {
            ActionButton(
                label = "Open GPX",
                onClick = onPickRoute,
                modifier = Modifier.weight(1f),
            )
        }
        if (hasRoute) {
            ActionButton(
                label = if (sessionRunning) "Stop" else "Start",
                onClick = if (sessionRunning) onStopMonitoring else onStartMonitoring,
                modifier = Modifier.weight(1f),
                emphasized = true,
            )
        }
        if (showMenu) {
            SetupMenu(
                hasRoute = hasRoute,
                hasCachedTiles = hasCachedTiles,
                onPickRoute = onPickRoute,
                onReverseRoute = onReverseRoute,
                onDeleteTiles = onDeleteTiles,
                deleteTilesLabel = deleteTilesLabel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val colors = geePeeColors()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (emphasized) colors.ink else colors.mist.copy(alpha = 0.92f),
        contentColor = if (emphasized) colors.mist else colors.ink,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SetupMenu(
    hasRoute: Boolean,
    hasCachedTiles: Boolean,
    onPickRoute: () -> Unit,
    onReverseRoute: () -> Unit,
    onDeleteTiles: () -> Unit,
    deleteTilesLabel: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ActionButton(
            label = "Menu",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = "Open GPX") },
                onClick = {
                    expanded = false
                    onPickRoute()
                },
            )
            if (hasRoute) {
                DropdownMenuItem(
                    text = { Text(text = "Reverse route") },
                    onClick = {
                        expanded = false
                        onReverseRoute()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = deleteTilesLabel) },
                onClick = {
                    expanded = false
                    onDeleteTiles()
                },
                enabled = hasCachedTiles,
            )
        }
    }
}
