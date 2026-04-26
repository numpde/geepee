package dev.ra.geepee

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal data class GeePeeColors(
    val paper: Color,
    val ink: Color,
    val line: Color,
    val mist: Color,
    val routeAhead: Color,
    val onRoute: Color,
    val drifting: Color,
    val offRoute: Color,
    val warning: Color,
)

private val DarkColors = GeePeeColors(
    paper = Color(0xFF0B1218),
    ink = Color(0xFFE9EFF4),
    line = Color(0xFFCED7DE),
    mist = Color(0xFF16212B),
    routeAhead = Color(0xFFFFB24A),
    onRoute = Color(0xFF4AC28C),
    drifting = Color(0xFFE6A846),
    offRoute = Color(0xFFF06B63),
    warning = Color(0xFFD5A545),
)

private val LightColors = GeePeeColors(
    paper = Color(0xFFF5F1E6),
    ink = Color(0xFF17212A),
    line = Color(0xFF27333E),
    mist = Color(0xFFFDFBF6),
    routeAhead = Color(0xFFE07A00),
    onRoute = Color(0xFF1A7E58),
    drifting = Color(0xFFAC6B00),
    offRoute = Color(0xFFC13F33),
    warning = Color(0xFF805B00),
)

private val LocalGeePeeColors = staticCompositionLocalOf { DarkColors }

@Composable
internal fun GeePeeTheme(
    darkThemeEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkThemeEnabled) DarkColors else LightColors
    CompositionLocalProvider(LocalGeePeeColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkThemeEnabled) {
                darkColorScheme(
                    background = colors.paper,
                    surface = colors.mist,
                    onBackground = colors.ink,
                    onSurface = colors.ink,
                    primary = colors.ink,
                    onPrimary = colors.paper,
                )
            } else {
                lightColorScheme(
                    background = colors.paper,
                    surface = colors.mist,
                    onBackground = colors.ink,
                    onSurface = colors.ink,
                    primary = colors.ink,
                    onPrimary = colors.paper,
                )
            },
            typography = androidx.compose.material3.Typography(
                displaySmall = TextStyle(
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                bodyLarge = TextStyle(
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                ),
                bodyMedium = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                labelLarge = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                titleMedium = TextStyle(
                    fontSize = 18.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            ),
            content = content,
        )
    }
}

@Composable
internal fun geePeeColors(): GeePeeColors = LocalGeePeeColors.current

@Composable
internal fun toneColor(tone: RouteTone): Color {
    val colors = geePeeColors()
    return when (tone) {
        RouteTone.OnRoute -> colors.onRoute
        RouteTone.Drifting -> colors.drifting
        RouteTone.OffRoute -> colors.offRoute
        RouteTone.Warning -> colors.warning
        RouteTone.Ready -> colors.line
        RouteTone.Idle -> colors.line.copy(alpha = 0.78f)
    }
}
