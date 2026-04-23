package dev.ra.geepee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun MovementTopOverlay(
    state: GeePeeUiState,
    onRequestLocationRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val movementStatus = movementStatusText(state)
    val gpsFreshnessLabel = rememberGpsFreshnessLabel(
        lastFixTimestampMillis = state.lastFixTimestampMillis,
        sessionRunning = state.sessionRunning,
    )

    Column(
        modifier = modifier,
    ) {
        movementStatus?.let { overlayText ->
            Text(
                text = overlayText,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink.copy(alpha = 0.82f),
            )
        }
        gpsFreshnessLabel?.let { freshness ->
            Spacer(modifier = Modifier.padding(top = 4.dp))
            Text(
                text = freshness,
                modifier = Modifier.clickable(onClick = onRequestLocationRefresh),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink.copy(alpha = 0.58f),
            )
        }
    }
}

@Composable
internal fun ScaleBar(
    routeScale: RouteScale,
    viewportWidthPx: Float,
    onCycleScale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val density = LocalDensity.current
    val barDistanceMeters = remember(routeScale) { routeScale.scaleBarDistanceMeters() }
    val barWidthPx = ((barDistanceMeters / routeScale.windowWidthMeters) * viewportWidthPx).toFloat()
    val barWidthDp = with(density) { barWidthPx.toDp() }

    Surface(
        modifier = modifier.clickable(onClick = onCycleScale),
        shape = RoundedCornerShape(14.dp),
        color = colors.mist.copy(alpha = 0.94f),
        contentColor = colors.ink,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = formatDistance(barDistanceMeters),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink.copy(alpha = 0.86f),
            )
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Canvas(
                modifier = Modifier.size(width = barWidthDp + 2.dp, height = 14.dp),
            ) {
                val y = size.height / 2f
                val start = Offset(1.dp.toPx(), y)
                val end = Offset(size.width - 1.dp.toPx(), y)
                val tickHalf = 4.dp.toPx()

                drawLine(
                    color = colors.ink,
                    start = start,
                    end = end,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colors.ink,
                    start = Offset(start.x, y - tickHalf),
                    end = Offset(start.x, y + tickHalf),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colors.ink,
                    start = Offset(end.x, y - tickHalf),
                    end = Offset(end.x, y + tickHalf),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
internal fun MovementMenu(
    routeName: String?,
    darkModeEnabled: Boolean,
    batterySaverEnabled: Boolean,
    onPickRoute: () -> Unit,
    onStartMonitoring: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleBatterySaver: () -> Unit,
    onRequestScreenPinning: () -> Unit,
    onStopMonitoring: () -> Unit,
    sessionRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.textButtonColors(
                containerColor = colors.mist.copy(alpha = 0.92f),
                contentColor = colors.ink,
            ),
        ) {
            Text(
                text = "Menu",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.mist,
        ) {
            routeName?.let { loadedRoute ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = loadedRoute,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink.copy(alpha = 0.66f),
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
            DropdownMenuItem(
                text = { Text(text = if (darkModeEnabled) "Light mode" else "Dark mode") },
                onClick = {
                    expanded = false
                    onToggleDarkMode()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "Battery saver: ${if (batterySaverEnabled) "on" else "off"}") },
                onClick = {
                    expanded = false
                    onToggleBatterySaver()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "Change route") },
                onClick = {
                    expanded = false
                    onPickRoute()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "Pin app") },
                onClick = {
                    expanded = false
                    onRequestScreenPinning()
                },
            )
            DropdownMenuItem(
                text = { Text(text = if (sessionRunning) "Stop session" else "Start session") },
                onClick = {
                    expanded = false
                    if (sessionRunning) {
                        onStopMonitoring()
                    } else {
                        onStartMonitoring()
                    }
                },
            )
        }
    }
}

private fun movementStatusText(state: GeePeeUiState): String? {
    if (!state.sessionRunning || state.routeModel == null) {
        return null
    }
    return when {
        state.routeLoading -> "Reading route"
        !state.hasLocationPermission -> "Allow location"
        state.analysis == null -> state.status.headline
        state.status.tone == RouteTone.Warning -> state.status.headline
        state.status.tone == RouteTone.OnRoute -> "On route"
        else -> null
    }
}

@Composable
private fun rememberGpsFreshnessLabel(
    lastFixTimestampMillis: Long?,
    sessionRunning: Boolean,
): String? {
    var nowMillis by remember(lastFixTimestampMillis, sessionRunning) {
        mutableLongStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(lastFixTimestampMillis, sessionRunning) {
        if (!sessionRunning || lastFixTimestampMillis == null) {
            return@LaunchedEffect
        }
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    if (!sessionRunning) {
        return null
    }
    if (lastFixTimestampMillis == null) {
        return "GPS: waiting"
    }
    val ageMillis = (nowMillis - lastFixTimestampMillis).coerceAtLeast(0L)
    return "GPS: ${formatAge(ageMillis)} ago"
}
