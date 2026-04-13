package uz.yalla.sipphone.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import uz.yalla.sipphone.feature.main.toolbar.SettingsPanel
import uz.yalla.sipphone.feature.main.toolbar.ToolbarContent
import uz.yalla.sipphone.feature.main.update.UpdateDialog
import uz.yalla.sipphone.feature.main.update.UpdateDiagnosticsDialog
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
    var updateDialogVisible by remember { mutableStateOf(false) }
    val diagnosticsVisible by component.updateManager.diagnosticsVisible.collectAsState()

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
            onLocaleChange = { newLocale ->
                onLocaleChange(newLocale)
                component.onLocaleChanged(newLocale)
            },
            onLogout = component::logout,
            updateManager = component.updateManager,
            onUpdateBadgeClick = { updateDialogVisible = true },
        )

        WebviewPanel(
            jcefManager = component.jcefManager,
            dispatcherUrl = component.dispatcherUrl,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }

    SettingsPanel(
        visible = settingsVisible,
        isDarkTheme = isDarkTheme,
        locale = locale,
        agentInfo = component.agentInfo,
        onThemeToggle = {
            onThemeToggle()
            component.onThemeChanged(!isDarkTheme)
        },
        onLocaleChange = { newLocale ->
            onLocaleChange(newLocale)
            component.onLocaleChanged(newLocale)
        },
        onLogout = component::logout,
        onDismiss = component.toolbar::closeSettings,
    )

    if (updateDialogVisible) {
        UpdateDialog(
            stateFlow = component.updateManager.state,
            callStateFlow = component.toolbar.callState,
            onInstall = {
                component.updateManager.confirmInstall()
                updateDialogVisible = false
            },
            onDismiss = {
                component.updateManager.dismiss()
                updateDialogVisible = false
            },
        )
    }

    val lastCheck = component.updateManager.lastCheckMillis().let { ms ->
        if (ms == 0L) "—" else Instant.ofEpochMilli(ms).toString()
    }
    UpdateDiagnosticsDialog(
        visible = diagnosticsVisible,
        installId = "—",
        channel = "—",
        currentVersion = uz.yalla.sipphone.di.BuildVersion.CURRENT,
        stateText = component.updateManager.state.value.toString(),
        lastCheckText = lastCheck,
        lastErrorText = component.updateManager.lastErrorMessage() ?: "—",
        logTail = "",
        onCopy = { component.updateManager.hideDiagnostics() },
        onDismiss = { component.updateManager.hideDiagnostics() },
    )
}
