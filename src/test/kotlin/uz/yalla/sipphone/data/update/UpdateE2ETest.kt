package uz.yalla.sipphone.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateState
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * End-to-end orchestration test for the auto-update flow.
 *
 * Wires the REAL [UpdateApi], [UpdateDownloader], [Sha256Verifier], and
 * [UpdateManager] together against a faked HTTP backend (Ktor MockEngine)
 * that mimics the RoyalTaxi backend's exact response shape: wrapped
 * `ApiResponse<T>` envelope + http installer URL + 192.168.0.98 host.
 *
 * The only mocked piece is [InstallerContract] (msiexec cannot run in a unit
 * test); we record the call and assert the correct arguments.
 *
 * Uses `runBlocking` + polling rather than `runTest + advanceUntilIdle`
 * because Ktor's MockEngine completes on real IO dispatchers outside the
 * TestScheduler — we need real wall-clock time to let the pipeline run.
 *
 * If this test passes, the entire client-side update pipeline works
 * end-to-end against the production backend response format. The only
 * remaining unknown is bootstrapper + msiexec interaction with Windows
 * file locks, which requires a real Windows machine.
 */
class UpdateE2ETest {

    private lateinit var tempRoot: Path

    @BeforeTest
    fun setup() {
        tempRoot = Files.createTempDirectory("e2e-update-test")
    }

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fakeBackend(
        manifestVersion: String,
        manifestMinSupported: String,
        msiBytes: ByteArray,
        msiPath: String = "/releases/YallaSipPhone-$manifestVersion.msi",
    ): HttpClient {
        val sha = sha256Hex(msiBytes)
        val size = msiBytes.size.toLong()
        val installerUrl = "http://192.168.0.98:8080$msiPath"

        val manifestBody = """
            {
              "status": true,
              "code": 200,
              "message": "success",
              "result": {
                "updateAvailable": true,
                "release": {
                  "version": "$manifestVersion",
                  "minSupportedVersion": "$manifestMinSupported",
                  "releaseNotes": "e2e test build",
                  "installer": {
                    "url": "$installerUrl",
                    "sha256": "$sha",
                    "size": $size
                  }
                }
              },
              "errors": null
            }
        """.trimIndent()

        return HttpClient(MockEngine) {
            install(HttpTimeout)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("/app-updates/latest") -> respond(
                            content = ByteReadChannel(manifestBody),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                        path == msiPath -> respond(
                            content = ByteReadChannel(msiBytes),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, msiBytes.size.toString()),
                        )
                        else -> respond(
                            content = ByteReadChannel(""),
                            status = HttpStatusCode.NotFound,
                        )
                    }
                }
            }
        }
    }

    private fun manager(
        scope: CoroutineScope,
        client: HttpClient,
        installer: E2eRecordingInstaller,
        callStateFlow: MutableStateFlow<CallState>,
        currentVersion: String = "1.0.0",
    ): UpdateManager {
        val paths = UpdatePaths(rootOverride = tempRoot)
        val api = UpdateApi(client, baseUrlProvider = { "http://192.168.0.98:8080/api/v1/" })
        val downloader = UpdateDownloader(client, paths)
        return UpdateManager(
            scope = scope,
            api = api.asContract(),
            downloader = downloader.asContract(),
            installer = installer,
            paths = paths,
            callState = callStateFlow.asStateFlow(),
            currentVersion = currentVersion,
            channelProvider = { UpdateChannel.STABLE },
            installIdProvider = { "e2e-test-install-id" },
            pollIntervalMillis = 1_000_000L,
            exitProcess = { /* no-op for tests */ },
        )
    }

    /** Poll the state flow until [predicate] is true or a generous timeout is hit. */
    private suspend fun UpdateManager.awaitState(
        timeoutMs: Long = 10_000,
        label: String = "state",
        predicate: (UpdateState) -> Boolean,
    ): UpdateState = withTimeout(timeoutMs) {
        while (true) {
            val current = state.value
            if (predicate(current)) return@withTimeout current
            if (current is UpdateState.Failed) fail("unexpected Failed while awaiting $label: $current")
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE")
        state.value
    }

    @Test
    fun `e2e happy path — wrapped envelope, http url, allowlisted host, real download, SHA verify, install dispatched`() = runBlocking {
        val msiBytes = ByteArray(4096) { (it % 256).toByte() }
        val expectedSha = sha256Hex(msiBytes)

        val client = fakeBackend(
            manifestVersion = "1.0.2",
            manifestMinSupported = "1.0.0",
            msiBytes = msiBytes,
        )
        val installer = E2eRecordingInstaller()
        val callState = MutableStateFlow<CallState>(CallState.Idle)
        val mgr = manager(this, client, installer, callState, currentVersion = "1.0.1")

        mgr.checkNow()
        val state = mgr.awaitState(label = "ReadyToInstall") { it is UpdateState.ReadyToInstall }
            as UpdateState.ReadyToInstall

        assertEquals("1.0.2", state.release.version)
        assertEquals(expectedSha, state.release.installer.sha256)

        val msiOnDisk = Path.of(state.msiPath)
        assertEquals(true, msiOnDisk.exists(), "MSI must exist on disk")
        assertContentEquals(msiBytes, msiOnDisk.readBytes(), "MSI bytes must match what the fake server served")
        assertEquals(expectedSha, Sha256Verifier.compute(msiOnDisk), "SHA256 must match")

        mgr.confirmInstall()
        // Wait for installer to be invoked (up to 5s).
        withTimeout(5_000) {
            while (installer.installCount == 0) delay(20)
        }

        assertEquals(1, installer.installCount, "installer should have been called exactly once")
        assertEquals(msiOnDisk.toString(), installer.lastMsiPath, "installer should receive the verified MSI path")
        assertEquals(expectedSha, installer.lastExpectedSha256, "installer should receive the manifest's SHA256")

        client.close()
    }

    @Test
    fun `e2e install is deferred while a SIP call is active and proceeds when call ends`() = runBlocking {
        val msiBytes = ByteArray(2048) { (it % 256).toByte() }

        val client = fakeBackend(
            manifestVersion = "1.0.2",
            manifestMinSupported = "1.0.0",
            msiBytes = msiBytes,
        )
        val installer = E2eRecordingInstaller()
        val callState = MutableStateFlow<CallState>(CallState.Idle)
        val mgr = manager(this, client, installer, callState, currentVersion = "1.0.1")

        mgr.checkNow()
        mgr.awaitState(label = "ReadyToInstall") { it is UpdateState.ReadyToInstall }

        callState.value = CallState.Active(
            callId = "c1",
            remoteNumber = "+998901112233",
            remoteName = null,
            isOutbound = false,
            isMuted = false,
            isOnHold = false,
        )

        mgr.confirmInstall()
        delay(300) // give the manager a chance to run if it was going to install

        assertEquals(0, installer.installCount, "installer must NOT be called while a call is active (invariant I1)")

        callState.value = CallState.Idle
        withTimeout(5_000) {
            while (installer.installCount == 0) delay(20)
        }

        assertEquals(1, installer.installCount, "installer must fire immediately when call ends")

        client.close()
    }

    @Test
    fun `e2e refuses downgrade — manifest version equal to current keeps state Idle`() = runBlocking {
        val msiBytes = ByteArray(1024) { (it % 256).toByte() }
        val client = fakeBackend(
            manifestVersion = "1.0.1",
            manifestMinSupported = "1.0.0",
            msiBytes = msiBytes,
        )
        val installer = E2eRecordingInstaller()
        val callState = MutableStateFlow<CallState>(CallState.Idle)
        val mgr = manager(this, client, installer, callState, currentVersion = "1.0.1")

        mgr.checkNow()
        // Give the manager time to poll, validate, and settle back to Idle.
        delay(500)

        assertEquals(UpdateState.Idle, mgr.state.value)
        assertEquals(0, installer.installCount, "installer must not be called for same-version manifest")

        client.close()
    }
}

private class E2eRecordingInstaller : InstallerContract {
    var installCount = 0
        private set
    var lastMsiPath: String? = null
        private set
    var lastExpectedSha256: String? = null
        private set

    override fun install(msiPath: Path, expectedSha256: String, logPath: Path) {
        installCount++
        lastMsiPath = msiPath.toString()
        lastExpectedSha256 = expectedSha256
    }
}
