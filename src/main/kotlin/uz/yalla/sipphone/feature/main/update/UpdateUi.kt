package uz.yalla.sipphone.feature.main.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateState
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.strings.StringResources

@Composable
fun UpdateDialog(
    stateFlow: StateFlow<UpdateState>,
    callStateFlow: StateFlow<CallState>,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by stateFlow.collectAsState()
    val callState by callStateFlow.collectAsState()
    val strings = LocalStrings.current

    if (state is UpdateState.Idle || state is UpdateState.Checking) return

    val release = when (val s = state) {
        is UpdateState.Downloading -> s.release
        is UpdateState.Verifying -> s.release
        is UpdateState.ReadyToInstall -> s.release
        is UpdateState.Installing -> s.release
        else -> null
    }

    val callIsIdle = callState is CallState.Idle
    val canInstall = state is UpdateState.ReadyToInstall && callIsIdle

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.updateAvailableDialogTitle + (release?.version?.let { " — v$it" } ?: "")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            ) {
                val statusText = when (val s = state) {
                    is UpdateState.Downloading -> "${strings.updateDownloadingMessage} (${percentOf(s.bytesRead, s.total)}%)"
                    is UpdateState.Verifying -> strings.updateVerifyingMessage
                    is UpdateState.Installing -> strings.updateInstallingMessage
                    is UpdateState.Failed -> failureText(s, strings)
                    is UpdateState.ReadyToInstall -> if (!callIsIdle) strings.updateWaitingForCallMessage else ""
                    else -> ""
                }
                if (statusText.isNotEmpty()) {
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }

                val downloading = state as? UpdateState.Downloading
                if (downloading != null) {
                    LinearProgressIndicator(
                        progress = {
                            if (downloading.total > 0) downloading.bytesRead.toFloat() / downloading.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (release != null && release.releaseNotes.isNotBlank()) {
                    Text(strings.updateReleaseNotesHeader, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(release.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = canInstall) {
                Text(strings.updateInstallButton)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.updateLaterButton) }
        },
    )
}

@Composable
fun UpdateDiagnosticsDialog(
    visible: Boolean,
    installId: String,
    channel: String,
    currentVersion: String,
    stateText: String,
    lastCheckText: String,
    lastErrorText: String,
    logTail: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.updateDiagnosticsTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${strings.updateCurrentVersion}: $currentVersion")
                Text("${strings.updateDiagnosticsInstallId}: $installId")
                Text("${strings.updateDiagnosticsChannel}: $channel")
                Text("${strings.updateDiagnosticsState}: $stateText")
                Text("${strings.updateDiagnosticsLastCheck}: $lastCheckText")
                Text("${strings.updateDiagnosticsLastError}: $lastErrorText")
                Spacer(Modifier.height(8.dp))
                Text(strings.updateDiagnosticsLogTail, style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(logTail, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text(strings.updateDiagnosticsCopy) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.updateDiagnosticsClose) } },
    )
}

private fun percentOf(read: Long, total: Long): Int =
    if (total <= 0) 0 else ((read.toDouble() / total) * 100).toInt().coerceIn(0, 100)

private fun failureText(failed: UpdateState.Failed, s: StringResources): String = when (failed.stage) {
    UpdateState.Failed.Stage.VERIFY -> s.updateFailedVerify
    UpdateState.Failed.Stage.DOWNLOAD -> s.updateFailedDownload
    UpdateState.Failed.Stage.DISK_FULL -> s.updateFailedDisk
    UpdateState.Failed.Stage.UNTRUSTED_URL -> s.updateFailedUntrustedUrl
    UpdateState.Failed.Stage.MALFORMED_MANIFEST -> s.updateFailedMalformedManifest
    else -> failed.reason
}
