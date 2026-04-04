package uz.yalla.sipphone.feature.dialer

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.FakeCallEngine
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DialerComponentTest {

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
        registrationEngine: FakeRegistrationEngine = FakeRegistrationEngine().apply { simulateRegistered() },
        callEngine: FakeCallEngine = FakeCallEngine(),
        onDisconnected: () -> Unit = {},
    ): Triple<DialerComponent, FakeRegistrationEngine, FakeCallEngine> {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val component = DialerComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            registrationEngine = registrationEngine,
            callEngine = callEngine,
            onDisconnected = onDisconnected,
            ioDispatcher = testDispatcher,
        )
        return Triple(component, registrationEngine, callEngine)
    }

    @Test
    fun `makeCall delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        component.makeCall("+998901234567")
        advanceUntilIdle()
        assertEquals("+998901234567", callEngine.lastCallNumber)
    }

    @Test
    fun `answerCall delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateRinging("102", "Alex")
        component.answerCall()
        advanceUntilIdle()
        assertEquals(1, callEngine.answerCallCount)
    }

    @Test
    fun `hangupCall delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateActive()
        component.hangupCall()
        advanceUntilIdle()
        assertEquals(1, callEngine.hangupCallCount)
    }

    @Test
    fun `toggleMute delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateActive()
        component.toggleMute()
        advanceUntilIdle()
        assertEquals(1, callEngine.toggleMuteCount)
    }

    @Test
    fun `toggleHold delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateActive()
        component.toggleHold()
        advanceUntilIdle()
        assertEquals(1, callEngine.toggleHoldCount)
    }

    @Test
    fun `registration drop with no active call triggers onDisconnected`() = runTest {
        var disconnected = false
        val (_, regEngine, _) = createComponent(onDisconnected = { disconnected = true })
        regEngine.simulateFailed("timeout")
        advanceUntilIdle()
        assertEquals(true, disconnected)
    }

    @Test
    fun `registration drop during active call does NOT trigger onDisconnected`() = runTest {
        var disconnected = false
        val (_, regEngine, callEngine) = createComponent(onDisconnected = { disconnected = true })
        callEngine.simulateActive()
        advanceUntilIdle()
        regEngine.simulateFailed("timeout")
        advanceUntilIdle()
        assertEquals(false, disconnected)
    }

    @Test
    fun `callState exposes CallEngine state`() {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateRinging("102", "Alex")
        val state = component.callState.value
        assertIs<CallState.Ringing>(state)
        assertEquals("102", state.callerNumber)
    }

    @Test
    fun `disconnect delegates to RegistrationEngine`() = runTest {
        val (component, regEngine, _) = createComponent()
        component.disconnect()
        advanceUntilIdle()
        assertIs<RegistrationState.Idle>(regEngine.registrationState.value)
    }
}
