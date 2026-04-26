package dev.ra.geepee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SetupTopOverlay(
    state: GeePeeUiState,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val tileMode = state.setupOverviewMode == SetupOverviewMode.Tiles
    val headline = if (tileMode && state.routeModel != null) {
        "Tap tiles to fetch context"
    } else {
        state.status.headline
    }
    val detail = if (tileMode && state.routeModel != null) {
        "Download only the areas worth spending network on."
    } else {
        state.status.detail
    }

    Column(
        modifier = modifier.widthIn(max = 340.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.displaySmall,
            color = colors.ink,
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink.copy(alpha = 0.78f),
        )
        state.routeName?.let { routeName ->
            Spacer(modifier = Modifier.padding(top = 10.dp))
            Text(
                text = routeName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
internal fun SetupActions(
    hasRoute: Boolean,
    sessionRunning: Boolean,
    overviewMode: SetupOverviewMode,
    onPickRoute: () -> Unit,
    onToggleOverviewMode: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionButton(
            label = if (!hasRoute) "Load route" else "Change route",
            onClick = onPickRoute,
            modifier = Modifier.weight(1f),
        )
        if (hasRoute) {
            ActionButton(
                label = if (overviewMode == SetupOverviewMode.Route) "Tiles" else "Route",
                onClick = onToggleOverviewMode,
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                label = if (sessionRunning) "Stop" else "Start",
                onClick = if (sessionRunning) onStopMonitoring else onStartMonitoring,
                modifier = Modifier.weight(1f),
                emphasized = true,
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
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (emphasized) colors.ink else colors.mist.copy(alpha = 0.92f),
            contentColor = if (emphasized) colors.mist else colors.ink,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
