package uz.yalla.sipphone.feature.main.toolbar

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.component.YallaSegmentedControl
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

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

    val tokens = LocalAppTokens.current

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "",
        state = rememberDialogState(size = DpSize(tokens.settingsDialogWidth, tokens.settingsDialogHeight)),
        resizable = false,
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
    ) {
        YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
            val colors = LocalYallaColors.current
            val strings = LocalStrings.current
            val t = LocalAppTokens.current

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(t.settingsCardWidth)
                        .clip(t.shapeLarge)
                        .background(colors.backgroundTertiary)
                        .border(t.dividerThickness, colors.borderDefault, t.shapeLarge)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = strings.settingsTitle,
                            fontSize = t.textXl,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textBase,
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(t.shapeXs)
                                .background(colors.backgroundSecondary)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close, null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.iconSubtle,
                            )
                        }
                    }

                    Spacer(Modifier.height(t.spacingMd))

                    // Agent info
                    if (agentInfo != null) {
                        AgentInfoCard(agentInfo)
                        Spacer(Modifier.height(t.spacingMd))
                    }

                    // Theme
                    SettingsRow(label = strings.settingsTheme) {
                        YallaSegmentedControl(
                            selectedIndex = if (isDarkTheme) 1 else 0,
                            onSelect = { index ->
                                val wantDark = index == 1
                                if (wantDark != isDarkTheme) onThemeToggle()
                            },
                            first = {
                                Icon(
                                    Icons.Filled.LightMode, null,
                                    modifier = Modifier.size(t.iconSmall),
                                    tint = if (!isDarkTheme) colors.brandPrimary else colors.textSubtle,
                                )
                            },
                            second = {
                                Icon(
                                    Icons.Filled.DarkMode, null,
                                    modifier = Modifier.size(t.iconSmall),
                                    tint = if (isDarkTheme) colors.brandPrimary else colors.textSubtle,
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(t.spacingMdSm))

                    // Locale
                    SettingsRow(label = strings.settingsLocale) {
                        YallaSegmentedControl(
                            selectedIndex = if (locale == "ru") 1 else 0,
                            onSelect = { index -> onLocaleChange(if (index == 0) "uz" else "ru") },
                            first = {
                                Text(
                                    "UZ", fontSize = t.textBase, fontWeight = FontWeight.Medium,
                                    color = if (locale == "uz") colors.brandPrimary else colors.textSubtle,
                                )
                            },
                            second = {
                                Text(
                                    "RU", fontSize = t.textBase, fontWeight = FontWeight.Medium,
                                    color = if (locale == "ru") colors.brandPrimary else colors.textSubtle,
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(t.spacingLg))

                    // Logout
                    TextButton(
                        onClick = { onDismiss(); onLogout() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(t.iconButtonSizeLarge)
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = t.shapeSmall,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colors.destructive,
                        ),
                    ) {
                        Text(strings.settingsLogout, fontSize = t.textMd, fontWeight = FontWeight.Medium)
                    }

                    Spacer(Modifier.height(t.spacingMdSm))

                    // Version
                    Text(
                        text = "Yalla SIP Phone v${SipConstants.APP_VERSION}",
                        fontSize = t.textSm,
                        color = colors.borderDefault,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentInfoCard(agentInfo: AgentInfo) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    val initials = agentInfo.name.split(" ").take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
        .joinToString("").ifEmpty { "?" }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.backgroundSecondary, tokens.shapeMedium)
            .padding(tokens.spacingMdSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(tokens.iconButtonSizeLarge).clip(CircleShape).background(colors.brandPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, fontSize = tokens.textLg, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(tokens.spacingMdSm))
        Column {
            Text(agentInfo.name, fontSize = tokens.textLg, fontWeight = FontWeight.Medium, color = colors.textBase)
            Text("ID: ${agentInfo.id}", fontSize = tokens.textBase, color = colors.textSubtle)
        }
    }
}

@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = tokens.textMd, color = colors.textBase)
        content()
    }
}
