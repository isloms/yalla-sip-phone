package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Tooltip anchored to the component (not following the cursor).
 * Appears above the component, centered horizontally.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YallaTooltip(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = 400,
    content: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    TooltipArea(
        tooltip = {
            Column(
                modifier = Modifier
                    .background(colors.backgroundSecondary, tokens.shapeSmall)
                    .border(tokens.dividerThickness, colors.borderDefault, tokens.shapeSmall)
                    .padding(horizontal = tokens.spacingMdSm, vertical = tokens.spacingSm),
            ) {
                tooltip()
            }
        },
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.TopCenter,
            alignment = Alignment.TopCenter,
            offset = DpOffset(0.dp, (-8).dp),
        ),
        delayMillis = delayMillis,
        modifier = modifier,
        content = content,
    )
}
