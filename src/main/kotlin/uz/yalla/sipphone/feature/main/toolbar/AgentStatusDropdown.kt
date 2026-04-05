package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

private fun parseHexColor(hex: String): Color {
    val sanitized = hex.removePrefix("#")
    val argb = sanitized.toLong(16) or 0xFF000000
    return Color(argb.toInt())
}

/**
 * Agent status selector — expands inline to show status options with labels.
 * No popups or tooltips (they render behind JCEF SwingPanel).
 */
@Composable
fun AgentStatusDropdown(
    currentStatus: AgentStatus,
    onStatusSelected: (AgentStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current
    var expanded by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = expanded,
        modifier = modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "agent-status-toggle",
    ) { isExpanded ->
        if (isExpanded) {
            // Expanded: inline row of status chips with labels
            Row(
                modifier = Modifier
                    .clip(tokens.shapeSmall)
                    .background(colors.backgroundBase.copy(alpha = 0.5f))
                    .padding(horizontal = tokens.spacingXs, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AgentStatus.entries.forEach { status ->
                    val statusColor = remember(status) { parseHexColor(status.colorHex) }
                    val isSelected = status == currentStatus

                    Row(
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clip(tokens.shapeSmall)
                            .heightIn(min = tokens.dropdownItemMinHeight)
                            .then(
                                if (isSelected) Modifier.background(statusColor.copy(alpha = 0.15f))
                                else Modifier
                            )
                            .clickable {
                                onStatusSelected(status)
                                expanded = false
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                        Text(
                            text = status.displayName,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) statusColor else colors.textSubtle,
                        )
                    }
                }
            }
        } else {
            // Collapsed: dot + name + arrow
            Row(
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clip(tokens.shapeSmall)
                    .clickable { expanded = true }
                    .padding(horizontal = tokens.spacingXs, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacingXs),
            ) {
                val currentStatusColor = remember(currentStatus) { parseHexColor(currentStatus.colorHex) }
                Box(
                    Modifier
                        .size(tokens.indicatorDot)
                        .clip(CircleShape)
                        .background(currentStatusColor),
                )
                Text(
                    text = currentStatus.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textBase,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.textSubtle,
                )
            }
        }
    }
}
