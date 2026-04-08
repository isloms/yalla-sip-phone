package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
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
                    // 3 disabled buttons at 40% opacity
                    Row(
                        modifier = Modifier.alpha(0.4f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Call button
                        IconButton(
                            onClick = onCall,
                            enabled = !phoneInputEmpty,
                            modifier = Modifier
                                .size(ButtonSize)
                                .background(colors.buttonDisabled, ButtonShape),
                        ) {
                            Icon(
                                Icons.Filled.Call,
                                contentDescription = strings.buttonCall,
                                modifier = Modifier.size(IconSize),
                                tint = colors.iconDisabled,
                            )
                        }
                        // Mute button
                        IconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .size(ButtonSize)
                                .background(colors.buttonDisabled, ButtonShape),
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = strings.buttonMute,
                                modifier = Modifier.size(IconSize),
                                tint = colors.iconDisabled,
                            )
                        }
                        // Hold button
                        IconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .size(ButtonSize)
                                .background(colors.buttonDisabled, ButtonShape),
                        ) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = strings.buttonHold,
                                modifier = Modifier.size(IconSize),
                                tint = colors.iconDisabled,
                            )
                        }
                    }
                }

                is CallState.Ringing -> {
                    if (!callState.isOutbound) {
                        // Answer button — brand bg with glow shadow
                        val answerInteraction = remember { MutableInteractionSource() }
                        val answerHovered by answerInteraction.collectIsHoveredAsState()
                        IconButton(
                            onClick = onAnswer,
                            modifier = Modifier
                                .size(ButtonSize)
                                .hoverable(answerInteraction)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .shadow(
                                    elevation = if (answerHovered) 8.dp else 4.dp,
                                    shape = ButtonShape,
                                    ambientColor = colors.buttonActive.copy(alpha = 0.4f),
                                    spotColor = colors.buttonActive.copy(alpha = 0.4f),
                                )
                                .background(
                                    if (answerHovered) colors.buttonActive.copy(alpha = 0.85f)
                                    else colors.buttonActive,
                                    ButtonShape,
                                ),
                            colors = IconButtonDefaults.iconButtonColors(
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
                        val rejectInteraction = remember { MutableInteractionSource() }
                        val rejectHovered by rejectInteraction.collectIsHoveredAsState()
                        IconButton(
                            onClick = onReject,
                            modifier = Modifier
                                .size(ButtonSize)
                                .hoverable(rejectInteraction)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .background(
                                    if (rejectHovered) colors.iconRed.copy(alpha = 0.85f) else colors.iconRed,
                                    ButtonShape,
                                ),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = strings.buttonReject,
                                modifier = Modifier.size(IconSize),
                            )
                        }

                        // "Qo'ng'iroq..." label — brand tint surface
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
                        // Outbound ringing — cancel button (red bg)
                        val cancelInteraction = remember { MutableInteractionSource() }
                        val cancelHovered by cancelInteraction.collectIsHoveredAsState()
                        IconButton(
                            onClick = onHangup,
                            modifier = Modifier
                                .size(ButtonSize)
                                .hoverable(cancelInteraction)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .background(
                                    if (cancelHovered) colors.iconRed.copy(alpha = 0.85f) else colors.iconRed,
                                    ButtonShape,
                                ),
                            colors = IconButtonDefaults.iconButtonColors(
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
                    val endInteraction = remember { MutableInteractionSource() }
                    val endHovered by endInteraction.collectIsHoveredAsState()
                    IconButton(
                        onClick = onHangup,
                        modifier = Modifier
                            .size(ButtonSize)
                            .hoverable(endInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                if (endHovered) colors.iconRed.copy(alpha = 0.85f) else colors.iconRed,
                                ButtonShape,
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
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
                    val muteInteraction = remember { MutableInteractionSource() }
                    val muteHovered by muteInteraction.collectIsHoveredAsState()
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(ButtonSize)
                            .hoverable(muteInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                when {
                                    callState.isMuted -> colors.buttonActive.copy(alpha = 0.15f)
                                    muteHovered -> colors.backgroundSecondary
                                    else -> Color.Transparent
                                },
                                ButtonShape,
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
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
                    val holdInteraction = remember { MutableInteractionSource() }
                    val holdHovered by holdInteraction.collectIsHoveredAsState()
                    IconButton(
                        onClick = onToggleHold,
                        modifier = Modifier
                            .size(ButtonSize)
                            .hoverable(holdInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                when {
                                    callState.isOnHold -> colors.buttonActive.copy(alpha = 0.15f)
                                    holdHovered -> colors.backgroundSecondary
                                    else -> Color.Transparent
                                },
                                ButtonShape,
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
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
