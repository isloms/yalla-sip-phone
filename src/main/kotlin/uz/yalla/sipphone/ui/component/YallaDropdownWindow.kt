package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * OS-level dropdown window anchored to a screen position.
 * Uses DialogWindow (required for rendering above JCEF).
 * Dismisses on focus loss with proper cleanup via DisposableEffect.
 */
@Composable
fun YallaDropdownWindow(
    visible: Boolean,
    anchorScreenX: Dp,
    anchorScreenY: Dp,
    isDarkTheme: Boolean,
    locale: String,
    width: Dp = 180.dp,
    height: Dp = 130.dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!visible) return

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "",
        state = rememberDialogState(
            position = WindowPosition(anchorScreenX, anchorScreenY),
            size = DpSize(width, height),
        ),
        resizable = false,
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
    ) {
        DisposableEffect(Unit) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {}
                override fun windowLostFocus(e: WindowEvent?) { onDismiss() }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
            val colors = LocalYallaColors.current
            val tokens = LocalAppTokens.current

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(width)
                        .clip(tokens.shapeMedium)
                        .background(colors.backgroundSecondary),
                ) {
                    content()
                }
            }
        }
    }
}
