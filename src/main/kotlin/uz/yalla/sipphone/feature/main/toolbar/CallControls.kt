package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
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

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacingXs),
    ) {
        when (callState) {
            is CallState.Idle -> {
                // Mute/Hold/End icons at 30% opacity as passive indicators
                Row(
                    modifier = Modifier.alpha(0.3f),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacingXs),
                ) {
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = Strings.BUTTON_MUTE,
                            modifier = Modifier.size(tokens.iconSmall),
                            tint = colors.textSubtle,
                        )
                    }
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = Strings.BUTTON_HOLD,
                            modifier = Modifier.size(tokens.iconSmall),
                            tint = colors.textSubtle,
                        )
                    }
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.CallEnd,
                            contentDescription = Strings.BUTTON_END,
                            modifier = Modifier.size(tokens.iconSmall),
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
                        modifier = Modifier.size(tokens.iconSmall),
                    )
                    Spacer(Modifier.width(tokens.spacingXs))
                    Text(Strings.BUTTON_CALL)
                }
            }

            is CallState.Ringing -> {
                if (!callState.isOutbound) {
                    // Answer — green filled icon button
                    IconButton(
                        onClick = onAnswer,
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                colors.callReady,
                                shape = RoundedCornerShape(8.dp),
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Phone,
                            contentDescription = Strings.BUTTON_ANSWER,
                            modifier = Modifier.size(tokens.iconSmall),
                        )
                    }

                    // Reject — red tinted icon button
                    IconButton(
                        onClick = onReject,
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                colors.errorIndicator.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = colors.errorText,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = Strings.BUTTON_REJECT,
                            modifier = Modifier.size(tokens.iconSmall),
                        )
                    }
                } else {
                    // Outbound ringing — cancel icon button
                    IconButton(
                        onClick = onHangup,
                        modifier = Modifier
                            .size(32.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(
                                colors.errorIndicator.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = colors.errorText,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.CallEnd,
                            contentDescription = Strings.BUTTON_CANCEL,
                            modifier = Modifier.size(tokens.iconSmall),
                        )
                    }
                }
            }

            is CallState.Active -> {
                // Mute icon button
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(32.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .then(
                            if (callState.isMuted) {
                                Modifier.background(
                                    colors.callMuted.copy(alpha = 0.15f),
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
                        modifier = Modifier.size(tokens.iconSmall),
                    )
                }

                // Hold icon button
                IconButton(
                    onClick = onToggleHold,
                    modifier = Modifier
                        .size(32.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .then(
                            if (callState.isOnHold) {
                                Modifier.background(
                                    colors.brandPrimary.copy(alpha = 0.15f),
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
                        modifier = Modifier.size(tokens.iconSmall),
                    )
                }

                // End call — red tinted icon button
                IconButton(
                    onClick = onHangup,
                    modifier = Modifier
                        .size(32.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .background(
                            colors.errorIndicator.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                        ),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = colors.errorText,
                    ),
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = Strings.BUTTON_END,
                        modifier = Modifier.size(tokens.iconSmall),
                    )
                }
            }

            is CallState.Ending -> {
                // No controls during ending transition
            }
        }
    }
}
