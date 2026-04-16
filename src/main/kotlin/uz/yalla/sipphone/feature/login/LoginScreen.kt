package uz.yalla.sipphone.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
                    onConnect = { accounts, dispatcherUrl, backendUrl, pin ->
                        showManualDialog = false
                        component.manualConnect(accounts, dispatcherUrl, backendUrl, pin)
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
    onConnect: (accounts: List<ManualAccountEntry>, dispatcherUrl: String, backendUrl: String, pin: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current
    val colors = LocalYallaColors.current

    var accounts by remember { mutableStateOf(listOf<ManualAccountEntry>()) }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5060") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var dispatcherUrl by remember { mutableStateOf("") }
    var backendUrl by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var duplicateWarning by remember { mutableStateOf(false) }

    val canAdd = server.isNotBlank() && username.isNotBlank() && !isLoading
    val canConnect = accounts.isNotEmpty() && !isLoading

    fun addAccount() {
        val entry = ManualAccountEntry(server, port.toIntOrNull() ?: 5060, username, password)
        if (accounts.any { it.displayKey == entry.displayKey }) {
            duplicateWarning = true
            return
        }
        accounts = accounts + entry
        username = ""
        password = ""
        duplicateWarning = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.loginManualConnection) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // PIN — backend auth identity, top priority
                var pinVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it },
                    label = { Text(strings.labelPin) },
                    placeholder = {
                        Text(strings.placeholderPin, style = MaterialTheme.typography.bodySmall,
                            color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                    },
                    visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pinVisible = !pinVisible }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                if (pinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null, modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    singleLine = true, enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(), shape = tokens.shapeMedium,
                )

                Spacer(Modifier.height(4.dp))

                // Account list
                if (accounts.isEmpty()) {
                    Text(
                        strings.manualNoAccounts,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSubtle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(accounts, key = { _, entry -> entry.displayKey }) { index, entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(entry.displayKey, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        accounts = accounts.filterIndexed { i, _ -> i != index }
                                        duplicateWarning = false
                                    },
                                    modifier = Modifier.size(20.dp),
                                    enabled = !isLoading,
                                ) {
                                    Text("\u00D7", style = MaterialTheme.typography.bodySmall, color = colors.textSubtle)
                                }
                            }
                        }
                    }
                }

                // SIP form — Server + Port row
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
                    OutlinedTextField(
                        value = server, onValueChange = { server = it; duplicateWarning = false },
                        label = { Text(strings.labelServer) },
                        placeholder = {
                            Text(strings.placeholderServer, style = MaterialTheme.typography.bodySmall,
                                color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                        },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.weight(1f), shape = tokens.shapeMedium,
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(strings.labelPort) },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.width(90.dp), shape = tokens.shapeMedium,
                    )
                }

                // Username + SIP Password row
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it; duplicateWarning = false },
                        label = { Text(strings.labelUsername) },
                        placeholder = {
                            Text(strings.placeholderUsername, style = MaterialTheme.typography.bodySmall,
                                color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                        },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.weight(1f), shape = tokens.shapeMedium,
                    )
                    var passwordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text(strings.labelPassword) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null, modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.weight(1f), shape = tokens.shapeMedium,
                    )
                }

                if (duplicateWarning) {
                    Text(strings.manualDuplicateAccount, style = MaterialTheme.typography.bodySmall, color = colors.statusWarning)
                }

                TextButton(
                    onClick = { addAccount() },
                    enabled = canAdd,
                    modifier = Modifier.align(Alignment.End),
                ) { Text(strings.manualAddAccount) }

                // Advanced — URLs collapsed by default (have sensible defaults in AppSettings)
                var showAdvanced by remember { mutableStateOf(false) }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(strings.manualAdvancedSettings, style = MaterialTheme.typography.bodySmall, color = colors.textSubtle)
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = dispatcherUrl, onValueChange = { dispatcherUrl = it },
                        label = { Text(strings.labelDispatcherUrl) },
                        placeholder = {
                            Text(strings.placeholderDispatcherUrl, style = MaterialTheme.typography.bodySmall,
                                color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                        },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(), shape = tokens.shapeMedium,
                    )
                    OutlinedTextField(
                        value = backendUrl, onValueChange = { backendUrl = it },
                        label = { Text(strings.labelBackendUrl) },
                        placeholder = {
                            Text(strings.placeholderBackendUrl, style = MaterialTheme.typography.bodySmall,
                                color = colors.textSubtle.copy(alpha = tokens.alphaDisabled))
                        },
                        singleLine = true, enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(), shape = tokens.shapeMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(accounts, dispatcherUrl, backendUrl, pin) },
                enabled = canConnect,
                shape = tokens.shapeMedium,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(tokens.iconDefault), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(tokens.spacingSm))
                }
                Text(strings.manualConnectAll)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.buttonCancel) } },
    )
}
