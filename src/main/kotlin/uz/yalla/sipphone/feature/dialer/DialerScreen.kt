package uz.yalla.sipphone.feature.dialer

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalExtendedColors

@Composable
fun DialerScreen(component: DialerComponent) {
    val tokens = LocalAppTokens.current

    when (val state = component.registrationState.collectAsState().value) {
        is RegistrationState.Registered -> {
            Column(Modifier.fillMaxSize()) {
                // TOP: Status bar
                Surface(tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Connected",
                            tint = LocalExtendedColors.current.success,
                        )
                        Spacer(Modifier.width(tokens.spacingSm))
                        Text("Registered - ${state.server}", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // CENTER: Future dial pad area
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Dialpad,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(tokens.spacingSm))
                        Text(
                            "Dial pad - Phase 3",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // BOTTOM: Actions
                FilledTonalButton(
                    onClick = component::disconnect,
                    modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = null)
                    Spacer(Modifier.width(tokens.spacingSm))
                    Text("Disconnect")
                }
            }
        }
        else -> { /* loading/error handled by navigation */ }
    }
}
