package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
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
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

enum class DisplayAgentStatus { ONLINE, BUSY, OFFLINE }

fun AgentStatus.toDisplayStatus(): DisplayAgentStatus = when (this) {
    AgentStatus.READY -> DisplayAgentStatus.ONLINE
    AgentStatus.AWAY, AgentStatus.BREAK, AgentStatus.WRAP_UP -> DisplayAgentStatus.BUSY
    AgentStatus.OFFLINE -> DisplayAgentStatus.OFFLINE
}

fun DisplayAgentStatus.toAgentStatus(): AgentStatus = when (this) {
    DisplayAgentStatus.ONLINE -> AgentStatus.READY
    DisplayAgentStatus.BUSY -> AgentStatus.AWAY
    DisplayAgentStatus.OFFLINE -> AgentStatus.OFFLINE
}

@Composable
fun AgentStatusButton(
    currentStatus: AgentStatus,
    onStatusSelected: (AgentStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    var showDropdown by remember { mutableStateOf(false) }
    val displayStatus = currentStatus.toDisplayStatus()

    fun dotColor(status: DisplayAgentStatus): Color = when (status) {
        DisplayAgentStatus.ONLINE -> colors.statusOnline
        DisplayAgentStatus.BUSY -> colors.statusWarning
        DisplayAgentStatus.OFFLINE -> colors.textSubtle
    }

    fun label(status: DisplayAgentStatus): String = when (status) {
        DisplayAgentStatus.ONLINE -> strings.agentStatusOnline
        DisplayAgentStatus.BUSY -> strings.agentStatusBusy
        DisplayAgentStatus.OFFLINE -> strings.agentStatusOffline
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(tokens.iconButtonSize)
                .clip(tokens.shapeSmall)
                .background(colors.backgroundSecondary)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { showDropdown = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(tokens.indicatorDotLarge)
                    .clip(CircleShape)
                    .background(dotColor(displayStatus)),
            )
        }

        // Dropdown — regular Compose DropdownMenu (renders above JCEF via compose.layers.type=WINDOW)
        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false },
            modifier = Modifier
                .background(colors.backgroundSecondary)
                .padding(tokens.spacingXs),
        ) {
            DisplayAgentStatus.entries.forEach { status ->
                val isSelected = status == displayStatus

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(tokens.shapeSmall)
                        .then(if (isSelected) Modifier.background(colors.backgroundTertiary) else Modifier)
                        .clickable {
                            onStatusSelected(status.toAgentStatus())
                            showDropdown = false
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = tokens.spacingMdSm, vertical = tokens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
                ) {
                    Box(
                        Modifier
                            .size(tokens.indicatorDot)
                            .clip(CircleShape)
                            .background(dotColor(status)),
                    )
                    Text(
                        text = label(status),
                        fontSize = tokens.textMd,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = colors.textBase,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Text("\u2713", fontSize = tokens.textMd, color = colors.brandPrimary)
                    }
                }
            }
        }
    }
}
