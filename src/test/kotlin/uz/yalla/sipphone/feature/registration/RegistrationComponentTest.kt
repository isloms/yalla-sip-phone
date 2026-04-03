package uz.yalla.sipphone.feature.registration

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.FakeSipEngine
import uz.yalla.sipphone.domain.SipCredentials
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RegistrationComponentTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createComponent(
        sipEngine: FakeSipEngine = FakeSipEngine(),
        appSettings: AppSettings = AppSettings(),
        onRegistered: () -> Unit = {},
    ): Pair<RegistrationComponent, FakeSipEngine> {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val context = DefaultComponentContext(lifecycle = lifecycle)
        val component = RegistrationComponent(
            componentContext = context,
            sipEngine = sipEngine,
            appSettings = appSettings,
            onRegistered = onRegistered,
            ioDispatcher = testDispatcher,
        )
        return component to sipEngine
    }

    @Test
    fun `connect calls register on SipEngine`() = runTest {
        val (component, engine) = createComponent()
        val credentials = SipCredentials("192.168.0.22", 5060, "102", "pass")

        component.connect(credentials)
        advanceUntilIdle()

        assertNotNull(engine.lastCredentials)
        assertEquals("102", engine.lastCredentials?.username)
    }

    @Test
    fun `onRegistered fires once on Registered state`() = runTest {
        var registeredCount = 0
        val engine = FakeSipEngine()
        val (_, _) = createComponent(sipEngine = engine, onRegistered = { registeredCount++ })
        advanceUntilIdle()

        engine.simulateRegistered()
        advanceUntilIdle()

        assertEquals(1, registeredCount)
    }

    @Test
    fun `cancelRegistration calls unregister`() = runTest {
        val (component, engine) = createComponent()
        engine.simulateRegistered()

        component.cancelRegistration()
        advanceUntilIdle()

        assertIs<uz.yalla.sipphone.domain.RegistrationState.Idle>(engine.registrationState.value)
    }

    @Test
    fun `updateFormState updates form`() {
        val (component, _) = createComponent()
        val newState = FormState(server = "10.0.0.1", port = "5080", username = "alice", password = "secret")

        component.updateFormState(newState)

        assertEquals("10.0.0.1", component.formState.value.server)
        assertEquals("5080", component.formState.value.port)
        assertEquals("alice", component.formState.value.username)
    }
}
