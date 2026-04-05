package uz.yalla.sipphone.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun LoginScreen(component: LoginComponent) {
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current
    val loginState by component.loginState.collectAsState()

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    val isLoading = loginState is LoginState.Loading || loginState is LoginState.Authenticated
    val errorMessage = (loginState as? LoginState.Error)?.message

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.backgroundBase,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(tokens.spacingLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Title
            Text(
                text = Strings.LOGIN_TITLE,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.brandPrimaryText,
            )

            Spacer(modifier = Modifier.height(tokens.spacingXl))

            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(Strings.LOGIN_PASSWORD_LABEL) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(tokens.iconMedium),
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(tokens.iconMedium),
                        )
                    }
                },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isLoading && password.isNotEmpty()) component.login(password)
                    },
                ),
                singleLine = true,
                enabled = !isLoading,
                isError = errorMessage != null,
                modifier = Modifier.fillMaxWidth(),
                shape = tokens.shapeMedium,
            )

            // Error message
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = colors.errorText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = tokens.spacingSm),
                )
            }

            Spacer(modifier = Modifier.height(tokens.spacingLg))

            // Login button
            Button(
                onClick = { component.login(password) },
                enabled = !isLoading && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                shape = tokens.shapeMedium,
                colors = ButtonDefaults.buttonColors(containerColor = colors.brandPrimary),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(tokens.spacingSm))
                    Text(Strings.BUTTON_CONNECTING)
                } else {
                    Text(Strings.LOGIN_BUTTON)
                }
            }

            Spacer(modifier = Modifier.height(tokens.spacingMd))

            // Manual connection link
            TextButton(
                onClick = { showManualDialog = true },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = Strings.LOGIN_MANUAL_CONNECTION,
                    color = colors.textSubtle,
                )
            }

            // Manual connection dialog
            if (showManualDialog) {
                ManualConnectionDialog(
                    isLoading = isLoading,
                    onConnect = { server, port, username, pwd, dispatcher ->
                        showManualDialog = false
                        component.manualConnect(server, port, username, pwd, dispatcher)
                    },
                    onDismiss = { showManualDialog = false },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Version
            Text(
                text = "v${SipConstants.APP_VERSION}",
                color = colors.textSubtle,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ManualConnectionDialog(
    isLoading: Boolean,
    onConnect: (server: String, port: Int, username: String, password: String, dispatcherUrl: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAppTokens.current

    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5060") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var dispatcherUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.LOGIN_MANUAL_CONNECTION) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(tokens.spacingSm),
            ) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(Strings.LABEL_SERVER) },
                    placeholder = { Text(Strings.PLACEHOLDER_SERVER, style = MaterialTheme.typography.bodySmall, color = LocalYallaColors.current.textSubtle.copy(alpha = 0.6f)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(Strings.LABEL_PORT) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.width(100.dp),
                        shape = tokens.shapeMedium,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(Strings.LABEL_USERNAME) },
                        placeholder = { Text(Strings.PLACEHOLDER_USERNAME, style = MaterialTheme.typography.bodySmall, color = LocalYallaColors.current.textSubtle.copy(alpha = 0.6f)) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        shape = tokens.shapeMedium,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(Strings.LABEL_PASSWORD) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
                OutlinedTextField(
                    value = dispatcherUrl,
                    onValueChange = { dispatcherUrl = it },
                    label = { Text("Dispatcher URL") },
                    placeholder = { Text("http://192.168.0.234:5173", style = MaterialTheme.typography.bodySmall, color = LocalYallaColors.current.textSubtle.copy(alpha = 0.6f)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(server, port.toIntOrNull() ?: 5060, username, password, dispatcherUrl) },
                enabled = !isLoading && server.isNotEmpty() && username.isNotEmpty(),
                shape = tokens.shapeMedium,
            ) {
                Text(Strings.BUTTON_CONNECT)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.BUTTON_CANCEL)
            }
        },
    )
}
