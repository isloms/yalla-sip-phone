package uz.yalla.sipphone.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
                shape = MaterialTheme.shapes.medium,
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
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
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

            Spacer(modifier = Modifier.weight(1f))

            // Version
            Text(
                text = "v1.0.0",
                color = colors.textSubtle,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
