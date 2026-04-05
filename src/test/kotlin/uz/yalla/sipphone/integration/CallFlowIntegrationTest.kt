package uz.yalla.sipphone.integration

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipError
import uz.yalla.sipphone.testing.engine.ScriptableCallEngine
import uz.yalla.sipphone.testing.engine.ScriptableRegistrationEngine
import uz.yalla.sipphone.testing.scenario.ScenarioRunner
import uz.yalla.sipphone.testing.scenario.callScenario
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests verifying call state machine transitions using
 * [ScriptableCallEngine] and Turbine's [test] for deterministic
 * Flow assertion under virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallFlowIntegrationTest {

    private val callEngine = ScriptableCallEngine()
    private val registrationEngine = ScriptableRegistrationEngine()
    private val runner = ScenarioRunner(callEngine, registrationEngine)

    // region Inbound call lifecycle

    @Test
    fun `inbound call emits Idle - Ringing - Active - Idle`() = runTest {
        val steps = callScenario {
            ring("1001", name = "Alisher", holdFor = 3.seconds)
            active(holdFor = 10.seconds)
            ending(holdFor = 1.seconds)
            idle()
        }

        callEngine.callState.test {
            // Initial state
            assertIs<CallState.Idle>(awaitItem())

            // Play the scenario in the background
            launch { callEngine.playScenario(steps) }

            // Ringing
            val ringing = awaitItem()
            assertIs<CallState.Ringing>(ringing)
            assertEquals("1001", ringing.callerNumber)
            assertEquals("Alisher", ringing.callerName)
            assertFalse(ringing.isOutbound)

            // Active
            val active = awaitItem()
            assertIs<CallState.Active>(active)
            assertEquals("1001", active.remoteNumber)
            assertFalse(active.isMuted)
            assertFalse(active.isOnHold)

            // Ending
            assertIs<CallState.Ending>(awaitItem())

            // Back to Idle
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `caller abandon emits Idle - Ringing - Idle (no Active)`() = runTest {
        val steps = callScenario {
            ring("1002", name = "Sardor", holdFor = 5.seconds)
            idle() // caller hung up before answer
        }

        callEngine.callState.test {
            assertIs<CallState.Idle>(awaitItem())

            launch { callEngine.playScenario(steps) }

            // Ringing
            val ringing = awaitItem()
            assertIs<CallState.Ringing>(ringing)
            assertEquals("1002", ringing.callerNumber)

            // Straight to Idle (no Active, no Ending)
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Hold / Mute cycles

    @Test
    fun `hold cycle emits Active - Active(onHold=true) - Active(onHold=false)`() = runTest {
        val steps = callScenario {
            ring("1003", holdFor = 1.seconds)
            active(holdFor = 5.seconds)
            hold(holdFor = 3.seconds)
            unhold(holdFor = 5.seconds)
            ending(holdFor = 1.seconds)
            idle()
        }

        callEngine.callState.test {
            assertIs<CallState.Idle>(awaitItem())

            launch { callEngine.playScenario(steps) }

            // Ringing
            assertIs<CallState.Ringing>(awaitItem())

            // Active (no hold)
            val active1 = awaitItem()
            assertIs<CallState.Active>(active1)
            assertFalse(active1.isOnHold)

            // Active (on hold)
            val held = awaitItem()
            assertIs<CallState.Active>(held)
            assertTrue(held.isOnHold)

            // Active (off hold)
            val unheld = awaitItem()
            assertIs<CallState.Active>(unheld)
            assertFalse(unheld.isOnHold)

            // Ending -> Idle
            assertIs<CallState.Ending>(awaitItem())
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mute cycle emits Active - Active(isMuted=true) - Active(isMuted=false)`() = runTest {
        val steps = callScenario {
            ring("1004", holdFor = 1.seconds)
            active(holdFor = 5.seconds)
            mute(holdFor = 3.seconds)
            unmute(holdFor = 5.seconds)
            ending(holdFor = 1.seconds)
            idle()
        }

        callEngine.callState.test {
            assertIs<CallState.Idle>(awaitItem())

            launch { callEngine.playScenario(steps) }

            // Ringing
            assertIs<CallState.Ringing>(awaitItem())

            // Active (not muted)
            val active1 = awaitItem()
            assertIs<CallState.Active>(active1)
            assertFalse(active1.isMuted)

            // Active (muted)
            val muted = awaitItem()
            assertIs<CallState.Active>(muted)
            assertTrue(muted.isMuted)

            // Active (unmuted)
            val unmuted = awaitItem()
            assertIs<CallState.Active>(unmuted)
            assertFalse(unmuted.isMuted)

            // Ending -> Idle
            assertIs<CallState.Ending>(awaitItem())
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Ring timeout

    @Test
    fun `ring timeout emits Ringing - Idle after delay`() = runTest {
        val ringTimeout = 30.seconds

        val steps = callScenario {
            ring("1005", holdFor = ringTimeout)
            idle() // timeout: no answer
        }

        callEngine.callState.test {
            assertIs<CallState.Idle>(awaitItem())

            launch { callEngine.playScenario(steps) }

            val ringing = awaitItem()
            assertIs<CallState.Ringing>(ringing)
            assertEquals("1005", ringing.callerNumber)

            // After the ring timeout, transitions to Idle
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Registration / Network

    @Test
    fun `network disconnect emits Registered - Failed`() = runTest {
        registrationEngine.emitRegistered("sip:102@192.168.0.22")

        registrationEngine.registrationState.test {
            // Already registered
            val registered = awaitItem()
            assertIs<RegistrationState.Registered>(registered)
            assertEquals("sip:102@192.168.0.22", registered.server)

            // Network disconnect
            registrationEngine.emitFailed(503, "Service Unavailable")

            val failed = awaitItem()
            assertIs<RegistrationState.Failed>(failed)
            assertIs<SipError.NetworkError>(failed.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reconnect after disconnect emits Failed - Registering - Registered`() = runTest {
        // Start in a registered state, then fail
        registrationEngine.emitRegistered("sip:102@192.168.0.22")

        registrationEngine.registrationState.test {
            assertIs<RegistrationState.Registered>(awaitItem())

            // Simulate network failure
            registrationEngine.emitFailed(503, "Service Unavailable")
            assertIs<RegistrationState.Failed>(awaitItem())

            // Simulate reconnection attempt
            registrationEngine.emit(RegistrationState.Registering)
            assertIs<RegistrationState.Registering>(awaitItem())

            // Successful re-registration
            registrationEngine.emitRegistered("sip:102@192.168.0.22")
            val recovered = awaitItem()
            assertIs<RegistrationState.Registered>(recovered)
            assertEquals("sip:102@192.168.0.22", recovered.server)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Concurrent call + registration

    @Test
    fun `call completes normally while registered`() = runTest {
        registrationEngine.emitRegistered("sip:102@192.168.0.22")

        val steps = callScenario {
            ring("9001", name = "Bekzod", holdFor = 2.seconds)
            active(holdFor = 15.seconds)
            ending(holdFor = 1.seconds)
            idle()
        }

        callEngine.callState.test {
            assertIs<CallState.Idle>(awaitItem())

            launch { callEngine.playScenario(steps) }

            val ringing = awaitItem()
            assertIs<CallState.Ringing>(ringing)
            assertEquals("Bekzod", ringing.callerName)

            assertIs<CallState.Active>(awaitItem())
            assertIs<CallState.Ending>(awaitItem())
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        // Verify registration stayed healthy throughout
        assertIs<RegistrationState.Registered>(registrationEngine.registrationState.value)
    }

    @Test
    fun `outbound call scenario tracks outbound flag`() = runTest {
        val steps = callScenario {
            ring("998901234567", name = "Client", outbound = true, holdFor = 4.seconds)
            active(holdFor = 20.seconds)
            ending(holdFor = 1.seconds)
            idle()
        }

        callEngine.callState.test {
            assertIs<CallState.Idle>(awaitItem())

            launch { callEngine.playScenario(steps) }

            val ringing = awaitItem()
            assertIs<CallState.Ringing>(ringing)
            assertTrue(ringing.isOutbound)
            assertEquals("998901234567", ringing.callerNumber)

            val active = awaitItem()
            assertIs<CallState.Active>(active)
            assertTrue(active.isOutbound)

            assertIs<CallState.Ending>(awaitItem())
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion
}
