package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.SipAccount
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.ui.component.YallaTooltip
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaColors
import uz.yalla.sipphone.ui.theme.AppTokens

@Composable
fun SipChipRow(
    accounts: List<SipAccount>,
    activeCallAccountId: String?,
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        accounts.forEach { account ->
            SipChip(
                account = account,
                isActiveCall = activeCallAccountId == account.id,
                isMutedByCall = activeCallAccountId != null && activeCallAccountId != account.id,
                onClick = { onChipClick(account.id) },
            )
        }
    }
}

@Composable
private fun SipChip(
    account: SipAccount,
    isActiveCall: Boolean,
    isMutedByCall: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    val isClickable = account.state !is SipAccountState.Reconnecting
    val chipStyle = resolveChipStyle(colors, tokens, account.state, isActiveCall, isMutedByCall)

    val statusText = when (account.state) {
        is SipAccountState.Connected -> strings.sipConnected
        is SipAccountState.Reconnecting -> strings.sipReconnecting
        is SipAccountState.Disconnected -> strings.sipDisconnected
    }
    val statusColor = when (account.state) {
        is SipAccountState.Connected -> colors.brandPrimary
        is SipAccountState.Reconnecting -> colors.statusWarning
        is SipAccountState.Disconnected -> colors.destructive
    }

    YallaTooltip(
        tooltip = {
            Text(account.name, fontSize = tokens.textMd, fontWeight = FontWeight.SemiBold, color = colors.textBase)
            Text(account.credentials.username, fontSize = tokens.textSm, color = colors.textSubtle)
            Text("${account.credentials.server}:${account.credentials.port}", fontSize = tokens.textSm, color = colors.textSubtle)
            if (account.credentials.transport != "UDP") {
                Text(account.credentials.transport, fontSize = tokens.textSm, color = colors.textSubtle)
            }
            Text(statusText, fontSize = tokens.textSm, fontWeight = FontWeight.Medium, color = statusColor)
            if (account.state is SipAccountState.Disconnected) {
                Text(strings.sipReconnectHint, fontSize = tokens.textXs, color = colors.destructive)
            }
        },
    ) {
        Row(
            modifier = Modifier
                .height(tokens.chipHeight)
                .clip(tokens.shapeXs)
                .background(chipStyle.bgColor, tokens.shapeXs)
                .border(tokens.dividerThickness, chipStyle.borderColor, tokens.shapeXs)
                .then(
                    if (isClickable) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
                    else Modifier,
                )
                .padding(horizontal = tokens.spacingMdSm - 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val prefix = if (isActiveCall) "\u25CF " else ""
            Text(
                text = "$prefix${account.name}",
                fontSize = tokens.textBase,
                fontWeight = FontWeight.Medium,
                color = chipStyle.textColor,
                maxLines = 1,
            )
        }
    }
}

private data class ChipStyle(val bgColor: Color, val borderColor: Color, val textColor: Color)

private fun resolveChipStyle(
    colors: YallaColors,
    tokens: AppTokens,
    state: SipAccountState,
    isActiveCall: Boolean,
    isMutedByCall: Boolean,
): ChipStyle = when {
    isActiveCall -> ChipStyle(colors.brandPrimary, colors.brandPrimary, Color.White)
    isMutedByCall && state is SipAccountState.Connected -> ChipStyle(colors.surfaceMuted, colors.borderDefault, colors.textSubtle)
    state is SipAccountState.Connected -> ChipStyle(
        colors.brandPrimary.copy(alpha = tokens.alphaMuted),
        colors.brandPrimary.copy(alpha = tokens.alphaMedium),
        colors.brandLight,
    )
    state is SipAccountState.Reconnecting -> ChipStyle(
        colors.statusWarning.copy(alpha = tokens.alphaSubtle),
        colors.statusWarning.copy(alpha = tokens.alphaBorder),
        colors.statusWarning,
    )
    else -> ChipStyle(
        colors.destructive.copy(alpha = tokens.alphaSubtle),
        colors.destructive.copy(alpha = tokens.alphaBorder),
        colors.destructive,
    )
}
