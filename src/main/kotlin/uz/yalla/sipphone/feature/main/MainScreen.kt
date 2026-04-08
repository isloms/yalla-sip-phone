package uz.yalla.sipphone.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import uz.yalla.sipphone.feature.main.toolbar.SettingsPanel
import uz.yalla.sipphone.feature.main.toolbar.ToolbarContent
import uz.yalla.sipphone.feature.main.webview.WebviewPanel
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun MainScreen(
    component: MainComponent,
    isDarkTheme: Boolean,
    locale: String,
    onThemeToggle: () -> Unit,
    onLocaleChange: (String) -> Unit,
) {
    val settingsVisible by component.toolbar.settingsVisible.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(LocalYallaColors.current.backgroundBase)) {
        ToolbarContent(
            component = component.toolbar,
            isDarkTheme = isDarkTheme,
            locale = locale,
            agentInfo = component.agentInfo,
            onThemeToggle = {
                onThemeToggle()
                component.onThemeChanged(!isDarkTheme)
            },
            onLocaleChange = onLocaleChange,
            onLogout = component::logout,
        )

        // Webview + settings side panel
        Row(modifier = Modifier.weight(1f).fillMaxSize()) {
            WebviewPanel(
                jcefManager = component.jcefManager,
                dispatcherUrl = component.dispatcherUrl,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            SettingsPanel(
                visible = settingsVisible,
                isDarkTheme = isDarkTheme,
                locale = locale,
                agentInfo = component.agentInfo,
                onThemeToggle = {
                    onThemeToggle()
                    component.onThemeChanged(!isDarkTheme)
                },
                onLocaleChange = onLocaleChange,
                onLogout = component::logout,
                onDismiss = component.toolbar::closeSettings,
            )
        }
    }
}
