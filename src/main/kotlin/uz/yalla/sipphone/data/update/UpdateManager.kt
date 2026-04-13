package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.nio.file.FileStore
import java.nio.file.Files
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.Semver
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateRelease
import uz.yalla.sipphone.domain.update.UpdateState
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/** Abstractions on dependencies so the manager is testable without Ktor or msiexec. */
interface UpdateApiContract {
    suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String = "windows",
    ): UpdateCheckResult
}

interface UpdateDownloaderContract {
    suspend fun download(release: UpdateRelease): DownloadResult
}

interface InstallerContract {
    fun install(msiPath: Path, expectedSha256: String, logPath: Path)
}

// Adapters so the production Koin graph binds the real classes.
fun UpdateApi.asContract(): UpdateApiContract = object : UpdateApiContract {
    override suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String,
    ): UpdateCheckResult = this@asContract.check(channel, currentVersion, installId, platform)
}

fun UpdateDownloader.asContract(): UpdateDownloaderContract = object : UpdateDownloaderContract {
    override suspend fun download(release: UpdateRelease): DownloadResult =
        this@asContract.download(release)
}

fun MsiBootstrapperInstaller.asContract(): InstallerContract = object : InstallerContract {
    override fun install(msiPath: Path, expectedSha256: String, logPath: Path) {
        this@asContract.install(msiPath, expectedSha256, logPath)
    }
}

