package dev.ra.geepee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private object MovementBottomChromeMetrics {
    val horizontalPadding = 16.dp
    val verticalPadding = 16.dp
    val primaryControlMaxWidth = 320.dp
    val singlePrimaryControlMinWidth = 156.dp
    val singlePrimaryControlMaxWidth = 196.dp
    val utilityRowMaxWidth = 420.dp
    val utilityToPrimarySpacing = 12.dp
    val primaryControlGap = 10.dp
    const val chromeTextAlpha = 0.84f
}

internal data class MovementMenuState(
    val routeName: String?,
    val darkModeEnabled: Boolean,
    val batterySaverEnabled: Boolean,
    val debugGpsEnabled: Boolean,
    val openInAvailable: Boolean,
    val hasCachedTiles: Boolean,
    val sessionRunning: Boolean,
)

internal data class RoutePoiSelectionInfo(
    val kind: RoutePoiKind,
    val title: String,
    val distanceMeters: Double?,
)

@Composable
private fun bottomChromeFillColor() = Color.Transparent

@Composable
private fun bottomChromeTextColor() = geePeeColors().ink.copy(alpha = MovementBottomChromeMetrics.chromeTextAlpha)

@Composable
internal fun MovementTopOverlay(
    state: GeePeeUiState,
    selectedPois: List<RoutePoiSelectionInfo> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val movementStatus = movementStatusText(state)
    val gpsFreshnessLabel = if (state.debugGpsEnabled) {
        null
    } else {
        rememberGpsFreshnessLabel(
            lastFixTimestampMillis = state.lastFixTimestampMillis,
            sessionRunning = state.sessionRunning,
        )
    }

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
        if (state.debugGpsEnabled) {
            Spacer(modifier = Modifier.padding(top = 4.dp))
            Text(
                text = "Debug GPS",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink.copy(alpha = 0.62f),
            )
        }
        gpsFreshnessLabel?.let { freshness ->
            Spacer(modifier = Modifier.padding(top = 4.dp))
            Text(
                text = freshness,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink.copy(alpha = 0.58f),
            )
        }
        state.mapInfo.availabilityText?.let { availabilityText ->
            Spacer(modifier = Modifier.padding(top = 2.dp))
            Text(
                text = availabilityText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink.copy(alpha = 0.56f),
            )
        }
        if (selectedPois.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            RoutePoiInfoPanel(
                selections = selectedPois,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun MovementBottomControls(
    menuState: MovementMenuState,
    showSetDebugGpsHere: Boolean,
    onPickRoute: () -> Unit,
    onRequestLocationRefresh: () -> Unit,
    onStartMonitoring: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleBatterySaver: () -> Unit,
    onToggleDebugGps: () -> Unit,
    onDeleteTiles: () -> Unit,
    deleteTilesLabel: String,
    onRequestScreenPinning: () -> Unit,
    onStopMonitoring: () -> Unit,
    onSetDebugGpsHere: () -> Unit,
    onOpenInExternalMap: () -> Unit,
    onOpenInOsmBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (showSetDebugGpsHere) {
        Row(
            modifier = modifier.widthIn(max = MovementBottomChromeMetrics.primaryControlMaxWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayActionPill(
                label = "Set GPS here",
                onClick = onSetDebugGpsHere,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(MovementBottomChromeMetrics.primaryControlGap))
            MovementMenu(
                state = menuState,
                onPickRoute = onPickRoute,
                onRequestLocationRefresh = onRequestLocationRefresh,
                onOpenInExternalMap = onOpenInExternalMap,
                onOpenInOsmBrowser = onOpenInOsmBrowser,
                onStartMonitoring = onStartMonitoring,
                onToggleDarkMode = onToggleDarkMode,
                onToggleBatterySaver = onToggleBatterySaver,
                onToggleDebugGps = onToggleDebugGps,
                onDeleteTiles = onDeleteTiles,
                deleteTilesLabel = deleteTilesLabel,
                onRequestScreenPinning = onRequestScreenPinning,
                onStopMonitoring = onStopMonitoring,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        MovementMenu(
            state = menuState,
            onPickRoute = onPickRoute,
            onRequestLocationRefresh = onRequestLocationRefresh,
            onOpenInExternalMap = onOpenInExternalMap,
            onOpenInOsmBrowser = onOpenInOsmBrowser,
            onStartMonitoring = onStartMonitoring,
            onToggleDarkMode = onToggleDarkMode,
            onToggleBatterySaver = onToggleBatterySaver,
            onToggleDebugGps = onToggleDebugGps,
            onDeleteTiles = onDeleteTiles,
            deleteTilesLabel = deleteTilesLabel,
            onRequestScreenPinning = onRequestScreenPinning,
            onStopMonitoring = onStopMonitoring,
            modifier = modifier.widthIn(
                min = MovementBottomChromeMetrics.singlePrimaryControlMinWidth,
                max = MovementBottomChromeMetrics.singlePrimaryControlMaxWidth,
            ),
        )
    }
}

@Composable
internal fun MovementBottomChrome(
    primaryControls: @Composable () -> Unit,
    leadingUtility: (@Composable () -> Unit)?,
    trailingUtility: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MovementBottomChromeMetrics.horizontalPadding,
                vertical = MovementBottomChromeMetrics.verticalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (leadingUtility != null || trailingUtility != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = MovementBottomChromeMetrics.utilityRowMaxWidth),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    leadingUtility?.invoke()
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    trailingUtility?.invoke()
                }
            }
            Spacer(modifier = Modifier.height(MovementBottomChromeMetrics.utilityToPrimarySpacing))
        }
        primaryControls()
    }
}

@Composable
internal fun DebugGpsCrosshair(
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    Canvas(
        modifier = modifier
            .size(34.dp),
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val arm = 9.dp.toPx()
        val gap = 3.dp.toPx()
        val strokeWidth = 1.75.dp.toPx()
        val ringRadius = 4.dp.toPx()

        drawCircle(
            color = colors.ink.copy(alpha = 0.82f),
            radius = ringRadius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
        )
        drawLine(
            color = colors.ink.copy(alpha = 0.84f),
            start = Offset(center.x - arm, center.y),
            end = Offset(center.x - gap, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.ink.copy(alpha = 0.84f),
            start = Offset(center.x + gap, center.y),
            end = Offset(center.x + arm, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.ink.copy(alpha = 0.84f),
            start = Offset(center.x, center.y - arm),
            end = Offset(center.x, center.y - gap),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.ink.copy(alpha = 0.84f),
            start = Offset(center.x, center.y + gap),
            end = Offset(center.x, center.y + arm),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun ScaleBar(
    routeScale: RouteScale,
    windowWidthMeters: Double = routeScale.windowWidthMeters,
    viewportWidthPx: Float,
    onCycleScale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    val density = LocalDensity.current
    val barDistanceMeters = remember(windowWidthMeters) { scaleBarDistanceMeters(windowWidthMeters) }
    val barWidthPx = ((barDistanceMeters / windowWidthMeters) * viewportWidthPx).toFloat()
    val barWidthDp = with(density) { barWidthPx.toDp() }
    val fillColor = bottomChromeFillColor()
    val textColor = bottomChromeTextColor()

    Surface(
        modifier = modifier.clickable(onClick = onCycleScale),
        shape = RoundedCornerShape(14.dp),
        color = fillColor,
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
                color = textColor,
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
    state: MovementMenuState,
    onPickRoute: () -> Unit,
    onRequestLocationRefresh: () -> Unit,
    onOpenInExternalMap: () -> Unit,
    onOpenInOsmBrowser: () -> Unit,
    onStartMonitoring: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleBatterySaver: () -> Unit,
    onToggleDebugGps: () -> Unit,
    onDeleteTiles: () -> Unit,
    deleteTilesLabel: String,
    onRequestScreenPinning: () -> Unit,
    onStopMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OverlayActionPill(
            label = "Menu",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.mist,
        ) {
            state.routeName?.let { loadedRoute ->
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
            if (state.openInAvailable) {
                DropdownMenuItem(
                    text = { Text(text = "Open in…") },
                    onClick = {
                        expanded = false
                        onOpenInExternalMap()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = "Open OSM page") },
                    onClick = {
                        expanded = false
                        onOpenInOsmBrowser()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = if (state.darkModeEnabled) "Light mode" else "Dark mode") },
                onClick = {
                    expanded = false
                    onToggleDarkMode()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "Battery saver: ${if (state.batterySaverEnabled) "on" else "off"}") },
                onClick = {
                    expanded = false
                    onToggleBatterySaver()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "Open GPX") },
                onClick = {
                    expanded = false
                    onPickRoute()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "Refresh GPS") },
                onClick = {
                    expanded = false
                    onRequestLocationRefresh()
                },
                enabled = !state.debugGpsEnabled,
            )
            DropdownMenuItem(
                text = { Text(text = if (state.debugGpsEnabled) "Use real GPS" else "Debug GPS") },
                onClick = {
                    expanded = false
                    onToggleDebugGps()
                },
            )
            DropdownMenuItem(
                text = { Text(text = deleteTilesLabel) },
                onClick = {
                    expanded = false
                    onDeleteTiles()
                },
                enabled = state.hasCachedTiles,
            )
            DropdownMenuItem(
                text = { Text(text = "Pin app") },
                onClick = {
                    expanded = false
                    onRequestScreenPinning()
                },
            )
            DropdownMenuItem(
                text = { Text(text = if (state.sessionRunning) "Stop session" else "Start session") },
                onClick = {
                    expanded = false
                    if (state.sessionRunning) {
                        onStopMonitoring()
                    } else {
                        onStartMonitoring()
                    }
                },
            )
        }
    }
}

@Composable
private fun OverlayActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = geePeeColors()
    val fillColor = bottomChromeFillColor()
    val textColor = bottomChromeTextColor()
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
        ),
        shape = RoundedCornerShape(999.dp),
        color = fillColor,
        contentColor = colors.ink,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
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
private fun RoutePoiInfoPanel(
    selections: List<RoutePoiSelectionInfo>,
    modifier: Modifier = Modifier,
) {
    val colors = geePeeColors()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = colors.mist.copy(alpha = 0.9f),
        contentColor = colors.ink,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            selections.forEach { selection ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.bodyMedium,
                        color = routePoiAccentColor(selection.kind),
                    )
                    Column {
                        Text(
                            text = selection.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink.copy(alpha = 0.88f),
                        )
                        selection.distanceMeters?.let { distanceMeters ->
                            Text(
                                text = "${formatDistance(distanceMeters)} away",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.ink.copy(alpha = 0.64f),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun routePoiSelectionTitle(
    marker: RoutePoiScreenMarker,
): String {
    return marker.name ?: when (marker.kind) {
        RoutePoiKind.DrinkingWater -> "Drinking water"
        RoutePoiKind.Toilets -> "Toilets"
        RoutePoiKind.Shelter -> "Shelter"
        RoutePoiKind.PicnicSite -> "Picnic site"
        RoutePoiKind.BicycleRepairStation -> "Bicycle repair station"
        RoutePoiKind.BicycleShop -> "Bicycle shop"
    }
}

internal fun routePoiAccentColor(kind: RoutePoiKind): Color {
    return when (kind) {
        RoutePoiKind.DrinkingWater -> Color(0xFF1E88E5)
        RoutePoiKind.Toilets -> Color(0xFF6D4C41)
        RoutePoiKind.Shelter -> Color(0xFF43A047)
        RoutePoiKind.PicnicSite -> Color(0xFFF9A825)
        RoutePoiKind.BicycleRepairStation -> Color(0xFFEF6C00)
        RoutePoiKind.BicycleShop -> Color(0xFF8E24AA)
    }
}

private fun scaleBarDistanceMeters(windowWidthMeters: Double): Double {
    val target = windowWidthMeters * 0.28
    val candidates = listOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
    return candidates.lastOrNull { it <= target } ?: candidates.first()
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
