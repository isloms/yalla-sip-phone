package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun YallaSegmentedControl(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    Row(
        modifier = modifier
            .background(colors.backgroundSecondary, tokens.shapeSmall)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SegmentItem(selected = selectedIndex == 0, onClick = { onSelect(0) }) { first() }
        SegmentItem(selected = selectedIndex == 1, onClick = { onSelect(1) }) { second() }
    }
}

@Composable
private fun SegmentItem(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    Box(
        modifier = Modifier
            .size(tokens.segmentButtonSize)
            .clip(tokens.shapeXs)
            .then(
                if (selected) {
                    Modifier.background(colors.brandPrimary.copy(alpha = tokens.alphaLight))
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
