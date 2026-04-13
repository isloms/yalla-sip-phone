package uz.yalla.sipphone.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateInstaller
import uz.yalla.sipphone.domain.update.UpdateRelease
import uz.yalla.sipphone.domain.update.UpdateState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    private lateinit var tempRoot: Path
    private val callState = MutableStateFlow<CallState>(CallState.Idle)
    private val fakeApi = FakeUpdateApi()
    private val fakeDownloader = FakeDownloader()
    private val fakeInstaller = RecordingInstaller()

    @BeforeTest
    fun setup() {
        tempRoot = Files.createTempDirectory("manager-test")
    }

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    private fun manager(scope: CoroutineScope): UpdateManager {
        val paths = UpdatePaths(rootOverride = tempRoot)
        return UpdateManager(
            scope = scope,
            api = fakeApi,
            downloader = fakeDownloader,
            installer = fakeInstaller,
            paths = paths,
            callState = callState.asStateFlow(),
            currentVersion = "1.0.0",
            channelProvider = { UpdateChannel.STABLE },
            installIdProvider = { "install-id" },
            pollIntervalMillis = 1_000_000L,
            exitProcess = { /* no-op for tests */ },
        )
    }

    @Test
    fun `initial state is Idle`() = runTest {
        val m = manager(this)
        assertEquals(UpdateState.Idle, m.state.value)
    }

    @Test
    fun `checkNow transitions through happy path to ReadyToInstall`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        val msi = tempRoot.resolve("YallaSipPhone-1.2.0.msi").also { it.writeBytes(byteArrayOf(1)) }
        fakeDownloader.nextResult = DownloadResult.Success(msi)

        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()

        val s = m.state.value
        assertIs<UpdateState.ReadyToInstall>(s)
        assertEquals("1.2.0", s.release.version)
    }

    @Test
    fun `checkNow stays Idle when no update available`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.NoUpdate
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
    }

    @Test
    fun `check is skipped while call is active (invariant I16)`() = runTest {
        callState.value = CallState.Active(
            callId = "c1", remoteNumber = "", remoteName = null,
            isOutbound = false, isMuted = false, isOnHold = false,
        )
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
        assertEquals(0, fakeApi.callCount)
    }

    @Test
    fun `version lower than current is refused (invariant I15)`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease(version = "0.9.0"))
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
    }

    @Test
    fun `confirmInstall waits for idle call before installing`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        val msi = tempRoot.resolve("YallaSipPhone-1.2.0.msi").also { it.writeBytes(byteArrayOf(1)) }
        fakeDownloader.nextResult = DownloadResult.Success(msi)

        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertIs<UpdateState.ReadyToInstall>(m.state.value)

        callState.value = CallState.Active(
            callId = "c1", remoteNumber = "", remoteName = null,
            isOutbound = false, isMuted = false, isOnHold = false,
        )
        m.confirmInstall()
        advanceUntilIdle()
        assertEquals(0, fakeInstaller.installCount)

        callState.value = CallState.Idle
        advanceUntilIdle()
        assertEquals(1, fakeInstaller.installCount)
    }

    @Test
    fun `three consecutive verify failures blacklist the version`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        fakeDownloader.nextResult = DownloadResult.VerifyFailed

        val m = manager(this)
        repeat(3) {
            m.checkNow()
            advanceUntilIdle()
        }
        // Fourth attempt would hit the API and return Available again, but the
        // blacklist skip should keep state at Idle without calling downloader again.
        val downloadsBefore = fakeDownloader.downloadCount
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
        assertEquals(downloadsBefore, fakeDownloader.downloadCount)
    }

    @Test
    fun `malformed manifest goes to Failed then back to Idle`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Malformed("bad sha")
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
    }

    private fun fakeRelease(version: String = "1.2.0"): UpdateRelease = UpdateRelease(
        version = version,
        minSupportedVersion = "1.0.0",
        releaseNotes = "notes",
        installer = UpdateInstaller(
            url = "https://downloads.yalla.uz/a.msi",
            sha256 = "a".repeat(64),
            size = 100,
        ),
    )
}

private class FakeUpdateApi : UpdateApiContract {
    var nextResult: UpdateCheckResult = UpdateCheckResult.NoUpdate
    var callCount = 0
    override suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String,
    ): UpdateCheckResult {
        callCount++
        return nextResult
    }
}

private class FakeDownloader : UpdateDownloaderContract {
    var nextResult: DownloadResult = DownloadResult.Failed(null)
    var downloadCount = 0
    override suspend fun download(release: UpdateRelease): DownloadResult {
        downloadCount++
        return nextResult
    }
}

private class RecordingInstaller : InstallerContract {
    var installCount = 0
    override fun install(msiPath: Path, expectedSha256: String, logPath: Path) {
        installCount++
    }
}
