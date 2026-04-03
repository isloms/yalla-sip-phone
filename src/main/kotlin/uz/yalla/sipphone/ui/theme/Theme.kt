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

// Extended semantic colors not in M3 spec
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        success = Color(0xFF2E7D32),
        onSuccess = Color.White,
        successContainer = Color(0xFFD4EDDA),
        onSuccessContainer = Color(0xFF155724),
    )
}

private val SeedColor = Color(0xFF1A5276) // Professional blue

// Custom typography
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
    // Phase 3: displayLarge for dial pad number display (tabular figures font)
)

@Composable
fun YallaSipPhoneTheme(content: @Composable () -> Unit) {
    val colorScheme = rememberDynamicColorScheme(
        seedColor = SeedColor,
        isDark = false,
        isAmoled = false,
        // Phase 2: Add isDark parameter, detect system theme
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        // Provide extended colors
        CompositionLocalProvider(
            LocalExtendedColors provides ExtendedColors(
                success = Color(0xFF2E7D32),
                onSuccess = Color.White,
                successContainer = Color(0xFFD4EDDA),
                onSuccessContainer = Color(0xFF155724),
            ),
            content = content,
        )
    }
}