/**
 * Orchestrates the update flow. State machine per spec §7.
 *
 * Thread model: owns a [CoroutineScope] injected by Koin. All transitions
 * run on that scope; the state is read by the UI via [state].
 *
 * Key invariants:
 * - I1: install never scheduled while a call is active.
 * - I15: downgrades are refused.
 * - I16: check is skipped while call is active.
 * - I13: version blacklist after 3 consecutive verify failures.
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private val api: UpdateApiContract,
    private val downloader: UpdateDownloaderContract,
    private val installer: InstallerContract,
    private val paths: UpdatePaths,
    private val callState: StateFlow<CallState>,
    private val currentVersion: String,
    private val channelProvider: () -> UpdateChannel,
    private val installIdProvider: () -> String,
    private val pollIntervalMillis: Long = 60 * 60 * 1000L,
    private val exitProcess: (Int) -> Unit = { code -> kotlin.system.exitProcess(code) },
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _diagnosticsVisible = MutableStateFlow(false)
    val diagnosticsVisible: StateFlow<Boolean> = _diagnosticsVisible.asStateFlow()

    fun toggleDiagnostics() {
        _diagnosticsVisible.value = !_diagnosticsVisible.value
    }

    fun hideDiagnostics() {
        _diagnosticsVisible.value = false
    }

    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null
    private var verifyFailureCount: Int = 0
    private var blacklistedVersion: String? = null
    private var lastCheckEpochMillis: Long = 0
    private var lastError: String? = null
    @Volatile
    private var installInProgress: Boolean = false

    fun lastCheckMillis(): Long = lastCheckEpochMillis
    fun lastErrorMessage(): String? = lastError
    fun isInstallInProgress(): Boolean = installInProgress

    fun start() {
        if (!running.compareAndSet(false, true)) return
        paths.cleanupPartials()
        loopJob = scope.launch {
            while (isActive) {
                runCheckCycle()
                delay(jitterDelay())
            }
        }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
    }

    /** Manually trigger a single check cycle. */
    fun checkNow() {
        scope.launch { runCheckCycle() }
    }

    /**
     * Called by the UI when the operator clicks "Install".
     * Waits until the call state is Idle, then triggers the install and exits.
     */
    fun confirmInstall() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        scope.launch {
            callState.first { it is CallState.Idle }
            if (callState.value !is CallState.Idle) return@launch
            _state.value = UpdateState.Installing(ready.release)
            installInProgress = true
            runCatching {
                installer.install(
                    msiPath = Path.of(ready.msiPath),
                    expectedSha256 = ready.release.installer.sha256,
                    logPath = paths.installLogPath(),
                )
                exitProcess(0)
            }.onFailure { t ->
                logger.error(t) { "Installer failed to launch" }
                installInProgress = false
                lastError = t.message
                _state.value = UpdateState.Failed(
                    UpdateState.Failed.Stage.INSTALL,
                    t.message ?: "install failed",
                )
            }
        }
    }

    /** UI "Later" button — reset state to Idle so the next tick re-checks. */
    fun dismiss() {
        val s = _state.value
        if (s is UpdateState.ReadyToInstall || s is UpdateState.Failed) {
            _state.value = UpdateState.Idle
        }
    }

    private suspend fun runCheckCycle() {
        // I16: never poll/download during an active call.
        if (callState.value !is CallState.Idle) {
            logger.debug { "Skipping update check: call not idle" }
            return
        }
        if (installInProgress) return

        lastCheckEpochMillis = System.currentTimeMillis()
        _state.value = UpdateState.Checking

        val result = api.check(
            channel = channelProvider(),
            currentVersion = currentVersion,
            installId = installIdProvider(),
        )

        when (result) {
            is UpdateCheckResult.NoUpdate -> _state.value = UpdateState.Idle
            is UpdateCheckResult.Malformed -> {
                lastError = result.reason
                _state.value = UpdateState.Failed(
                    UpdateState.Failed.Stage.MALFORMED_MANIFEST,
                    result.reason,
                )
                delay(1500)
                if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
            }
            is UpdateCheckResult.Error -> {
                lastError = result.cause?.message ?: "network error"
                _state.value = UpdateState.Idle
            }
            is UpdateCheckResult.Available -> handleAvailable(result.release)
        }
    }

    private suspend fun handleAvailable(release: UpdateRelease) {
        // I15: refuse downgrades / same version.
        val current = Semver.parseOrNull(currentVersion)
        val incoming = Semver.parseOrNull(release.version)
        if (current != null && incoming != null && incoming <= current) {
            _state.value = UpdateState.Idle
            return
        }
        if (blacklistedVersion == release.version) {
            logger.warn { "Version ${release.version} is blacklisted, skipping" }
            _state.value = UpdateState.Idle
            return
        }

        // I4: pre-flight disk space. size × 2 covers .part + final .msi + bootstrapper quarantine.
        if (!hasEnoughDisk(release.installer.size * 2)) {
            lastError = "insufficient disk space for ${release.installer.size * 2} bytes"
            logger.warn { lastError!! }
            _state.value = UpdateState.Failed(
                UpdateState.Failed.Stage.DISK_FULL,
                "size * 2 = ${release.installer.size * 2} bytes required",
            )
            delay(1500)
            if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
            return
        }

        _state.value = UpdateState.Downloading(release, 0, release.installer.size)
        val dl = downloader.download(release)
        when (dl) {
            is DownloadResult.Success -> {
                _state.value = UpdateState.Verifying(release)
                verifyFailureCount = 0
                _state.value = UpdateState.ReadyToInstall(release, dl.msiFile.toString())
            }
            is DownloadResult.VerifyFailed -> {
                verifyFailureCount++
                lastError = "sha256 mismatch"
                if (verifyFailureCount >= 3) {
                    blacklistedVersion = release.version
                    logger.warn { "Blacklisting ${release.version} after $verifyFailureCount verify failures" }
                }
                _state.value = UpdateState.Failed(UpdateState.Failed.Stage.VERIFY, "sha256 mismatch")
                delay(1500)
                if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
            }
            is DownloadResult.Failed -> {
                lastError = dl.cause?.message ?: "download failed"
                _state.value = UpdateState.Failed(
                    UpdateState.Failed.Stage.DOWNLOAD,
                    lastError ?: "download failed",
                )
                delay(1500)
                if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
            }
        }
    }

    private fun jitterDelay(): Long = pollIntervalMillis + (0 until 600_000L).random()

    /**
     * I4: pre-flight disk check on the filesystem hosting the updates dir.
     * Returns true (don't block) if we can't query the filesystem — failing
     * open rather than stranding the operator on an old version because of
     * an obscure API problem.
     */
    private fun hasEnoughDisk(neededBytes: Long): Boolean = runCatching {
        val store: FileStore = Files.getFileStore(paths.updatesDir)
        store.usableSpace >= neededBytes
    }.getOrDefault(true)
}
