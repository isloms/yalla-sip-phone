package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

enum class CallQuality(val label: String) {
    EXCELLENT(Strings.CALL_QUALITY_EXCELLENT),
    GOOD(Strings.CALL_QUALITY_GOOD),
    FAIR(Strings.CALL_QUALITY_FAIR),
    POOR(Strings.CALL_QUALITY_POOR),
}

@Composable
fun CallQualityIndicator(
    callState: CallState,
    quality: CallQuality = CallQuality.GOOD, // Static "Good" until real MOS in Session 2
    modifier: Modifier = Modifier,
) {
    // Reserve space always (40dp), but skip composition when not in active call
    Box(
        modifier = modifier.width(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (callState is CallState.Active) {
            val tokens = LocalAppTokens.current
            val colors = LocalYallaColors.current

            val qualityColor = when (quality) {
                CallQuality.EXCELLENT, CallQuality.GOOD -> colors.callReady
                CallQuality.FAIR -> colors.callIncoming
                CallQuality.POOR -> colors.callMuted
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacingXs),
            ) {
                Box(
                    Modifier
                        .size(tokens.qualityDotSize)
                        .clip(CircleShape)
                        .background(qualityColor),
                )
                Text(
                    text = quality.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSubtle,
                )
            }
        }
    }
}
