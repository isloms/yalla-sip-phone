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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
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
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Display-level agent status — maps 5 internal statuses to 3 UI states.
 */
enum class DisplayAgentStatus {
    ONLINE,  // READY
    BUSY,    // AWAY, BREAK, WRAP_UP
    OFFLINE, // OFFLINE
}

fun AgentStatus.toDisplayStatus(): DisplayAgentStatus = when (this) {
    AgentStatus.READY -> DisplayAgentStatus.ONLINE
    AgentStatus.AWAY, AgentStatus.BREAK, AgentStatus.WRAP_UP -> DisplayAgentStatus.BUSY
    AgentStatus.OFFLINE -> DisplayAgentStatus.OFFLINE
}

/**
 * Maps display status back to internal AgentStatus for bridge communication.
 * ONLINE → READY, BUSY → AWAY (default busy mapping), OFFLINE → OFFLINE.
 */
fun DisplayAgentStatus.toAgentStatus(): AgentStatus = when (this) {
    DisplayAgentStatus.ONLINE -> AgentStatus.READY
    DisplayAgentStatus.BUSY -> AgentStatus.AWAY
    DisplayAgentStatus.OFFLINE -> AgentStatus.OFFLINE
}

/**
 * Agent status button — 36dp icon button showing a colored status dot.
 * On click, expands inline to show 3 status options (no Popup — avoids JCEF z-order issues).
 */
@Composable
fun AgentStatusButton(
    currentStatus: AgentStatus,
    onStatusSelected: (AgentStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }

    val displayStatus = currentStatus.toDisplayStatus()

    fun dotColor(status: DisplayAgentStatus): Color = when (status) {
        DisplayAgentStatus.ONLINE -> colors.buttonActive
        DisplayAgentStatus.BUSY -> colors.pinkSun
        DisplayAgentStatus.OFFLINE -> colors.textSubtle
    }

    fun label(status: DisplayAgentStatus): String = when (status) {
        DisplayAgentStatus.ONLINE -> strings.agentStatusOnline
        DisplayAgentStatus.BUSY -> strings.agentStatusBusy
        DisplayAgentStatus.OFFLINE -> strings.agentStatusOffline
    }

    AnimatedContent(
        targetState = expanded,
        modifier = modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "agent-status-toggle",
    ) { isExpanded ->
        if (isExpanded) {
            // Expanded: inline row of 3 status options
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.backgroundSecondary)
                    .padding(horizontal = tokens.spacingXs, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DisplayAgentStatus.entries.forEach { status ->
                    val isSelected = status == displayStatus

                    Row(
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clip(RoundedCornerShape(6.dp))
                            .heightIn(min = 32.dp)
                            .then(
                                if (isSelected) {
                                    Modifier.background(dotColor(status).copy(alpha = 0.15f))
                                } else {
                                    Modifier
                                },
                            )
                            .clickable {
                                onStatusSelected(status.toAgentStatus())
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
                                .background(dotColor(status)),
                        )
                        Text(
                            text = label(status),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) dotColor(status) else colors.textSubtle,
                        )
                    }
                }
            }
        } else {
            // Collapsed: 36dp icon button with colored dot
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.backgroundSecondary)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { expanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor(displayStatus)),
                )
            }
        }
    }
}
