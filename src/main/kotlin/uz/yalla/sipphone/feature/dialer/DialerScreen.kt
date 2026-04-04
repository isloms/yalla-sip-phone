package uz.yalla.sipphone.feature.dialer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalExtendedColors

@Composable
fun DialerScreen(component: DialerComponent) {
    val tokens = LocalAppTokens.current
    val registrationState by component.registrationState.collectAsState()
    val callState by component.callState.collectAsState()

    var phoneNumber by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    var callDuration by remember { mutableLongStateOf(0L) }

    // Call timer — UI concern
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

    when (registrationState) {
        is RegistrationState.Registered -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Spacebar &&
                            callState is CallState.Ringing &&
                            !isInputFocused
                        ) {
                            component.answerCall()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                // Status bar
                Surface(tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.spacingMd, vertical = tokens.spacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(LocalExtendedColors.current.success),
                        )
                        Spacer(Modifier.width(tokens.spacingSm))
                        Text(
                            (registrationState as RegistrationState.Registered).server,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // Main content — state-driven
                when (val state = callState) {
                    is CallState.Idle -> IdleRow(
                        phoneNumber = phoneNumber,
                        onPhoneNumberChange = { phoneNumber = it },
                        onCall = { if (phoneNumber.isNotBlank()) component.makeCall(phoneNumber) },
                        onDisconnect = component::disconnect,
                        onFocusChanged = { isInputFocused = it },
                        tokens = tokens,
                    )
                    is CallState.Ringing -> RingingRow(
                        callerNumber = state.callerNumber,
                        callerName = state.callerName,
                        onAnswer = component::answerCall,
                        onReject = component::hangupCall,
                        tokens = tokens,
                    )
                    is CallState.Active -> ActiveCallRow(
                        remoteNumber = state.remoteNumber,
                        remoteName = state.remoteName,
                        duration = callDuration,
                        isMuted = state.isMuted,
                        isOnHold = state.isOnHold,
                        onToggleMute = component::toggleMute,
                        onToggleHold = component::toggleHold,
                        onHangup = component::hangupCall,
                        tokens = tokens,
                    )
                    is CallState.Ending -> EndingRow()
                }
            }
        }
        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Connection lost \u2014 returning...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IdleRow(
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    onCall: () -> Unit,
    onDisconnect: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    tokens: uz.yalla.sipphone.ui.theme.AppTokens,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
    ) {
        // Status
        Text(
            "READY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )

        // Phone input
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = onPhoneNumberChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            placeholder = { Text("Phone number") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )

        // Call button
        Button(
            onClick = onCall,
            enabled = phoneNumber.isNotBlank(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Call")
        }

        // Disconnect
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RingingRow(
    callerNumber: String,
    callerName: String?,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    tokens: uz.yalla.sipphone.ui.theme.AppTokens,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: caller info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "INCOMING CALL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                callerNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (callerName != null) {
                Text(
                    callerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Right: answer + reject
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
            Button(
                onClick = onAnswer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalExtendedColors.current.success,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Answer")
                Text(
                    " (Space)",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalExtendedColors.current.onSuccess.copy(alpha = 0.7f),
                )
            }
            OutlinedButton(
                onClick = onReject,
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reject")
            }
        }
    }
}

@Composable
private fun ActiveCallRow(
    remoteNumber: String,
    remoteName: String?,
    duration: Long,
    isMuted: Boolean,
    isOnHold: Boolean,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    onHangup: () -> Unit,
    tokens: uz.yalla.sipphone.ui.theme.AppTokens,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: status + caller info + timer
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(
                        if (isOnHold) MaterialTheme.colorScheme.tertiary
                        else LocalExtendedColors.current.success,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isOnHold) "ON HOLD" else "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOnHold) MaterialTheme.colorScheme.tertiary
                    else LocalExtendedColors.current.success,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDuration(duration),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (isOnHold) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                remoteNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (remoteName != null) {
                Text(
                    remoteName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Right: mute + hold + divider + end
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
        ) {
            // Mute
            OutlinedButton(
                onClick = onToggleMute,
                shape = RoundedCornerShape(8.dp),
                colors = if (isMuted) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ) else ButtonDefaults.outlinedButtonColors(),
            ) {
                Icon(Icons.Filled.MicOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isMuted) "Unmute" else "Mute")
            }

            // Hold / Resume
            if (isOnHold) {
                Button(
                    onClick = onToggleHold,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Resume")
                }
            } else {
                OutlinedButton(
                    onClick = onToggleHold,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hold")
                }
            }

            // Vertical divider
            Box(
                Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // End call
            OutlinedButton(
                onClick = onHangup,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("End")
            }
        }
    }
}

@Composable
private fun EndingRow() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Ending call...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}
