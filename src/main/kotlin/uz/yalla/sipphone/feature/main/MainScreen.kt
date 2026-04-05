package uz.yalla.sipphone.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import uz.yalla.sipphone.feature.main.webview.WebviewPanel
import uz.yalla.sipphone.feature.main.toolbar.ToolbarContent
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun MainScreen(
    component: MainComponent,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LocalYallaColors.current.backgroundBase)) {
        ToolbarContent(
            component = component.toolbar,
            isDarkTheme = isDarkTheme,
            onThemeToggle = onThemeToggle,
            onLogout = component::logout,
        )
        Spacer(Modifier.height(LocalAppTokens.current.spacingSm))
        WebviewPanel(
            jcefManager = component.jcefManager,
            dispatcherUrl = component.dispatcherUrl,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}
