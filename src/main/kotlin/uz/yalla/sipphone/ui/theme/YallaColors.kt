package uz.yalla.sipphone.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class YallaColors(
    // Brand
    val brandPrimary: Color,
    val brandPrimaryDisabled: Color,
    val brandPrimaryText: Color,
    // Backgrounds
    val backgroundBase: Color,
    val backgroundSecondary: Color,
    val backgroundTertiary: Color,
    // Text
    val textBase: Color,
    val textSubtle: Color,
    // Borders
    val borderDisabled: Color,
    val borderFilled: Color,
    // Error
    val errorText: Color,
    val errorIndicator: Color,
    // Call states
    val callReady: Color,
    val callIncoming: Color,
    val callMuted: Color,
    val callOffline: Color,
    val callWrapUp: Color,
) {
    companion object {
        val Light = YallaColors(
            brandPrimary = Color(0xFF562DF8),
            brandPrimaryDisabled = Color(0xFFC8CBFA),
            brandPrimaryText = Color(0xFF562DF8),
            backgroundBase = Color(0xFFFFFFFF),
            backgroundSecondary = Color(0xFFF7F7F7),
            backgroundTertiary = Color(0xFFE9EAEA),
            textBase = Color(0xFF101828),
            textSubtle = Color(0xFF6B7280),       // WCAG 5.0:1 on white
            borderDisabled = Color(0xFFE4E7EC),
            borderFilled = Color(0xFF101828),
            errorText = Color(0xFFD32F2F),         // WCAG 5.5:1 on white
            errorIndicator = Color(0xFFF42500),
            callReady = Color(0xFF2E7D32),
            callIncoming = Color(0xFFD97706),
            callMuted = Color(0xFFF42500),
            callOffline = Color(0xFF6B7280),
            callWrapUp = Color(0xFF7C3AED),
        )

        val Dark = YallaColors(
            brandPrimary = Color(0xFF562DF8),
            brandPrimaryDisabled = Color(0xFF2C2D34),
            brandPrimaryText = Color(0xFF8B6FFF),  // WCAG 5.2:1 on dark
            backgroundBase = Color(0xFF1A1A20),
            backgroundSecondary = Color(0xFF21222B),
            backgroundTertiary = Color(0xFF383843),
            textBase = Color(0xFFFFFFFF),
            textSubtle = Color(0xFF9CA3AF),        // WCAG 5.5:1 on dark
            borderDisabled = Color(0xFF383843),
            borderFilled = Color(0xFFFFFFFF),
            errorText = Color(0xFFFF6B6B),          // WCAG 5.8:1 on dark
            errorIndicator = Color(0xFFF42500),
            callReady = Color(0xFF66BB6A),          // WCAG AA on dark
            callIncoming = Color(0xFFF59E0B),
            callMuted = Color(0xFFF42500),
            callOffline = Color(0xFF98A2B3),
            callWrapUp = Color(0xFF8B5CF6),
        )
    }
}

val LocalYallaColors = staticCompositionLocalOf { YallaColors.Light }
