package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/** Parse "#RRGGBB" hex string to Compose [Color]. */
private fun parseHexColor(hex: String): Color {
    val sanitized = hex.removePrefix("#")
    val argb = sanitized.toLong(16) or 0xFF000000
    return Color(argb.toInt())
}

@Composable
fun AgentStatusDropdown(
    currentStatus: AgentStatus,
    onStatusSelected: (AgentStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clip(tokens.shapeSmall)
                .clickable { expanded = true }
                .padding(horizontal = tokens.spacingSm, vertical = tokens.spacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacingXs),
        ) {
            Box(
                Modifier
                    .size(tokens.indicatorDot)
                    .clip(CircleShape)
                    .background(parseHexColor(currentStatus.colorHex)),
            )
            Text(
                text = currentStatus.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textBase,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(tokens.iconSmall),
                tint = colors.textSubtle,
            )
        }

        if (expanded) {
            Popup(
                onDismissRequest = { expanded = false },
                popupPositionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
                },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = tokens.shapeMedium,
                    shadowElevation = tokens.elevationMedium,
                    color = colors.backgroundBase,
                ) {
                    Column(modifier = Modifier.widthIn(min = 160.dp)) {
                        AgentStatus.entries.forEach { status ->
                            Row(
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable {
                                        onStatusSelected(status)
                                        expanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
                            ) {
                                Box(
                                    Modifier
                                        .size(tokens.indicatorDot)
                                        .clip(CircleShape)
                                        .background(parseHexColor(status.colorHex)),
                                )
                                Text(
                                    text = status.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textBase,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
