package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

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
            Popup(
                onDismissRequest = { expanded = false },
                popupPositionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(
                        x = anchorBounds.right - popupContentSize.width,
                        y = anchorBounds.bottom,
                    )
                },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = tokens.shapeMedium,
                    shadowElevation = tokens.elevationMedium,
                    color = colors.backgroundBase,
                ) {
                    Column(modifier = Modifier.widthIn(min = 200.dp)) {
                        // Theme toggle row
                        Row(
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onThemeToggle() }
                                .padding(
                                    horizontal = tokens.spacingMd,
                                    vertical = tokens.spacingSm,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
                        ) {
                            Text(
                                text = Strings.SETTINGS_THEME,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textBase,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (isDarkTheme) Strings.SETTINGS_THEME_DARK
                                else Strings.SETTINGS_THEME_LIGHT,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSubtle,
                            )
                            Switch(
                                checked = isDarkTheme,
                                onCheckedChange = null,
                            )
                        }

                        HorizontalDivider()

                        // Logout
                        Text(
                            text = Strings.SETTINGS_LOGOUT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.errorText,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable {
                                    expanded = false
                                    onLogout()
                                }
                                .padding(
                                    horizontal = tokens.spacingMd,
                                    vertical = tokens.spacingSm,
                                ),
                        )

                        HorizontalDivider()

                        // Version
                        Box(
                            modifier = Modifier.padding(
                                horizontal = tokens.spacingMd,
                                vertical = tokens.spacingSm,
                            ),
                        ) {
                            Text(
                                text = "v1.0.0",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSubtle,
                            )
                        }
                    }
                }
            }
        }
    }
}
