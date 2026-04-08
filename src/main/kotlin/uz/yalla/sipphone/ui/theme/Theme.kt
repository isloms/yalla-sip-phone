package uz.yalla.sipphone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.materialkolor.rememberDynamicColorScheme
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.strings.RuStrings
import uz.yalla.sipphone.ui.strings.StringResources
import uz.yalla.sipphone.ui.strings.UzStrings

data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

private val DefaultExtendedColors = ExtendedColors(
    success = Color(0xFF2E7D32),
    onSuccess = Color.White,
    successContainer = Color(0xFFD4EDDA),
    onSuccessContainer = Color(0xFF155724),
)

val LocalExtendedColors = staticCompositionLocalOf { DefaultExtendedColors }

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
)

@Composable
fun YallaSipPhoneTheme(
    isDark: Boolean = false,
    locale: String = "uz",
    content: @Composable () -> Unit,
) {
    val seedColor = Color(0xFF562DF8) // Yalla purple
    val colorScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        isAmoled = false,
    )
    val yallaColors = if (isDark) YallaColors.Dark else YallaColors.Light
    val extendedColors = ExtendedColors(
        success = yallaColors.callReady,
        onSuccess = Color.White,
        successContainer = if (isDark) Color(0xFF1B5E20) else Color(0xFFD4EDDA),
        onSuccessContainer = if (isDark) Color(0xFFA5D6A7) else Color(0xFF155724),
    )

    val strings: StringResources = when (locale) {
        "ru" -> RuStrings
        else -> UzStrings
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalExtendedColors provides extendedColors,
            LocalAppTokens provides AppTokens(),
            LocalYallaColors provides yallaColors,
            LocalStrings provides strings,
            content = content,
        )
    }
}
