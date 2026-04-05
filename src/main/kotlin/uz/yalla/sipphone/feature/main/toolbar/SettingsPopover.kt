package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

/**
 * Settings button that opens an AlertDialog instead of a Popup.
 * AlertDialog creates a real OS-level dialog window, which avoids z-order
 * issues with heavyweight SwingPanel (JCEF browser).
 */
@Composable
fun SettingsPopover(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val colors = LocalYallaColors.current
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(32.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = Strings.SETTINGS_TITLE,
                modifier = Modifier.size(20.dp),
                tint = colors.textSubtle,
            )
        }

        if (expanded) {
            DialogWindow(
                onCloseRequest = { expanded = false },
                title = Strings.SETTINGS_TITLE,
                state = rememberDialogState(size = DpSize(320.dp, 220.dp)),
                resizable = false,
                alwaysOnTop = true,
            ) {
                YallaSipPhoneTheme(isDark = isDarkTheme) {
                    val dlgColors = LocalYallaColors.current
                    val dlgTokens = LocalAppTokens.current

                    Surface(color = dlgColors.backgroundBase) {
                        Column(modifier = Modifier.padding(dlgTokens.spacingLg)) {
                            // Theme toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onThemeToggle() }
                                    .padding(vertical = dlgTokens.spacingSm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = Strings.SETTINGS_THEME,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = dlgColors.textBase,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(checked = isDarkTheme, onCheckedChange = null)
                            }

                            HorizontalDivider()

                            // Version
                            Text(
                                text = SipConstants.APP_VERSION_DISPLAY,
                                style = MaterialTheme.typography.labelSmall,
                                color = dlgColors.textSubtle,
                                modifier = Modifier.padding(vertical = dlgTokens.spacingSm),
                            )

                            Spacer(Modifier.weight(1f))

                            // Logout
                            Button(
                                onClick = {
                                    expanded = false
                                    onLogout()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = dlgColors.errorIndicator,
                                ),
                            ) {
                                Text(Strings.SETTINGS_LOGOUT)
                            }
                        }
                    }
                }
            }
        }
    }
}
