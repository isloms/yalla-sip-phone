package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.data.auth.AuthApi
import uz.yalla.sipphone.data.auth.AuthEventBus
import uz.yalla.sipphone.data.auth.InMemoryTokenProvider
import uz.yalla.sipphone.data.auth.LogoutOrchestrator
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.data.update.DownloadResult
import uz.yalla.sipphone.data.update.InstallerContract
import uz.yalla.sipphone.data.update.UpdateApiContract
import uz.yalla.sipphone.data.update.UpdateCheckResult
import uz.yalla.sipphone.data.update.UpdateDownloaderContract
import uz.yalla.sipphone.data.update.UpdateManager
import uz.yalla.sipphone.data.update.UpdatePaths
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.FakeCallEngine
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent
import uz.yalla.sipphone.testing.FakeSipAccountManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RootComponentTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeSipAccountManager = FakeSipAccountManager()
    private val fakeCallEngine = FakeCallEngine()
    private val authEventBus = AuthEventBus()

    private val testAuthResult = AuthResult(
        token = "test-token",
        accounts = listOf(
            SipAccountInfo(
                extensionNumber = 102,
                serverUrl = "192.168.0.22",
                sipName = null,
                credentials = SipCredentials("192.168.0.22", 5060, "102", "pass"),
            ),
        ),
        dispatcherUrl = "http://dispatcher.test",
        agent = AgentInfo("1", "Test Agent"),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fakeAuthRepository = object : AuthRepository {
        override suspend fun login(pinCode: String): Result<AuthResult> =
            Result.success(testAuthResult)
        override suspend fun logout(): Result<Unit> = Result.success(Unit)
    }

    private val fakeAuthApi = AuthApi(
        client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
        authEventBus = authEventBus,
    )

    private fun stubUpdateManager(): UpdateManager {
        val tmpRoot = java.nio.file.Files.createTempDirectory("root-test-update").also {
            Runtime.getRuntime().addShutdownHook(Thread { it.toFile().deleteRecursively() })
        }
        val noopApi = object : UpdateApiContract {
            override suspend fun check(
                channel: UpdateChannel,
                currentVersion: String,
                installId: String,
                platform: String,
            ): UpdateCheckResult = UpdateCheckResult.NoUpdate
        }
        val noopDownloader = object : UpdateDownloaderContract {
            override suspend fun download(release: UpdateRelease): DownloadResult = DownloadResult.Failed(null)
        }
        val noopInstaller = object : InstallerContract {
            override fun install(
                msiPath: java.nio.file.Path,
                expectedSha256: String,
                logPath: java.nio.file.Path,
            ) = Unit
        }
        return UpdateManager(
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            api = noopApi,
            downloader = noopDownloader,
            installer = noopInstaller,
            paths = UpdatePaths(rootOverride = tmpRoot),
            callState = fakeCallEngine.callState,
            currentVersion = "1.0.0",
            channelProvider = { UpdateChannel.STABLE },
            installIdProvider = { "test-install-id" },
            exitProcess = { /* no-op */ },
        )
    }

    private val logoutOrchestrator = LogoutOrchestrator(
        sipAccountManager = fakeSipAccountManager,
        authApi = fakeAuthApi,
        tokenProvider = InMemoryTokenProvider(),
    )

    private fun createRoot(): RootComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val factory = object : ComponentFactory {
            override fun createLogin(
                context: com.arkivanov.decompose.ComponentContext,
                onLoginSuccess: (AuthResult) -> Unit,
            ) = LoginComponent(
                componentContext = context,
                authRepository = fakeAuthRepository,
                sipAccountManager = fakeSipAccountManager,
                onLoginSuccess = onLoginSuccess,
                ioDispatcher = testDispatcher,
                mainDispatcher = testDispatcher,
            )

            override fun createMain(
                context: com.arkivanov.decompose.ComponentContext,
                authResult: AuthResult,
                onLogout: () -> Unit,
            ) = MainComponent(
                componentContext = context,
                authResult = authResult,
                callEngine = fakeCallEngine,
                sipAccountManager = fakeSipAccountManager,
                jcefManager = JcefManager(),
                eventEmitter = BridgeEventEmitter(auditLog = BridgeAuditLog()),
                security = BridgeSecurity(),
                auditLog = BridgeAuditLog(),
                updateManager = stubUpdateManager(),
                onLogout = onLogout,
            )
        }
        return RootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            factory = factory,
            authEventBus = authEventBus,
            logoutOrchestrator = logoutOrchestrator,
        )
    }

    @Test
    fun `initial screen is Login`() {
        val root = createRoot()
        val activeChild = root.childStack.value.active.instance
        assertIs<RootComponent.Child.Login>(activeChild)
    }

    @Test
    fun `navigates to Main on login success`() {
        val root = createRoot()
        // Get LoginComponent and simulate successful login + SIP registration
        val loginChild = root.childStack.value.active.instance as RootComponent.Child.Login
        loginChild.component.manualConnect("192.168.0.22", 5060, "102", "pass")
        // FakeSipAccountManager auto-connects accounts on registerAll
        val activeChild = root.childStack.value.active.instance
        assertIs<RootComponent.Child.Main>(activeChild)
    }

    @Test
    fun `navigates back to Login on explicit logout`() {
        val root = createRoot()
        val loginChild = root.childStack.value.active.instance as RootComponent.Child.Login
        loginChild.component.manualConnect("192.168.0.22", 5060, "102", "pass")
        assertIs<RootComponent.Child.Main>(root.childStack.value.active.instance)

        val mainChild = root.childStack.value.active.instance as RootComponent.Child.Main
        mainChild.component.logout()
    }

    @Test
    fun `stays on Main when SIP disconnects`() {
        val root = createRoot()
        val loginChild = root.childStack.value.active.instance as RootComponent.Child.Login
        loginChild.component.manualConnect("192.168.0.22", 5060, "102", "pass")
        assertIs<RootComponent.Child.Main>(root.childStack.value.active.instance)

        val accountId = fakeSipAccountManager.accounts.value.firstOrNull()?.id ?: "102@192.168.0.22"
        fakeSipAccountManager.simulateAccountState(accountId, SipAccountState.Disconnected)
        assertIs<RootComponent.Child.Main>(root.childStack.value.active.instance)
    }
}
