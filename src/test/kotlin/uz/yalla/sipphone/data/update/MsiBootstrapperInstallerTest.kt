package uz.yalla.sipphone.data.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsiBootstrapperInstallerTest {

    private lateinit var tempRoot: Path

    @BeforeTest
    fun setup() {
        tempRoot = Files.createTempDirectory("installer-test")
    }

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `command is built with bootstrapper and args`() {
        val installer = MsiBootstrapperInstaller(
            bootstrapperPathOverride = tempRoot.resolve("yalla-update-bootstrap.exe"),
            installDirOverride = tempRoot.resolve("app"),
            processLauncher = FakeProcessLauncher(),
        )
        val msi = tempRoot.resolve("YallaSipPhone-1.2.0.msi").also { it.writeBytes(byteArrayOf(1, 2, 3)) }

        val cmd = installer.buildCommand(
            msiPath = msi,
            expectedSha256 = "a".repeat(64),
            logPath = tempRoot.resolve("install.log"),
            parentPid = 42,
        )
        assertEquals(tempRoot.resolve("yalla-update-bootstrap.exe").toString(), cmd[0])
        assertTrue("--msi" in cmd)
        assertTrue("--install-dir" in cmd)
        assertTrue("--parent-pid" in cmd)
        assertTrue("42" in cmd)
        assertTrue("--expected-sha256" in cmd)
        assertTrue("--log" in cmd)
    }

    @Test
    fun `install throws when bootstrapper missing`() {
        val installer = MsiBootstrapperInstaller(
            bootstrapperPathOverride = tempRoot.resolve("nonexistent.exe"),
            installDirOverride = tempRoot.resolve("app"),
            processLauncher = FakeProcessLauncher(),
        )
        val msi = tempRoot.resolve("a.msi").also { it.writeBytes(byteArrayOf(1)) }
        try {
            installer.install(msi, "a".repeat(64), tempRoot.resolve("log"))
            error("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Bootstrapper not found"))
        }
    }

    @Test
    fun `install launches process when files exist`() {
        val boot = tempRoot.resolve("yalla-update-bootstrap.exe").also { it.writeBytes(byteArrayOf(0)) }
        val launcher = FakeProcessLauncher()
        val installer = MsiBootstrapperInstaller(
            bootstrapperPathOverride = boot,
            installDirOverride = tempRoot.resolve("app"),
            processLauncher = launcher,
        )
        val msi = tempRoot.resolve("a.msi").also { it.writeBytes(byteArrayOf(1)) }
        installer.install(msi, "a".repeat(64), tempRoot.resolve("log"))
        assertTrue(launcher.lastCommand != null)
        assertTrue(launcher.lastCommand!![0].contains("yalla-update-bootstrap.exe"))
    }
}

class FakeProcessLauncher : ProcessLauncher {
    var lastCommand: List<String>? = null
    override fun launch(command: List<String>) {
        lastCommand = command
    }
}
