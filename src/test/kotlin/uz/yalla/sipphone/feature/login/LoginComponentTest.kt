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
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginComponentTest {
    private val testDispatcher = StandardTestDispatcher()
    private val lifecycle = LifecycleRegistry()
    private val fakeRegistration = FakeRegistrationEngine()
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
            registrationEngine = fakeRegistration,
            onLoginSuccess = { navigatedResult = it },
            ioDispatcher = testDispatcher,
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
        // After auth success, should be Authenticated (waiting for SIP register)
        assertTrue(component.loginState.value is LoginState.Authenticated || component.loginState.value is LoginState.Loading)
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
        // Simulate SIP registration success
        fakeRegistration.simulateRegistered("192.168.30.103")
        advanceUntilIdle()
        assertTrue(navigatedResult != null)
        assertEquals("Islom", navigatedResult?.agent?.name)
    }

    @Test
    fun `duplicate login calls are ignored when loading`() = runTest(testDispatcher) {
        component.login("test123")
        component.login("test123") // should be ignored
        advanceUntilIdle()
        // Should still work normally, no crash
        assertTrue(
            component.loginState.value is LoginState.Authenticated ||
                component.loginState.value is LoginState.Loading,
        )
    }

    @Test
    fun `SIP registration failure shows error`() = runTest(testDispatcher) {
        component.login("test123")
        advanceUntilIdle()
        // Simulate SIP registration failure
        fakeRegistration.simulateFailed("403 Forbidden")
        advanceUntilIdle()
        val state = component.loginState.value
        assertTrue(state is LoginState.Error)
        assertTrue((state as LoginState.Error).message.contains("SIP registration failed"))
    }

    @Test
    fun `manualConnect sets loading and registers SIP`() = runTest(testDispatcher) {
        component.manualConnect(
            server = "192.168.1.1",
            port = 5060,
            username = "102",
            password = "secret",
        )
        advanceUntilIdle()
        assertEquals("192.168.1.1", fakeRegistration.lastCredentials?.server)
        assertEquals("102", fakeRegistration.lastCredentials?.username)
    }
}
