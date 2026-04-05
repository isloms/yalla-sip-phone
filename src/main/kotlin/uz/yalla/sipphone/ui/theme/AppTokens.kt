package uz.yalla.sipphone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

data class AppTokens(
    // Spacing
    val spacingXs: Dp = 4.dp,
    val spacingSm: Dp = 8.dp,
    val spacingMdSm: Dp = 12.dp,
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

    // Shapes
    val shapeSmall: Shape = RoundedCornerShape(cornerSmall),
    val shapeMedium: Shape = RoundedCornerShape(cornerMedium),
    val shapeLarge: Shape = RoundedCornerShape(cornerLarge),

    // Window sizes
    val registrationWindowSize: DpSize = DpSize(420.dp, 520.dp),
    val dialerWindowSize: DpSize = DpSize(800.dp, 180.dp),
    val windowMinWidth: Dp = 380.dp,
    val windowMinHeight: Dp = 180.dp,

    // Icons
    val iconSmall: Dp = 16.dp,
    val iconDefault: Dp = 20.dp,
    val iconMedium: Dp = 24.dp,

    // Indicators
    val indicatorDot: Dp = 8.dp,
    val indicatorDotSmall: Dp = 7.dp,
    val dividerThickness: Dp = 1.dp,
    val dividerHeight: Dp = 32.dp,

    // Dropdown
    val dropdownItemMinHeight: Dp = 36.dp,

    // Progress
    val progressSmall: Dp = 18.dp,
    val progressStrokeSmall: Dp = 2.dp,

    // Alpha
    val alphaDisabled: Float = 0.6f,
    val alphaHint: Float = 0.7f,

    // Button sizes
    val iconButtonSize: Dp = 40.dp,
    val iconButtonSizeLarge: Dp = 48.dp,

    // Toolbar
    val toolbarHeight: Dp = 52.dp,
    val toolbarPaddingH: Dp = 12.dp,
    val toolbarZoneGap: Dp = 8.dp,

    // Call quality
    val qualityDotSize: Dp = 8.dp,

    // Animation
    val animFast: Int = 200,
    val animMedium: Int = 300,
    val animSlow: Int = 350,

    // Window sizes (extended)
    val loginWindowSize: DpSize = DpSize(420.dp, 520.dp),
    val mainWindowMinWidth: Dp = 1280.dp,
    val mainWindowMinHeight: Dp = 720.dp,
) {
    fun minimumAwtDimension(): java.awt.Dimension =
        java.awt.Dimension(windowMinWidth.value.toInt(), windowMinHeight.value.toInt())
}

val LocalAppTokens = staticCompositionLocalOf { AppTokens() }
