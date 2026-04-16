package uz.yalla.sipphone.feature.login

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.data.auth.MockAuthRepository
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.feature.login.ManualAccountEntry
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.testing.FakeSipAccountManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginComponentTest {
    private val testDispatcher = StandardTestDispatcher()
    private val lifecycle = LifecycleRegistry()
    private val fakeSipAccountManager = FakeSipAccountManager()
    private val authRepo: AuthRepository = MockAuthRepository()
    private var navigatedResult: AuthResult? = null

    private lateinit var component: LoginComponent

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        lifecycle.onCreate()
        lifecycle.onStart()
        lifecycle.onResume()
        component = LoginComponent(
            componentContext = DefaultComponentContext(lifecycle),
            authRepository = authRepo,
            sipAccountManager = fakeSipAccountManager,
            appSettings = AppSettings(),
            onLoginSuccess = { navigatedResult = it },
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is idle`() {
        assertEquals(LoginState.Idle, component.loginState.value)
    }

    @Test
    fun `login with correct password transitions to loading then authenticated`() = runTest(testDispatcher) {
        component.login("test123")
        advanceUntilIdle()
        // After auth success, registerAll is called and FakeSipAccountManager auto-connects
        assertTrue(
            component.loginState.value is LoginState.Authenticated ||
                component.loginState.value is LoginState.Loading,
        )
    }

    @Test
    fun `login with wrong password shows error`() = runTest(testDispatcher) {
        component.login("wrong")
        advanceUntilIdle()
        val state = component.loginState.value
        assertTrue(state is LoginState.Error)
        assertTrue((state as LoginState.Error).message.contains("Invalid"))
    }

    @Test
    fun `successful SIP registration triggers navigation`() = runTest(testDispatcher) {
        component.login("test123")
        advanceUntilIdle()
        assertTrue(navigatedResult != null)
        assertEquals("Islom", navigatedResult?.agent?.name)
        assertEquals(1, fakeSipAccountManager.registerAllCallCount)
        assertEquals(1, fakeSipAccountManager.lastRegisteredAccounts.size)
        assertEquals(103, fakeSipAccountManager.lastRegisteredAccounts.first().extensionNumber)
    }

    @Test
    fun `duplicate login calls are ignored when loading`() = runTest(testDispatcher) {
        component.login("test123")
        component.login("test123") // should be ignored
        advanceUntilIdle()
        // Should still work normally, no crash
        assertEquals(1, fakeSipAccountManager.registerAllCallCount)
    }

    @Test
    fun `SIP registration failure shows error`() = runTest(testDispatcher) {
        fakeSipAccountManager.registerAllResult = Result.failure(RuntimeException("403 Forbidden"))
        component.login("test123")
        advanceUntilIdle()
        val state = component.loginState.value
        assertTrue(state is LoginState.Error)
        assertTrue((state as LoginState.Error).message.contains("SIP registration failed"))
    }

    @Test
    fun `manualConnect registers SIP accounts`() = runTest(testDispatcher) {
        val accounts = listOf(
            ManualAccountEntry("192.168.1.1", 5060, "102", "secret"),
        )
        component.manualConnect(accounts)
        advanceUntilIdle()
        assertEquals(1, fakeSipAccountManager.registerAllCallCount)
        val registered = fakeSipAccountManager.lastRegisteredAccounts
        assertEquals(1, registered.size)
        assertEquals("192.168.1.1", registered.first().serverUrl)
        assertEquals(102, registered.first().extensionNumber)
        assertEquals("102", registered.first().credentials.username)
    }

    @Test
    fun `manualConnect with multiple accounts registers all`() = runTest(testDispatcher) {
        val accounts = listOf(
            ManualAccountEntry("192.168.0.22", 5060, "102", "pass1"),
            ManualAccountEntry("192.168.0.22", 5060, "103", "pass2"),
            ManualAccountEntry("10.0.0.5", 5060, "200", "pass3"),
        )
        component.manualConnect(accounts, "http://localhost:5173")
        advanceUntilIdle()
        assertEquals(1, fakeSipAccountManager.registerAllCallCount)
        val registered = fakeSipAccountManager.lastRegisteredAccounts
        assertEquals(3, registered.size)
        assertEquals("192.168.0.22", registered[0].serverUrl)
        assertEquals(102, registered[0].extensionNumber)
        assertEquals("10.0.0.5", registered[2].serverUrl)
        assertEquals(200, registered[2].extensionNumber)
    }
}
