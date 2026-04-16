package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
     *
     * No-op on non-Windows. Best-effort — failure is logged, not thrown.
     *
     * Uses `java.io.File` rather than `java.nio.file.Path` because Java NIO
     * refuses to parse the `:Zone.Identifier` alternate data stream suffix
     * as a valid Path on Windows — `Path.of("C:\\a.msi:Zone.Identifier")`
     * throws `InvalidPathException`. The legacy `File` API has no such
     * validation and lets the deletion reach the filesystem.
     */
    fun stripMarkOfTheWeb(msiPath: Path) {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) return
        runCatching {
            val ads = java.io.File("${msiPath}:Zone.Identifier")
            if (ads.exists()) ads.delete() else true
        }
            .onSuccess { logger.info { "Stripped Zone.Identifier from $msiPath (if present)" } }
            .onFailure { logger.warn(it) { "Failed to strip Zone.Identifier (non-fatal)" } }
    }

    /**
     * Launches the bootstrapper. Caller MUST call `exitProcess(0)` immediately.
     * Throws if the bootstrapper or MSI is missing.
     *
     * The bootstrapper is copied to %TEMP% before launch so it doesn't hold
     * a file lock inside the install directory while msiexec runs.
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

        val tempDir = Path.of(System.getProperty("java.io.tmpdir"), "yalla-update")
        Files.createDirectories(tempDir)
        val tempBootstrapper = tempDir.resolve("yalla-update-bootstrap.exe")
        Files.copy(bootstrapperPath, tempBootstrapper, StandardCopyOption.REPLACE_EXISTING)
        logger.info { "Copied bootstrapper to $tempBootstrapper" }

        val cmd = listOf(
            tempBootstrapper.toString(),
            "--msi", msiPath.toString(),
            "--install-dir", installDir.toString(),
            "--parent-pid", currentPid().toString(),
            "--expected-sha256", expectedSha256,
            "--log", logPath.toString(),
        )
        logger.info { "Launching bootstrapper: $cmd" }
        processLauncher.launch(cmd)
    }

    companion object {
        /**
         * Resolve the bootstrapper binary.
         *
         * 1. `compose.application.resources.dir` — set by Compose Desktop at
         *    runtime to the per-platform app-resources directory inside the
         *    installed MSI. Same mechanism used by [NativeLibraryLoader] for
         *    `pjsua2.dll`. This is the production path.
         * 2. Dev fallback: `<project-dir>/app-resources/windows-x64/` — when
         *    running via `./gradlew run` the above system property is also
         *    set, but we keep this as a belt-and-braces fallback for ad-hoc
         *    invocations.
         */
        private fun defaultBootstrapperPath(): Path {
            val resourcesDir = System.getProperty("compose.application.resources.dir")
            if (resourcesDir != null) {
                val packaged = Path.of(resourcesDir, "yalla-update-bootstrap.exe")
                if (packaged.exists()) return packaged
            }
            return Path.of(
                System.getProperty("user.dir"),
                "app-resources",
                "windows-x64",
                "yalla-update-bootstrap.exe",
            )
        }

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
