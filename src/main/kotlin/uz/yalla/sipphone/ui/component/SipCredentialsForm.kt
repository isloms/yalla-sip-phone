package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.feature.registration.FormErrors
import uz.yalla.sipphone.feature.registration.FormState

@Composable
fun SipCredentialsForm(
    formState: FormState,
    errors: FormErrors,
    enabled: Boolean,
    onFormChange: (FormState) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val submitOnEnter = Modifier.onKeyEvent { event ->
        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && enabled) {
            onSubmit()
            true
        } else {
            false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = formState.server,
            onValueChange = { onFormChange(formState.copy(server = it)) },
            label = { Text("SIP Server") },
            placeholder = { Text("192.168.0.22") },
            leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
            isError = errors.server != null,
            supportingText = errors.server?.let { { Text(it) } },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(submitOnEnter),
        )

        OutlinedTextField(
            value = formState.port,
            onValueChange = { newValue ->
                if (newValue.all { it.isDigit() } && newValue.length <= 5) {
                    onFormChange(formState.copy(port = newValue))
                }
            },
            label = { Text("Port") },
            leadingIcon = { Icon(Icons.Filled.Tag, contentDescription = null) },
            isError = errors.port != null,
            supportingText = errors.port?.let { { Text(it) } },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.widthIn(min = 120.dp, max = 160.dp).then(submitOnEnter),
        )

        OutlinedTextField(
            value = formState.username,
            onValueChange = { onFormChange(formState.copy(username = it)) },
            label = { Text("Username") },
            placeholder = { Text("102") },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            isError = errors.username != null,
            supportingText = errors.username?.let { { Text(it) } },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(submitOnEnter),
        )

        var passwordVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = formState.password,
            onValueChange = { onFormChange(formState.copy(password = it)) },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = errors.password != null,
            supportingText = errors.password?.let { { Text(it) } },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(submitOnEnter),
        )
    }
}
