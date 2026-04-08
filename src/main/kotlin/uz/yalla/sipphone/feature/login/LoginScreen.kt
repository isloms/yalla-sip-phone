package uz.yalla.sipphone.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

private val SplashGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF7957FF), Color(0xFF562DF8), Color(0xFF3812CE)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

// Card always looks "dark" on purple gradient regardless of theme
private val CardBg = Color(0xFF1A1A20).copy(alpha = 0.88f)
private val CardTextPrimary = Color.White
private val CardTextSecondary = Color(0xFF98A2B3)
private val CardBorderDefault = Color(0xFF383843)
private val CardSurfaceMuted = Color(0xFF21222B)

@Composable
fun LoginScreen(component: LoginComponent) {
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current
    val colors = LocalYallaColors.current
    val loginState by component.loginState.collectAsState()

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    val isLoading = loginState is LoginState.Loading || loginState is LoginState.Authenticated
    val errorState = loginState as? LoginState.Error

    Box(
        modifier = Modifier.fillMaxSize().background(SplashGradient),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(tokens.shapeXl)
                .background(CardBg)
                .padding(horizontal = 40.dp, vertical = tokens.spacingXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(tokens.shapeMedium).background(colors.brandPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Phone, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }

            Spacer(Modifier.height(tokens.spacingMd))

            Text(
                text = strings.loginTitle,
                style = TextStyle(fontSize = tokens.textTitle, fontWeight = FontWeight.Bold, color = CardTextPrimary),
            )

            Spacer(Modifier.height(tokens.spacingSm))

            Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                when {
                    errorState?.type == LoginErrorType.WRONG_PASSWORD -> Text(
                        strings.errorWrongPassword, style = MaterialTheme.typography.bodySmall, color = colors.destructive,
                    )
                    errorState?.type == LoginErrorType.NETWORK -> Text(
                        strings.errorNetworkFailed, style = MaterialTheme.typography.bodySmall, color = colors.statusWarning,
                    )
                    else -> Text(
                        strings.loginSubtitle, style = MaterialTheme.typography.bodySmall, color = CardTextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            val fieldBorderColor = if (errorState?.type == LoginErrorType.WRONG_PASSWORD) colors.destructive else CardBorderDefault

            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = !isLoading,
                textStyle = TextStyle(color = CardTextPrimary, fontSize = tokens.textLg),
                cursorBrush = SolidColor(colors.brandPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!isLoading && password.isNotEmpty()) component.login(password)
                }),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tokens.fieldHeightLg)
                            .clip(tokens.shapeMedium)
                            .background(CardSurfaceMuted)
                            .border(tokens.dividerThickness, fieldBorderColor, tokens.shapeMedium)
                            .padding(horizontal = tokens.spacingMdSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Lock, null, tint = CardTextSecondary, modifier = Modifier.size(tokens.iconDefault))
                        Spacer(Modifier.width(tokens.spacingSm))
                        Box(modifier = Modifier.weight(1f)) {
                            if (password.isEmpty()) {
                                Text(strings.loginPasswordPlaceholder, style = TextStyle(fontSize = tokens.textLg, color = CardTextSecondary))
                            }
                            innerTextField()
                        }
                        IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, tint = CardTextSecondary, modifier = Modifier.size(tokens.iconDefault),
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.height(tokens.spacingMd))

            val buttonText = when {
                isLoading -> strings.loginConnecting
                errorState != null -> strings.loginRetry
                else -> strings.loginButton
            }

            Button(
                onClick = { component.login(password) },
                enabled = !isLoading && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(tokens.fieldHeightLg).pointerHoverIcon(PointerIcon.Hand),
                shape = tokens.shapeMedium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brandPrimary,
                    disabledContainerColor = CardSurfaceMuted,
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(tokens.iconDefault), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(tokens.spacingSm))
                }
                Text(buttonText, color = Color.White, fontSize = tokens.textLg)
            }

            Spacer(Modifier.height(tokens.spacingMdSm))

            TextButton(onClick = { showManualDialog = true }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Text(strings.loginManualConnection, color = CardTextSecondary, fontSize = tokens.textMd)
            }

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

            Spacer(Modifier.height(tokens.spacingSm))

            Text("v${SipConstants.APP_VERSION}", color = CardBorderDefault, style = MaterialTheme.typography.bodySmall)
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
    val strings = LocalStrings.current
    val colors = LocalYallaColors.current

    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5060") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var dispatcherUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.loginManualConnection) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text(strings.labelServer) },
                    placeholder = {
                        Text(strings.placeholderServer, style = MaterialTheme.typography.bodySmall,
                            color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                    },
                    singleLine = true, enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(), shape = tokens.shapeMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(strings.labelPort) },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.width(100.dp), shape = tokens.shapeMedium,
                    )
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text(strings.labelUsername) },
                        placeholder = {
                            Text(strings.placeholderUsername, style = MaterialTheme.typography.bodySmall,
                                color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                        },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.weight(1f), shape = tokens.shapeMedium,
                    )
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(strings.labelPassword) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(), shape = tokens.shapeMedium,
                )
                OutlinedTextField(
                    value = dispatcherUrl, onValueChange = { dispatcherUrl = it },
                    label = { Text(strings.placeholderDispatcherUrl) },
                    placeholder = {
                        Text(strings.placeholderDispatcherUrl, style = MaterialTheme.typography.bodySmall,
                            color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                    },
                    singleLine = true, enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(), shape = tokens.shapeMedium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(server, port.toIntOrNull() ?: 5060, username, password, dispatcherUrl) },
                enabled = !isLoading && server.isNotEmpty() && username.isNotEmpty(),
                shape = tokens.shapeMedium,
            ) { Text(strings.buttonConnect) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.buttonCancel) } },
    )
}
