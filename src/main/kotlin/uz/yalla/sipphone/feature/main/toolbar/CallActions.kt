package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalYallaColors

private val ButtonSize = 36.dp
private val IconSize = 18.dp
private val ButtonShape = RoundedCornerShape(8.dp)

/**
 * Call action buttons — state-dependent row of 36dp icon buttons.
 *
 * - Idle: 3 disabled buttons (call, mute, hold) at 40% opacity
 * - Ringing inbound: Answer (brand bg, glow) + Reject (red bg) + "Qo'ng'iroq..." label
 * - Ringing outbound: Cancel (red bg)
 * - Active: Hangup (red bg) + Mute toggle + Hold toggle
 * - Ending: no buttons
 */
@Composable
fun CallActions(
    callState: CallState,
    phoneInputEmpty: Boolean,
    onCall: () -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val strings = LocalStrings.current

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (callState) {
                is CallState.Idle -> {
                    val disabledColors = IconButtonDefaults.iconButtonColors(
                        containerColor = colors.buttonDisabled,
                        contentColor = colors.iconDisabled,
                        disabledContainerColor = colors.buttonDisabled,
                        disabledContentColor = colors.iconDisabled,
                    )
                    // Call button — enabled only when phone input has text
                    IconButton(
                        onClick = onCall,
                        enabled = !phoneInputEmpty,
                        modifier = Modifier.size(ButtonSize),
                        colors = disabledColors,
                    ) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = strings.buttonCall,
                            modifier = Modifier.size(IconSize),
                        )
                    }
                    // Mute button (disabled)
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(ButtonSize),
                        colors = disabledColors,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = strings.buttonMute,
                            modifier = Modifier.size(IconSize),
                        )
                    }
                    // Hold button (disabled)
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(ButtonSize),
                        colors = disabledColors,
                    ) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = strings.buttonHold,
                            modifier = Modifier.size(IconSize),
                        )
                    }
                }

                is CallState.Ringing -> {
                    if (!callState.isOutbound) {
                        // Answer button — brand bg
                        IconButton(
                            onClick = onAnswer,
                            modifier = Modifier
                                .size(ButtonSize)
                                .pointerHoverIcon(PointerIcon.Hand),
                                colors = IconButtonDefaults.iconButtonColors(
                                containerColor = colors.buttonActive,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.Phone,
                                contentDescription = strings.buttonAnswer,
                                modifier = Modifier.size(IconSize),
                            )
                        }

                        // Reject button — red bg
                        IconButton(
                            onClick = onReject,
                            modifier = Modifier
                                .size(ButtonSize)
                                .pointerHoverIcon(PointerIcon.Hand),
                                colors = IconButtonDefaults.iconButtonColors(
                                containerColor = colors.iconRed,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = strings.buttonReject,
                                modifier = Modifier.size(IconSize),
                            )
                        }

                        // "Qo'ng'iroq..." label
                        Text(
                            text = strings.sipRinging,
                            fontSize = 12.sp,
                            color = colors.iconDisabled,
                            modifier = Modifier
                                .background(
                                    colors.buttonActive.copy(alpha = 0.15f),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    } else {
                        // Outbound ringing — cancel button
                        IconButton(
                            onClick = onHangup,
                            modifier = Modifier
                                .size(ButtonSize)
                                .pointerHoverIcon(PointerIcon.Hand),
                                colors = IconButtonDefaults.iconButtonColors(
                                containerColor = colors.iconRed,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.CallEnd,
                                contentDescription = strings.buttonHangup,
                                modifier = Modifier.size(IconSize),
                            )
                        }

                        Text(
                            text = strings.sipRinging,
                            fontSize = 12.sp,
                            color = colors.iconDisabled,
                            modifier = Modifier
                                .background(
                                    colors.buttonActive.copy(alpha = 0.15f),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                is CallState.Active -> {
                    // Hangup — red bg
                    IconButton(
                        onClick = onHangup,
                        modifier = Modifier
                            .size(ButtonSize)
                            .pointerHoverIcon(PointerIcon.Hand),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = colors.iconRed,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.CallEnd,
                            contentDescription = strings.buttonHangup,
                            modifier = Modifier.size(IconSize),
                        )
                    }

                    // Mute toggle
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(ButtonSize)
                            .pointerHoverIcon(PointerIcon.Hand),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (callState.isMuted) colors.buttonActive.copy(alpha = 0.15f) else colors.backgroundSecondary,
                            contentColor = if (callState.isMuted) colors.buttonActive else colors.iconSubtle,
                        ),
                    ) {
                        Icon(
                            if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (callState.isMuted) strings.buttonUnmute else strings.buttonMute,
                            modifier = Modifier.size(IconSize),
                        )
                    }

                    // Hold toggle
                    IconButton(
                        onClick = onToggleHold,
                        modifier = Modifier
                            .size(ButtonSize)
                            .pointerHoverIcon(PointerIcon.Hand),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (callState.isOnHold) colors.buttonActive.copy(alpha = 0.15f) else colors.backgroundSecondary,
                            contentColor = if (callState.isOnHold) colors.buttonActive else colors.iconSubtle,
                        ),
                    ) {
                        Icon(
                            if (callState.isOnHold) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (callState.isOnHold) strings.buttonResume else strings.buttonHold,
                            modifier = Modifier.size(IconSize),
                        )
                    }
                }

                is CallState.Ending -> {
                    // No controls during ending transition
                }
            }
        }
    }
}
