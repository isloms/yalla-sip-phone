package uz.yalla.sipphone.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class AppTokens(
    // Spacing
    val spacingXs: Dp = 4.dp,
    val spacingSm: Dp = 8.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val spacingXl: Dp = 32.dp,

    // Elevation
    val elevationNone: Dp = 0.dp,
    val elevationLow: Dp = 2.dp,
    val elevationMedium: Dp = 6.dp,

    // Corner radius
    val cornerSmall: Dp = 8.dp,
    val cornerMedium: Dp = 12.dp,
    val cornerLarge: Dp = 16.dp,

    // Window
    val windowWidth: Dp = 420.dp,
    val windowHeight: Dp = 600.dp,
    val windowMinWidth: Dp = 380.dp,
    val windowMinHeight: Dp = 480.dp,
)

val LocalAppTokens = staticCompositionLocalOf { AppTokens() }
