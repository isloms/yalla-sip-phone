package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.FakeCallEngine
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RootComponentTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeRegistrationEngine = FakeRegistrationEngine()
    private val fakeCallEngine = FakeCallEngine()

    private val testAuthResult = AuthResult(
        sipCredentials = SipCredentials("192.168.0.22", 5060, "102", "pass"),
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

    private fun createRoot(): RootComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val factory = object : ComponentFactory {
            override fun createRegistration(
                context: com.arkivanov.decompose.ComponentContext,
                onRegistered: () -> Unit,
            ) = RegistrationComponent(
                componentContext = context,
                sipEngine = fakeRegistrationEngine,
                appSettings = AppSettings(),
                onRegistered = onRegistered,
                ioDispatcher = testDispatcher,
            )

            override fun createDialer(
                context: com.arkivanov.decompose.ComponentContext,
                onDisconnected: () -> Unit,
            ) = DialerComponent(
                componentContext = context,
                registrationEngine = fakeRegistrationEngine,
                callEngine = fakeCallEngine,
                onDisconnected = onDisconnected,
                ioDispatcher = testDispatcher,
            )

            override fun createLogin(
                context: com.arkivanov.decompose.ComponentContext,
                onLoginSuccess: (AuthResult) -> Unit,
            ) = LoginComponent(
                componentContext = context,
                authRepository = object : AuthRepository {
                    override suspend fun login(password: String): Result<AuthResult> =
                        Result.success(testAuthResult)
                },
                registrationEngine = fakeRegistrationEngine,
                onLoginSuccess = onLoginSuccess,
                ioDispatcher = testDispatcher,
            )

            override fun createMain(
                context: com.arkivanov.decompose.ComponentContext,
                authResult: AuthResult,
                onLogout: () -> Unit,
            ) = MainComponent(
                componentContext = context,
                authResult = authResult,
                callEngine = fakeCallEngine,
                registrationEngine = fakeRegistrationEngine,
                jcefManager = JcefManager(),
                eventEmitter = BridgeEventEmitter(auditLog = BridgeAuditLog()),
                onLogout = onLogout,
            )
        }
        return RootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            factory = factory,
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
        fakeRegistrationEngine.simulateRegistered()
        val activeChild = root.childStack.value.active.instance
        assertIs<RootComponent.Child.Main>(activeChild)
    }

    @Test
    fun `navigates back to Login on logout from Main`() {
        val root = createRoot()
        // Navigate to Main
        val loginChild = root.childStack.value.active.instance as RootComponent.Child.Login
        loginChild.component.manualConnect("192.168.0.22", 5060, "102", "pass")
        fakeRegistrationEngine.simulateRegistered()
        assertIs<RootComponent.Child.Main>(root.childStack.value.active.instance)

        // Simulate disconnect (unregister sets state to Idle, auto-logout triggers)
        fakeRegistrationEngine.simulateFailed("timeout")
        val activeChild = root.childStack.value.active.instance
        assertIs<RootComponent.Child.Login>(activeChild)
    }
}
