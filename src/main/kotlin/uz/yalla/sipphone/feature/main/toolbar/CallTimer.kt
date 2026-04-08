package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun CallTimer(
    duration: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    AnimatedVisibility(
        visible = duration != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        duration?.let { text ->
            Text(
                text = text,
                fontSize = tokens.textBase,
                fontFamily = FontFamily.Monospace,
                color = colors.brandLight,
                modifier = Modifier
                    .background(colors.brandPrimary.copy(alpha = tokens.alphaLight), tokens.shapeXs)
                    .border(tokens.dividerThickness, colors.brandPrimary.copy(alpha = tokens.alphaMedium), tokens.shapeXs)
                    .padding(horizontal = tokens.spacingSm, vertical = tokens.spacingXs),
            )
        }
    }
}
