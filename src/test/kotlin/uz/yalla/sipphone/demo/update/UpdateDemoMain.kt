package uz.yalla.sipphone.demo.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uz.yalla.sipphone.feature.main.update.UpdateBadge
import uz.yalla.sipphone.feature.main.update.UpdateDialog
import uz.yalla.sipphone.feature.main.update.UpdateDiagnosticsDialog
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

/**
 * Standalone visual demo of the auto-update UI. Renders the real
 * UpdateBadge, UpdateDialog, and UpdateDiagnosticsDialog driven by
 * an UpdateDemoDriver. No Koin, no network, no installer process.
 *
 * Run with:  ./gradlew runUpdateDemo
 */
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val driver = UpdateDemoDriver(scope)
    val autoPlay = UpdateDemoAutoPlay(driver, scope)
    val logger = UpdateDemoConsoleLogger(driver, scope)

    logger.start()

    application {
        val locale by driver.locale.collectAsState()

        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 760.dp),
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "DEMO — Yalla SIP Phone Update UI",
            state = windowState,
            alwaysOnTop = false,
            resizable = true,
        ) {
            YallaSipPhoneTheme(isDark = false, locale = locale) {
                UpdateDemoRoot(driver = driver, autoPlay = autoPlay)
            }
        }
    }
}

@Composable
private fun UpdateDemoRoot(driver: UpdateDemoDriver, autoPlay: UpdateDemoAutoPlay) {
    val diagnosticsVisible by driver.diagnosticsVisible.collectAsState()
    val channel by driver.channel.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DEMO: Auto-Update UI    (current v${driver.currentVersion})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                UpdateBadge(
                    state = driver.state,
                    onClick = { println("[demo] badge clicked (no-op — dialog auto-shows)") },
                )
            }

            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                UpdateDemoControlPanel(driver = driver, autoPlay = autoPlay)

                UpdateDialog(
                    stateFlow = driver.state,
                    callStateFlow = driver.callState,
                    onInstall = { driver.mockInstall() },
                    onDismiss = { driver.reset() },
                )

                UpdateDiagnosticsDialog(
                    visible = diagnosticsVisible,
                    installId = "demo-install-id-0000-1111-2222",
                    channel = channel.value,
                    currentVersion = driver.currentVersion,
                    stateText = driver.state.value::class.simpleName ?: "?",
                    lastCheckText = "never (demo mode)",
                    lastErrorText = "none",
                    logTail = "[demo] no real log tail — this is a mock diagnostics panel",
                    onCopy = { println("[demo] diagnostics copy clicked") },
                    onDismiss = { driver.hideDiagnostics() },
                )
            }
        }
    }
}
