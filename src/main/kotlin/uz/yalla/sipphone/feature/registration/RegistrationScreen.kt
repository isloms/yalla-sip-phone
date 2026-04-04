package uz.yalla.sipphone.feature.registration

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.ui.component.ConnectButton
import uz.yalla.sipphone.ui.component.ConnectionStatusCard
import uz.yalla.sipphone.ui.component.SipCredentialsForm

@Composable
fun RegistrationScreen(component: RegistrationComponent) {
    val formState by component.formState.collectAsState()
    val registrationState by component.registrationState.collectAsState()

    var formErrors by remember { mutableStateOf(FormErrors()) }

    val formEnabled = when (registrationState) {
        is RegistrationState.Idle, is RegistrationState.Failed -> true
        else -> false
    }

    val formAlpha by animateFloatAsState(
        targetValue = if (formEnabled) 1f else 0.6f, animationSpec = tween(300),
    )

    val submitAction = {
        val errors = validateForm(formState)
        formErrors = errors
        if (!errors.hasErrors) {
            component.connect(
                SipCredentials(
                    server = formState.server.trim(),
                    port = formState.port.toIntOrNull() ?: SipCredentials.DEFAULT_SIP_PORT,
                    username = formState.username.trim(),
                    password = formState.password,
                )
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "SIP Registration",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            SipCredentialsForm(
                formState = formState,
                errors = formErrors,
                enabled = formEnabled,
                onFormChange = {
                    component.updateFormState(it)
                    formErrors = FormErrors()
                },
                onSubmit = submitAction,
                modifier = Modifier.alpha(formAlpha),
            )
            Spacer(Modifier.height(24.dp))
            ConnectButton(
                state = registrationState,
                onConnect = submitAction,
                onDisconnect = {},
                onCancel = component::cancelRegistration,
            )
            ConnectionStatusCard(state = registrationState)
        }
    }
}
