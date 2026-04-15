package uz.yalla.sipphone.demo.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateInstaller
import uz.yalla.sipphone.domain.update.UpdateRelease
import uz.yalla.sipphone.domain.update.UpdateState

/**
 * Demo-only driver for the update UI. Owns MutableStateFlows that feed
 * the real production composables. Scenario methods flip states directly
 * — no UpdateManager, no network, no installer process.
 */
class UpdateDemoDriver(private val scope: CoroutineScope) {

    val currentVersion: String = "1.0.4"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _channel = MutableStateFlow(UpdateChannel.STABLE)
    val channel: StateFlow<UpdateChannel> = _channel.asStateFlow()

    private val _locale = MutableStateFlow("uz")
    val locale: StateFlow<String> = _locale.asStateFlow()

    private val _diagnosticsVisible = MutableStateFlow(false)
    val diagnosticsVisible: StateFlow<Boolean> = _diagnosticsVisible.asStateFlow()

    private val sampleRelease = UpdateRelease(
        version = "1.0.5",
        minSupportedVersion = "1.0.0",
        releaseNotes = """
            • Fixed SIP registration retry on weak networks
            • Hidden beta channel toggle via Ctrl+Shift+Alt+B
            • Improved call-active guard for updates
            • Better error messages in the update diagnostics panel
        """.trimIndent(),
        installer = UpdateInstaller(
            url = "http://192.168.0.98:8080/releases/YallaSipPhone-1.0.5.msi",
            sha256 = "a".repeat(64),
            size = 34_500_000L,
        ),
    )

    fun reset() { _state.value = UpdateState.Idle }

    fun showChecking() { _state.value = UpdateState.Checking }

    fun showDownloading(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val size = sampleRelease.installer.size
        val read = size * clamped / 100L
        _state.value = UpdateState.Downloading(sampleRelease, read, size)
    }

    fun showVerifying() { _state.value = UpdateState.Verifying(sampleRelease) }

    fun showReady() { _state.value = UpdateState.ReadyToInstall(sampleRelease, "/tmp/fake.msi") }

    fun showInstalling() { _state.value = UpdateState.Installing(sampleRelease) }

    fun failVerify() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.VERIFY, "sha256 mismatch")
    }

    fun failDownload() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.DOWNLOAD, "connection reset")
    }

    fun failDiskFull() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.DISK_FULL, "69 MB needed, 12 MB free")
    }

    fun failUntrustedUrl() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.UNTRUSTED_URL, "evil.example.com not in allow-list")
    }

    fun failMalformed() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.MALFORMED_MANIFEST, "version is not semver: 1.0.5-beta")
    }

    fun toggleCallActive() {
        _callState.value = when (_callState.value) {
            is CallState.Idle -> CallState.Active(
                callId = "demo-call",
                remoteNumber = "+998901112233",
                remoteName = "Demo caller",
                isOutbound = false,
                isMuted = false,
                isOnHold = false,
            )
            else -> CallState.Idle
        }
    }

    fun toggleChannel() {
        _channel.value = if (_channel.value == UpdateChannel.STABLE) UpdateChannel.BETA else UpdateChannel.STABLE
    }

    fun cycleLocale() {
        _locale.value = if (_locale.value == "uz") "ru" else "uz"
    }

    fun showDiagnostics() { _diagnosticsVisible.value = true }
    fun hideDiagnostics() { _diagnosticsVisible.value = false }

    /** Emulates UpdateManager.confirmInstall() without calling exitProcess. */
    fun mockInstall() {
        scope.launch {
            _state.value = UpdateState.Installing(sampleRelease)
            val pid = ProcessHandle.current().pid()
            println(
                "[INSTALL HANDOFF] Would have spawned bootstrapper.exe " +
                    "--msi /tmp/fake.msi " +
                    "--expected-sha256 ${sampleRelease.installer.sha256} " +
                    "--parent-pid $pid",
            )
            delay(3_000)
            _state.value = UpdateState.Idle
        }
    }
}
