package uz.yalla.sipphone.demo.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateDemoControlPanel(
    driver: UpdateDemoDriver,
    autoPlay: UpdateDemoAutoPlay,
    modifier: Modifier = Modifier,
) {
    val state by driver.state.collectAsState()
    val callState by driver.callState.collectAsState()
    val channel by driver.channel.collectAsState()
    val locale by driver.locale.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InspectorLine(state, callState, channel, locale)
        Spacer(Modifier.height(4.dp))

        SectionHeader("Auto-play")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { autoPlay.start() }) { Text("Play All") }
            OutlinedButton(onClick = { autoPlay.pause() }) { Text("Pause") }
            OutlinedButton(onClick = { autoPlay.resume() }) { Text("Resume") }
            OutlinedButton(onClick = { autoPlay.reset() }) { Text("Reset") }
        }

        SectionHeader("Core states")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { driver.reset() }) { Text("Idle") }
            OutlinedButton(onClick = { driver.showChecking() }) { Text("Checking") }
            listOf(0, 25, 50, 75, 100).forEach { p ->
                OutlinedButton(onClick = { driver.showDownloading(p) }) { Text("DL $p%") }
            }
            OutlinedButton(onClick = { driver.showVerifying() }) { Text("Verifying") }
            OutlinedButton(onClick = { driver.showReady() }) { Text("Ready") }
            OutlinedButton(onClick = { driver.showInstalling() }) { Text("Installing") }
        }

        SectionHeader("Failures")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { driver.failVerify() }) { Text("Verify fail") }
            OutlinedButton(onClick = { driver.failDownload() }) { Text("Download fail") }
            OutlinedButton(onClick = { driver.failDiskFull() }) { Text("Disk full") }
            OutlinedButton(onClick = { driver.failUntrustedUrl() }) { Text("Untrusted URL") }
            OutlinedButton(onClick = { driver.failMalformed() }) { Text("Malformed manifest") }
        }

        SectionHeader("Scenarios")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { driver.toggleCallActive() }) {
                Text(if (callState is CallState.Idle) "Start fake call" else "End fake call")
            }
            OutlinedButton(onClick = { driver.showDiagnostics() }) { Text("Open diagnostics") }
        }

        SectionHeader("Settings")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { driver.cycleLocale() }) { Text("Locale: $locale") }
            OutlinedButton(onClick = { driver.toggleChannel() }) { Text("Channel: ${channel.value}") }
        }
    }
}

@Composable
private fun InspectorLine(
    state: UpdateState,
    callState: CallState,
    channel: UpdateChannel,
    locale: String,
) {
    val stateText = when (state) {
        is UpdateState.Idle -> "Idle"
        is UpdateState.Checking -> "Checking"
        is UpdateState.Downloading -> "Downloading v${state.release.version} (${percent(state.bytesRead, state.total)}%)"
        is UpdateState.Verifying -> "Verifying v${state.release.version}"
        is UpdateState.ReadyToInstall -> "ReadyToInstall v${state.release.version}"
        is UpdateState.Installing -> "Installing v${state.release.version}"
        is UpdateState.Failed -> "Failed(${state.stage}) — ${state.reason}"
    }
    val callText = if (callState is CallState.Idle) "Idle" else "Active"
    Text(
        text = "State: $stateText   Call: $callText   Channel: ${channel.value}   Locale: $locale",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

private fun percent(read: Long, total: Long): Int =
    if (total <= 0) 0 else ((read.toDouble() / total) * 100).toInt().coerceIn(0, 100)
