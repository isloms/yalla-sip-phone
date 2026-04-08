package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

/**
 * Settings dialog — OS-level DialogWindow (renders above JCEF).
 *
 * Content: agent info, theme toggle, locale toggle, logout, version.
 */
@Composable
fun SettingsDialog(
    visible: Boolean,
    isDarkTheme: Boolean,
    locale: String,
    agentInfo: AgentInfo?,
    onThemeToggle: () -> Unit,
    onLocaleChange: (String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "",
        state = rememberDialogState(size = DpSize(340.dp, 400.dp)),
        resizable = false,
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
    ) {
        YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
            val colors = LocalYallaColors.current
            val strings = LocalStrings.current

            // Transparent background — card is the visible content
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // Card
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.backgroundTertiary)
                        .border(1.dp, colors.borderDisabled, RoundedCornerShape(14.dp))
                        .padding(20.dp),
                ) {
                    // Header: title + close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = strings.settingsTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textBase,
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(28.dp)
                                .background(colors.backgroundSecondary, CircleShape)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.textSubtle,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Agent info card
                    if (agentInfo != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.backgroundSecondary, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Initials avatar
                            val initials = agentInfo.name
                                .split(" ")
                                .take(2)
                                .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
                                .joinToString("")
                                .ifEmpty { "?" }

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(colors.buttonActive),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = initials,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = agentInfo.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textBase,
                                )
                                Text(
                                    text = "ID: ${agentInfo.id}",
                                    fontSize = 12.sp,
                                    color = colors.textSubtle,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }

                    // Theme row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = strings.settingsTheme,
                            fontSize = 13.sp,
                            color = colors.textBase,
                        )
                        // Segmented control: light / dark
                        Row(
                            modifier = Modifier
                                .background(colors.backgroundSecondary, RoundedCornerShape(8.dp))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            SegmentButton(
                                selected = !isDarkTheme,
                                onClick = { if (isDarkTheme) onThemeToggle() },
                            ) {
                                Icon(
                                    Icons.Filled.LightMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (!isDarkTheme) colors.buttonActive else colors.textSubtle,
                                )
                            }
                            SegmentButton(
                                selected = isDarkTheme,
                                onClick = { if (!isDarkTheme) onThemeToggle() },
                            ) {
                                Icon(
                                    Icons.Filled.DarkMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isDarkTheme) colors.buttonActive else colors.textSubtle,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Locale row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = strings.settingsLocale,
                            fontSize = 13.sp,
                            color = colors.textBase,
                        )
                        Row(
                            modifier = Modifier
                                .background(colors.backgroundSecondary, RoundedCornerShape(8.dp))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            SegmentButton(
                                selected = locale == "uz",
                                onClick = { onLocaleChange("uz") },
                            ) {
                                Text(
                                    text = "UZ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (locale == "uz") colors.buttonActive else colors.textSubtle,
                                )
                            }
                            SegmentButton(
                                selected = locale == "ru",
                                onClick = { onLocaleChange("ru") },
                            ) {
                                Text(
                                    text = "RU",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (locale == "ru") colors.buttonActive else colors.textSubtle,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Logout button
                    Button(
                        onClick = {
                            onDismiss()
                            onLogout()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.iconRed.copy(alpha = 0.12f),
                            contentColor = colors.iconRed,
                        ),
                    ) {
                        Text(
                            text = strings.settingsLogout,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Version
                    Text(
                        text = "Yalla SIP Phone v${SipConstants.APP_VERSION}",
                        fontSize = 11.sp,
                        color = colors.borderDisabled,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (selected) {
                    Modifier.background(colors.buttonActive.copy(alpha = 0.15f))
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
