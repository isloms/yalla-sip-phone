package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.component.YallaSegmentedControl
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Settings side panel — Popup-based, renders above JCEF on the right side.
 * Uses Popup (becomes OS-level window via compose.layers.type=WINDOW).
 * Slide animation via AnimatedVisibility + MutableTransitionState.
 */
@Composable
fun SettingsPanel(
    visible: Boolean,
    isDarkTheme: Boolean,
    locale: String,
    agentInfo: AgentInfo?,
    onThemeToggle: () -> Unit,
    onLocaleChange: (String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animState = remember { MutableTransitionState(false) }
    animState.targetState = visible

    // Keep Popup alive during exit animation
    val showPopup = visible || animState.currentState || !animState.isIdle
    if (!showPopup) return

    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = animState,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
        ) {
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(colors.backgroundSecondary)
                    .padding(tokens.spacingMd)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.settingsTitle,
                        fontSize = tokens.textXl,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textBase,
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(tokens.shapeXs)
                            .background(colors.backgroundTertiary)
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

                Spacer(Modifier.height(tokens.spacingMd))

                if (agentInfo != null) {
                    AgentInfoCard(agentInfo)
                    Spacer(Modifier.height(tokens.spacingMd))
                }

                HorizontalDivider(color = colors.borderDefault)
                Spacer(Modifier.height(tokens.spacingMd))

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
                                modifier = Modifier.size(tokens.iconSmall),
                                tint = if (!isDarkTheme) colors.brandPrimary else colors.textSubtle,
                            )
                        },
                        second = {
                            Icon(
                                Icons.Filled.DarkMode, null,
                                modifier = Modifier.size(tokens.iconSmall),
                                tint = if (isDarkTheme) colors.brandPrimary else colors.textSubtle,
                            )
                        },
                    )
                }

                Spacer(Modifier.height(tokens.spacingMdSm))

                SettingsRow(label = strings.settingsLocale) {
                    YallaSegmentedControl(
                        selectedIndex = if (locale == "ru") 1 else 0,
                        onSelect = { index -> onLocaleChange(if (index == 0) "uz" else "ru") },
                        first = {
                            Text(
                                "UZ", fontSize = tokens.textBase, fontWeight = FontWeight.Medium,
                                color = if (locale == "uz") colors.brandPrimary else colors.textSubtle,
                            )
                        },
                        second = {
                            Text(
                                "RU", fontSize = tokens.textBase, fontWeight = FontWeight.Medium,
                                color = if (locale == "ru") colors.brandPrimary else colors.textSubtle,
                            )
                        },
                    )
                }

                Spacer(Modifier.weight(1f))

                HorizontalDivider(color = colors.borderDefault)
                Spacer(Modifier.height(tokens.spacingSm))

                TextButton(
                    onClick = { onDismiss(); onLogout() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerHoverIcon(PointerIcon.Hand),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.destructive),
                ) {
                    Text(strings.settingsLogout, fontSize = tokens.textMd, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(tokens.spacingXs))

                Text(
                    text = "v${SipConstants.APP_VERSION}",
                    fontSize = tokens.textXs,
                    color = colors.textSubtle,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
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
            .background(colors.backgroundTertiary, tokens.shapeMedium)
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
