package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.util.formatDuration

@Composable
fun ToolbarContent(
    component: ToolbarComponent,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current

    val callState by component.callState.collectAsState()
    val agentStatus by component.agentStatus.collectAsState()
    val phoneInput by component.phoneInput.collectAsState()

    // Call timer
    var callDuration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(callState) {
        if (callState is CallState.Active) {
            callDuration = 0
            while (isActive) {
                delay(1000)
                callDuration++
            }
        } else {
            callDuration = 0
        }
    }

    // Ringing top border for incoming calls
    val ringingBorderModifier = if (callState is CallState.Ringing && !(callState as CallState.Ringing).isOutbound) {
        Modifier.drawBehind {
            drawLine(
                color = colors.callIncoming,
                start = Offset.Zero,
                end = Offset(size.width, 0f),
                strokeWidth = 2.dp.toPx(),
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tokens.toolbarHeight)
            .shadow(elevation = 2.dp, shape = RectangleShape)
            .background(colors.backgroundSecondary)
            .then(ringingBorderModifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.toolbarHeight)
                .padding(horizontal = tokens.spacingSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Zone A: Agent Status Dropdown
            AgentStatusDropdown(
                currentStatus = agentStatus,
                onStatusSelected = component::setAgentStatus,
            )

            Spacer(Modifier.width(tokens.spacingXs))

            // Vertical divider between Zone A and Zone B
            Box(
                Modifier
                    .width(tokens.dividerThickness)
                    .height(tokens.dividerHeight)
                    .background(colors.backgroundTertiary.copy(alpha = 0.2f)),
            )

            Spacer(Modifier.width(tokens.spacingXs))

            // Zone B: Phone input or call info (flexible width)
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                ZoneBContent(
                    callState = callState,
                    phoneInput = phoneInput,
                    onPhoneInputChange = component::updatePhoneInput,
                    callDuration = callDuration,
                )
            }

            Spacer(Modifier.width(tokens.spacingXs))

            // Vertical divider between Zone B and Zone C
            Box(
                Modifier
                    .width(tokens.dividerThickness)
                    .height(tokens.dividerHeight)
                    .background(colors.backgroundTertiary.copy(alpha = 0.2f)),
            )

            Spacer(Modifier.width(tokens.spacingXs))

            // Zone C: Call action buttons
            CallControls(
                callState = callState,
                phoneInputEmpty = phoneInput.isBlank(),
                onCall = { component.makeCall(phoneInput) },
                onAnswer = component::answerCall,
                onReject = component::rejectCall,
                onHangup = component::hangupCall,
                onToggleMute = component::toggleMute,
                onToggleHold = component::toggleHold,
            )

            Spacer(Modifier.width(tokens.spacingXs))

            // Vertical divider between Zone C and Zone D
            Box(
                Modifier
                    .width(tokens.dividerThickness)
                    .height(tokens.dividerHeight)
                    .background(colors.backgroundTertiary.copy(alpha = 0.2f)),
            )

            Spacer(Modifier.width(tokens.spacingXs))

            // Zone D: Settings gear
            SettingsPopover(
                isDarkTheme = isDarkTheme,
                onThemeToggle = onThemeToggle,
                onLogout = onLogout,
            )

            Spacer(Modifier.width(tokens.spacingXs))

            // Zone E: Call quality indicator
            CallQualityIndicator(callState = callState)
        }
    }
}

@Composable
private fun ZoneBContent(
    callState: CallState,
    phoneInput: String,
    onPhoneInputChange: (String) -> Unit,
    callDuration: Long,
) {
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current

    when (callState) {
        is CallState.Idle -> {
            var isFocused by remember { mutableStateOf(false) }

            BasicTextField(
                value = phoneInput,
                onValueChange = onPhoneInputChange,
                textStyle = TextStyle(
                    color = colors.textBase,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
                singleLine = true,
                cursorBrush = if (isFocused) SolidColor(colors.brandPrimary) else SolidColor(Color.Transparent),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isFocused) colors.backgroundBase else colors.backgroundBase.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (phoneInput.isEmpty()) {
                            Text(
                                text = Strings.PLACEHOLDER_PHONE,
                                color = colors.textSubtle.copy(alpha = 0.5f),
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused },
            )
        }

        is CallState.Ringing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = callState.callerNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textBase,
                )
                if (callState.isOutbound) {
                    Spacer(Modifier.width(tokens.spacingSm))
                    Text(
                        text = Strings.STATUS_RINGING,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSubtle,
                    )
                }
            }
        }

        is CallState.Active -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = callState.remoteNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textBase,
                )
                Spacer(Modifier.width(tokens.spacingSm))
                Text(
                    text = formatDuration(callDuration),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    color = if (callState.isOnHold) {
                        colors.textSubtle
                    } else {
                        colors.textBase
                    },
                )
            }
        }

        is CallState.Ending -> {
            Text(
                text = Strings.STATUS_ENDING_CALL,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSubtle,
            )
        }
    }
}
