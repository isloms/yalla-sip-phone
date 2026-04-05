package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalExtendedColors
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun CallControls(
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
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current

    // Disable M3's 48dp minimum interactive size — we control sizes explicitly via tokens
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
    ) {
        when (callState) {
            is CallState.Idle -> {
                // Mute/Hold/End icons at 38% opacity as passive indicators (M3 standard)
                Row(
                    modifier = Modifier.alpha(0.38f),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
                ) {
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(tokens.iconButtonSize),
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = Strings.BUTTON_MUTE,
                            modifier = Modifier.size(tokens.iconDefault),
                            tint = colors.textSubtle,
                        )
                    }
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(tokens.iconButtonSize),
                    ) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = Strings.BUTTON_HOLD,
                            modifier = Modifier.size(tokens.iconDefault),
                            tint = colors.textSubtle,
                        )
                    }
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(tokens.iconButtonSize),
                    ) {
                        Icon(
                            Icons.Filled.CallEnd,
                            contentDescription = Strings.BUTTON_END,
                            modifier = Modifier.size(tokens.iconDefault),
                            tint = colors.textSubtle,
                        )
                    }
                }

                Spacer(Modifier.width(tokens.spacingXs))

                // Call button keeps label — primary CTA
                Button(
                    onClick = onCall,
                    enabled = !phoneInputEmpty,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    shape = tokens.shapeSmall,
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        modifier = Modifier.size(tokens.iconDefault),
                    )
                    Spacer(Modifier.width(tokens.spacingXs))
                    Text(Strings.BUTTON_CALL)
                }
            }

            is CallState.Ringing -> {
                if (!callState.isOutbound) {
                    // Answer — green filled icon button (large for visual dominance)
                    val answerInteraction = remember { MutableInteractionSource() }
                    val answerHovered by answerInteraction.collectIsHoveredAsState()
                    IconButton(
                        onClick = onAnswer,
                        modifier = Modifier
                            .size(tokens.iconButtonSize)
                            .hoverable(answerInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                if (answerHovered) {
                                    LocalExtendedColors.current.success.copy(alpha = 0.85f)
                                } else {
                                    LocalExtendedColors.current.success
                                },
                                shape = RoundedCornerShape(8.dp),
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Phone,
                            contentDescription = Strings.BUTTON_ANSWER,
                            modifier = Modifier.size(tokens.iconDefault),
                        )
                    }

                    // Reject — red tinted icon button
                    val rejectInteraction = remember { MutableInteractionSource() }
                    val rejectHovered by rejectInteraction.collectIsHoveredAsState()
                    IconButton(
                        onClick = onReject,
                        modifier = Modifier
                            .size(tokens.iconButtonSize)
                            .hoverable(rejectInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                colors.errorIndicator.copy(alpha = if (rejectHovered) 0.25f else 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = colors.errorText,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = Strings.BUTTON_REJECT,
                            modifier = Modifier.size(tokens.iconDefault),
                        )
                    }
                } else {
                    // Outbound ringing — cancel icon button
                    val cancelInteraction = remember { MutableInteractionSource() }
                    val cancelHovered by cancelInteraction.collectIsHoveredAsState()
                    IconButton(
                        onClick = onHangup,
                        modifier = Modifier
                            .size(tokens.iconButtonSize)
                            .hoverable(cancelInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                colors.errorIndicator.copy(alpha = if (cancelHovered) 0.25f else 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = colors.errorText,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.CallEnd,
                            contentDescription = Strings.BUTTON_CANCEL,
                            modifier = Modifier.size(tokens.iconDefault),
                        )
                    }
                }
            }

            is CallState.Active -> {
                // Mute icon button
                val muteInteraction = remember { MutableInteractionSource() }
                val muteHovered by muteInteraction.collectIsHoveredAsState()
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(tokens.iconButtonSize)
                        .hoverable(muteInteraction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .then(
                            if (callState.isMuted) {
                                Modifier.background(
                                    colors.callMuted.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                            } else if (muteHovered) {
                                Modifier.background(
                                    colors.textBase.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                            } else {
                                Modifier
                            },
                        ),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (callState.isMuted) colors.callMuted else colors.textBase,
                    ),
                ) {
                    Icon(
                        if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (callState.isMuted) Strings.BUTTON_UNMUTE else Strings.BUTTON_MUTE,
                        modifier = Modifier.size(tokens.iconDefault),
                    )
                }

                // Hold icon button
                val holdInteraction = remember { MutableInteractionSource() }
                val holdHovered by holdInteraction.collectIsHoveredAsState()
                IconButton(
                    onClick = onToggleHold,
                    modifier = Modifier
                        .size(tokens.iconButtonSize)
                        .hoverable(holdInteraction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .then(
                            if (callState.isOnHold) {
                                Modifier.background(
                                    colors.brandPrimary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                            } else if (holdHovered) {
                                Modifier.background(
                                    colors.textBase.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                            } else {
                                Modifier
                            },
                        ),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (callState.isOnHold) colors.brandPrimary else colors.textBase,
                    ),
                ) {
                    Icon(
                        if (callState.isOnHold) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (callState.isOnHold) Strings.BUTTON_RESUME else Strings.BUTTON_HOLD,
                        modifier = Modifier.size(tokens.iconDefault),
                    )
                }

                // End call — red tinted icon button
                val endInteraction = remember { MutableInteractionSource() }
                val endHovered by endInteraction.collectIsHoveredAsState()
                IconButton(
                    onClick = onHangup,
                    modifier = Modifier
                        .size(tokens.iconButtonSize)
                        .hoverable(endInteraction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .background(
                            colors.errorIndicator.copy(alpha = if (endHovered) 0.25f else 0.15f),
                            shape = RoundedCornerShape(8.dp),
                        ),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = colors.errorText,
                    ),
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = Strings.BUTTON_END,
                        modifier = Modifier.size(tokens.iconDefault),
                    )
                }
            }

            is CallState.Ending -> {
                // No controls during ending transition
            }
        }
    }
    } // CompositionLocalProvider
}
