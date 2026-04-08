package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Brand tint surface showing call duration. Only visible during active call.
 *
 * - Background: buttonActive @ 15% alpha
 * - Border: buttonActive @ 30% alpha
 * - Text: iconDisabled (#C8CBFA)
 * - Font: monospace, tabular-nums
 * - Rounded 6dp
 */
@Composable
fun CallTimer(
    duration: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val shape = RoundedCornerShape(6.dp)

    AnimatedVisibility(
        visible = duration != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        duration?.let { text ->
            Text(
                text = text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.iconDisabled,
                modifier = Modifier
                    .background(colors.buttonActive.copy(alpha = 0.15f), shape)
                    .border(1.dp, colors.buttonActive.copy(alpha = 0.3f), shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
