package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

/** Abstraction so tests can verify command construction without spawning msiexec. */
interface ProcessLauncher {
    fun launch(command: List<String>)
}

class RealProcessLauncher : ProcessLauncher {
    override fun launch(command: List<String>) {
        ProcessBuilder(command)
            .inheritIO()
            .start()
    }
}

/**
 * Spawns the C# bootstrapper and exits the JVM. See spec §11 for the
 * bootstrapper's behaviour.
 *
 * The bootstrapper lives next to `YallaSipPhone.exe` after install. In
 * dev we expect it under `bootstrapper/` relative to the working dir.
 */
class MsiBootstrapperInstaller(
    private val bootstrapperPathOverride: Path? = null,
    private val installDirOverride: Path? = null,
    private val processLauncher: ProcessLauncher = RealProcessLauncher(),
) {

    private val bootstrapperPath: Path
        get() = bootstrapperPathOverride ?: defaultBootstrapperPath()

    private val installDir: Path
        get() = installDirOverride ?: defaultInstallDir()

    fun buildCommand(
        msiPath: Path,
        expectedSha256: String,
        logPath: Path,
        parentPid: Long = currentPid(),
    ): List<String> = listOf(
        bootstrapperPath.toString(),
        "--msi", msiPath.toString(),
        "--install-dir", installDir.toString(),
        "--parent-pid", parentPid.toString(),
        "--expected-sha256", expectedSha256,
        "--log", logPath.toString(),
    )

    /**
     * Strip Mark-of-the-Web from the downloaded MSI (invariant I18).
     * No-op on non-Windows. Best-effort — failure is logged, not thrown.
     */
    fun stripMarkOfTheWeb(msiPath: Path) {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) return
        val adsPath = Path.of("${msiPath}:Zone.Identifier")
        runCatching { adsPath.deleteIfExists() }
            .onSuccess { logger.info { "Stripped Zone.Identifier from $msiPath" } }
            .onFailure { logger.warn(it) { "Failed to strip Zone.Identifier (non-fatal)" } }
    }

    /**
     * Launches the bootstrapper. Caller MUST call `exitProcess(0)` immediately.
     * Throws if the bootstrapper or MSI is missing.
     */
    fun install(msiPath: Path, expectedSha256: String, logPath: Path) {
        if (!bootstrapperPath.exists()) {
            logger.error { "Bootstrapper missing at $bootstrapperPath — cannot install" }
            throw IllegalStateException("Bootstrapper not found: $bootstrapperPath")
        }
        if (!msiPath.exists()) {
            throw IllegalStateException("MSI missing: $msiPath")
        }
        stripMarkOfTheWeb(msiPath)
        val cmd = buildCommand(msiPath, expectedSha256, logPath)
        logger.info { "Launching bootstrapper: $cmd" }
        processLauncher.launch(cmd)
    }

    companion object {
        private fun defaultBootstrapperPath(): Path =
            Path.of(System.getProperty("user.dir"), "bootstrapper", "yalla-update-bootstrap.exe")

        private fun defaultInstallDir(): Path {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) {
                val local = System.getenv("LOCALAPPDATA")
                    ?: (System.getProperty("user.home") + "\\AppData\\Local")
                Path.of(local, "YallaSipPhone")
            } else {
                Path.of(System.getProperty("user.dir"))
            }
        }

        private fun currentPid(): Long = ProcessHandle.current().pid()
    }
}
