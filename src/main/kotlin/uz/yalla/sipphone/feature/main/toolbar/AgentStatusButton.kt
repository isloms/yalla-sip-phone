package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme
import java.awt.MouseInfo

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

fun DisplayAgentStatus.toAgentStatus(): AgentStatus = when (this) {
    DisplayAgentStatus.ONLINE -> AgentStatus.READY
    DisplayAgentStatus.BUSY -> AgentStatus.AWAY
    DisplayAgentStatus.OFFLINE -> AgentStatus.OFFLINE
}

/**
 * Agent status button — 36dp icon button showing a colored status dot.
 * On click, opens a DialogWindow dropdown (OS-level, renders above JCEF).
 */
@Composable
fun AgentStatusButton(
    currentStatus: AgentStatus,
    isDarkTheme: Boolean,
    locale: String,
    onStatusSelected: (AgentStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val strings = LocalStrings.current
    var showDropdown by remember { mutableStateOf(false) }
    // Screen position where dropdown should open (set on click)
    var dropdownScreenX by remember { mutableStateOf(0) }
    var dropdownScreenY by remember { mutableStateOf(0) }
    val density = LocalDensity.current

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

    // Collapsed: 36dp icon button with colored dot
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.backgroundSecondary)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable {
                // Use mouse position at click time — reliable screen coordinates
                val mousePos = MouseInfo.getPointerInfo()?.location
                if (mousePos != null) {
                    dropdownScreenX = mousePos.x - 20 // offset left so dropdown aligns with button
                    dropdownScreenY = mousePos.y + 10  // below cursor
                }
                showDropdown = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor(displayStatus)),
        )
    }

    // Dropdown as DialogWindow — renders above JCEF
    if (showDropdown) {
        val dropdownWidth = 180
        val dropdownHeight = 130

        // Position from mouse click screen coordinates
        val xDp = with(density) { dropdownScreenX.toDp() }
        val yDp = with(density) { dropdownScreenY.toDp() }

        DialogWindow(
            onCloseRequest = { showDropdown = false },
            title = "",
            state = rememberDialogState(
                position = WindowPosition(xDp, yDp),
                size = DpSize(dropdownWidth.dp, dropdownHeight.dp),
            ),
            resizable = false,
            alwaysOnTop = true,
            undecorated = true,
            transparent = true,
        ) {
            // Clicking outside dismisses
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    showDropdown = false
                }
            })

            YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
                val dropdownColors = LocalYallaColors.current
                val dropdownStrings = LocalStrings.current

                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .width(dropdownWidth.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(dropdownColors.backgroundSecondary)
                            .padding(4.dp),
                    ) {
                        DisplayAgentStatus.entries.forEach { status ->
                            val isSelected = status == displayStatus
                            val statusDotColor = when (status) {
                                DisplayAgentStatus.ONLINE -> dropdownColors.buttonActive
                                DisplayAgentStatus.BUSY -> dropdownColors.pinkSun
                                DisplayAgentStatus.OFFLINE -> dropdownColors.textSubtle
                            }
                            val statusLabel = when (status) {
                                DisplayAgentStatus.ONLINE -> dropdownStrings.agentStatusOnline
                                DisplayAgentStatus.BUSY -> dropdownStrings.agentStatusBusy
                                DisplayAgentStatus.OFFLINE -> dropdownStrings.agentStatusOffline
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (isSelected) Modifier.background(dropdownColors.backgroundTertiary)
                                        else Modifier
                                    )
                                    .clickable {
                                        onStatusSelected(status.toAgentStatus())
                                        showDropdown = false
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusDotColor),
                                )
                                Text(
                                    text = statusLabel,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    color = dropdownColors.textBase,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        fontSize = 13.sp,
                                        color = dropdownColors.buttonActive,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
