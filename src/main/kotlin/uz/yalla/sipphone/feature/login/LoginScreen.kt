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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

private val SplashGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF7957FF), Color(0xFF562DF8), Color(0xFF3812CE)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

private val CardShape = RoundedCornerShape(16.dp)
private val FieldShape = RoundedCornerShape(10.dp)

// Login card always uses dark colors regardless of theme — sits on purple gradient
private val CardBg = Color(0xFF1A1A20).copy(alpha = 0.88f)
private val CardFieldBg = Color(0xFF21222B)
private val CardBorder = Color(0xFF383843)
private val CardTextBase = Color.White
private val CardTextSubtle = Color(0xFF747C8B)
private val CardIconSubtle = Color(0xFF98A2B3)
private val CardBrand = Color(0xFF562DF8)
private val CardBrandDisabled = Color(0xFF2C2D34)
private val CardError = Color(0xFFF42500)
private val CardPinkSun = Color(0xFFFF234B)

@Composable
fun LoginScreen(component: LoginComponent) {
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current
    val strings = LocalStrings.current
    val loginState by component.loginState.collectAsState()

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    val isLoading = loginState is LoginState.Loading || loginState is LoginState.Authenticated
    val errorState = loginState as? LoginState.Error

    // Gradient background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashGradient),
        contentAlignment = Alignment.Center,
    ) {
        // Semi-transparent dark card (always dark, sits on purple gradient)
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(CardShape)
                .background(CardBg)
                .padding(horizontal = 40.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo: 56dp rounded square with brand bg, phone icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBrand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = strings.loginTitle,
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle slot: 20dp fixed height
            Box(
                modifier = Modifier.height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    errorState?.type == LoginErrorType.WRONG_PASSWORD -> {
                        Text(
                            text = strings.errorWrongPassword,
                            style = MaterialTheme.typography.bodySmall,
                            color = CardError,
                        )
                    }
                    errorState?.type == LoginErrorType.NETWORK -> {
                        Text(
                            text = strings.errorNetworkFailed,
                            style = MaterialTheme.typography.bodySmall,
                            color = CardPinkSun,
                        )
                    }
                    else -> {
                        Text(
                            text = strings.loginSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = CardIconSubtle,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Password field: custom with backgroundSecondary bg, borderDisabled border
            val fieldBorderColor = if (errorState?.type == LoginErrorType.WRONG_PASSWORD) {
                CardError
            } else {
                CardBorder
            }

            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = !isLoading,
                textStyle = TextStyle(
                    color = CardTextBase,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(CardBrand),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isLoading && password.isNotEmpty()) component.login(password)
                    },
                ),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(FieldShape)
                            .background(CardFieldBg)
                            .border(1.dp, fieldBorderColor, FieldShape)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = CardIconSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (password.isEmpty()) {
                                Text(
                                    text = strings.loginPasswordPlaceholder,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        color = CardTextSubtle,
                                    ),
                                )
                            }
                            innerTextField()
                        }
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = null,
                                tint = CardIconSubtle,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Login button
            val buttonText = when {
                isLoading -> strings.loginConnecting
                errorState != null -> strings.loginRetry
                else -> strings.loginButton
            }

            Button(
                onClick = { component.login(password) },
                enabled = !isLoading && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                shape = FieldShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBrand,
                    disabledContainerColor = CardBrandDisabled,
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = buttonText,
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Manual connection link
            TextButton(
                onClick = { showManualDialog = true },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = strings.loginManualConnection,
                    color = CardTextSubtle,
                    fontSize = 13.sp,
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

            Spacer(modifier = Modifier.height(8.dp))

            // Version
            Text(
                text = "v${SipConstants.APP_VERSION}",
                color = CardBorder,
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
    val strings = LocalStrings.current

    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5060") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var dispatcherUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.loginManualConnection) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(tokens.spacingSm),
            ) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(strings.labelServer) },
                    placeholder = {
                        Text(
                            strings.placeholderServer,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalYallaColors.current.textSubtle.copy(alpha = 0.6f),
                        )
                    },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(strings.labelPort) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.width(100.dp),
                        shape = tokens.shapeMedium,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(strings.labelUsername) },
                        placeholder = {
                            Text(
                                strings.placeholderUsername,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalYallaColors.current.textSubtle.copy(alpha = 0.6f),
                            )
                        },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        shape = tokens.shapeMedium,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.labelPassword) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
                OutlinedTextField(
                    value = dispatcherUrl,
                    onValueChange = { dispatcherUrl = it },
                    label = { Text(strings.placeholderDispatcherUrl) },
                    placeholder = {
                        Text(
                            strings.placeholderDispatcherUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalYallaColors.current.textSubtle.copy(alpha = 0.6f),
                        )
                    },
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
                Text(strings.buttonConnect)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.buttonCancel)
            }
        },
    )
}
