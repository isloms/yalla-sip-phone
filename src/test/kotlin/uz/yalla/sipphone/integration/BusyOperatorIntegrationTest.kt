package uz.yalla.sipphone.integration

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.testing.engine.ScriptableCallEngine
import uz.yalla.sipphone.testing.engine.ScriptableRegistrationEngine
import uz.yalla.sipphone.testing.scenario.ScenarioRunner
import uz.yalla.sipphone.testing.scenario.busyOperatorDay
import uz.yalla.sipphone.testing.scenario.networkDisruption
import uz.yalla.sipphone.testing.scenario.stressTest
import uz.yalla.sipphone.testing.scenario.callScenario
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Full operator simulation integration tests.
 *
 * These exercise realistic multi-call sequences through the
 * [ScriptableCallEngine] and verify that the state machine never
 * enters an invalid state across many transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusyOperatorIntegrationTest {

    private val callEngine = ScriptableCallEngine()
    private val registrationEngine = ScriptableRegistrationEngine()
    private val runner = ScenarioRunner(callEngine, registrationEngine)

    /**
     * Allowed state transitions for call state.
     * Any transition not in this map is a state machine error.
     */
    private val allowedCallTransitions: Map<String, Set<String>> = mapOf(
        "Idle" to setOf("Ringing"),
        "Ringing" to setOf("Active", "Ending", "Idle"),
        "Active" to setOf("Active", "Ending", "Idle"),
        "Ending" to setOf("Idle"),
    )

    /**
     * Returns a simplified state name for transition validation.
     */
    private fun CallState.simpleName(): String = when (this) {
        is CallState.Idle -> "Idle"
        is CallState.Ringing -> "Ringing"
        is CallState.Active -> "Active"
        is CallState.Ending -> "Ending"
    }

    /**
     * Validate that a state transition is legal.
     */
    private fun validateTransition(from: CallState, to: CallState): Boolean {
        val fromName = from.simpleName()
        val toName = to.simpleName()
        // Idle -> Idle is valid (wrap-up gap between calls)
        if (fromName == toName && fromName == "Idle") return true
        return allowedCallTransitions[fromName]?.contains(toName) == true
    }

    // region Busy operator day

    @Test
    fun `busy operator day completes all calls without state machine errors`() = runTest {
        val seed = 42L
        val random = Random(seed)
        val stats = CallStats()

        callEngine.callState.test {
            // Initial idle
            val initial = awaitItem()
            assertIs<CallState.Idle>(initial)

            // Launch the scenario
            launch {
                runner.run { busyOperatorDay(random) }
            }

            // Consume all state transitions, validating each one
            var previous: CallState = initial
            var transitionCount = 0
            val maxTransitions = 500 // safety bound

            while (transitionCount < maxTransitions) {
                val next = try {
                    awaitItem()
                } catch (_: Throwable) {
                    break
                }

                val isValid = validateTransition(previous, next)
                if (!isValid) {
                    fail(
                        "Invalid state transition #$transitionCount: " +
                            "${previous.simpleName()} -> ${next.simpleName()}\n" +
                            "Previous: $previous\n" +
                            "Next: $next"
                    )
                }

                stats.recordTransition(previous, next)
                previous = next
                transitionCount++
            }

            // After scenario, we should be back at Idle
            assertIs<CallState.Idle>(previous)

            stats.print("Busy Operator Day (seed=$seed)")

            // Verify meaningful work was done
            assertTrue(stats.totalCalls > 0, "Expected at least 1 call, got ${stats.totalCalls}")
            assertTrue(
                stats.totalTransitions > 10,
                "Expected at least 10 transitions, got ${stats.totalTransitions}"
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Stress test

    @Test
    fun `stress test 50 random calls maintains consistent state`() = runTest {
        val seed = 123L
        val random = Random(seed)
        val stats = CallStats()

        callEngine.callState.test {
            val initial = awaitItem()
            assertIs<CallState.Idle>(initial)

            launch {
                runner.run { stressTest(count = 50, random = random) }
            }

            var previous: CallState = initial
            var transitionCount = 0
            val maxTransitions = 1500 // 50 calls can have many transitions

            while (transitionCount < maxTransitions) {
                val next = try {
                    awaitItem()
                } catch (_: Throwable) {
                    break
                }

                val isValid = validateTransition(previous, next)
                if (!isValid) {
                    fail(
                        "Invalid transition at step $transitionCount: " +
                            "${previous.simpleName()} -> ${next.simpleName()}\n" +
                            "Previous: $previous\nNext: $next"
                    )
                }

                stats.recordTransition(previous, next)
                previous = next
                transitionCount++
            }

            assertIs<CallState.Idle>(previous)

            stats.print("Stress Test 50 Calls (seed=$seed)")

            assertTrue(stats.totalCalls >= 50, "Expected at least 50 calls, got ${stats.totalCalls}")

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Burst pattern

    @Test
    fun `burst pattern handles rapid sequential calls`() = runTest {
        val stats = CallStats()

        // 5 rapid-fire calls with minimal gaps
        val burstScenarios = (1..5).map { i ->
            callScenario {
                ring("${5000 + i}", holdFor = 500.milliseconds)
                active(holdFor = 2.seconds)
                ending(holdFor = 200.milliseconds)
                idle()
            }
        }

        callEngine.callState.test {
            val initial = awaitItem()
            assertIs<CallState.Idle>(initial)

            launch {
                for (steps in burstScenarios) {
                    callEngine.playScenario(steps)
                    // Tiny gap between calls — no idle padding
                }
            }

            var previous: CallState = initial
            var transitionCount = 0
            val maxTransitions = 100

            while (transitionCount < maxTransitions) {
                val next = try {
                    awaitItem()
                } catch (_: Throwable) {
                    break
                }

                val isValid = validateTransition(previous, next)
                if (!isValid) {
                    fail(
                        "Invalid transition in burst at step $transitionCount: " +
                            "${previous.simpleName()} -> ${next.simpleName()}"
                    )
                }

                stats.recordTransition(previous, next)
                previous = next
                transitionCount++
            }

            assertIs<CallState.Idle>(previous)

            stats.print("Burst Pattern (5 rapid calls)")

            assertEquals(5, stats.totalCalls, "Expected 5 completed calls")

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Network disruption

    @Test
    fun `network disruption mid-shift recovers gracefully`() = runTest {
        val seed = 777L
        val random = Random(seed)
        val callStats = CallStats()
        val regStats = RegistrationStats()

        // Observe registration transitions in parallel
        val regJob = launch {
            var prevReg: RegistrationState = RegistrationState.Idle
            registrationEngine.registrationState.collect { newState ->
                regStats.recordTransition(prevReg, newState)
                prevReg = newState
            }
        }

        callEngine.callState.test {
            val initial = awaitItem()
            assertIs<CallState.Idle>(initial)

            launch {
                runner.run { networkDisruption(random) }
            }

            var previous: CallState = initial
            var transitionCount = 0
            val maxTransitions = 300

            while (transitionCount < maxTransitions) {
                val next = try {
                    awaitItem()
                } catch (_: Throwable) {
                    break
                }

                val isValid = validateTransition(previous, next)
                if (!isValid) {
                    fail(
                        "Invalid transition during network disruption at step $transitionCount: " +
                            "${previous.simpleName()} -> ${next.simpleName()}\n" +
                            "Previous: $previous\nNext: $next"
                    )
                }

                callStats.recordTransition(previous, next)
                previous = next
                transitionCount++
            }

            assertIs<CallState.Idle>(previous)

            regJob.cancel()

            callStats.print("Network Disruption (seed=$seed)")
            regStats.print()

            // Verify calls happened both before and after disruption
            assertTrue(callStats.totalCalls >= 6, "Expected at least 6 calls (3 pre + 1 during + 3 post)")

            // Verify registration went through a disconnect/reconnect cycle
            assertTrue(regStats.disconnects > 0, "Expected at least 1 disconnect")
            assertTrue(regStats.reconnects > 0, "Expected at least 1 reconnect")

            // Final registration state should be Registered (recovered)
            assertIs<RegistrationState.Registered>(registrationEngine.registrationState.value)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Combined mute + hold

    @Test
    fun `complex call with mute and hold cycles maintains state integrity`() = runTest {
        val steps = callScenario {
            ring("8001", name = "Rustam", holdFor = 2.seconds)
            active(holdFor = 5.seconds)
            mute(holdFor = 3.seconds)
            hold(holdFor = 4.seconds)
            unhold(holdFor = 2.seconds)
            unmute(holdFor = 3.seconds)
            ending(holdFor = 1.seconds)
            idle()
        }

        callEngine.callState.test {
            assertIs<CallState.Idle>(awaitItem())

            launch { callEngine.playScenario(steps) }

            // Ringing
            assertIs<CallState.Ringing>(awaitItem())

            // Active (clean)
            val a1 = awaitItem()
            assertIs<CallState.Active>(a1)
            assertTrue(!a1.isMuted && !a1.isOnHold)

            // Active (muted)
            val a2 = awaitItem()
            assertIs<CallState.Active>(a2)
            assertTrue(a2.isMuted && !a2.isOnHold)

            // Active (muted + on hold)
            val a3 = awaitItem()
            assertIs<CallState.Active>(a3)
            assertTrue(a3.isMuted && a3.isOnHold)

            // Active (muted + off hold)
            val a4 = awaitItem()
            assertIs<CallState.Active>(a4)
            assertTrue(a4.isMuted && !a4.isOnHold)

            // Active (unmuted + off hold)
            val a5 = awaitItem()
            assertIs<CallState.Active>(a5)
            assertTrue(!a5.isMuted && !a5.isOnHold)

            // Ending -> Idle
            assertIs<CallState.Ending>(awaitItem())
            assertIs<CallState.Idle>(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion
}

// region Statistics helpers

/**
 * Tracks call statistics during scenario playback for test reporting.
 */
private class CallStats {
    var totalCalls = 0
        private set
    var totalTransitions = 0
        private set
    var missedCalls = 0
        private set
    var holdCycles = 0
        private set
    var muteCycles = 0
        private set
    var maxConsecutiveActiveTransitions = 0
        private set

    private var consecutiveActiveTransitions = 0
    private val transitionCounts = mutableMapOf<String, Int>()

    fun recordTransition(from: CallState, to: CallState) {
        totalTransitions++
        val key = "${from.simpleName()} -> ${to.simpleName()}"
        transitionCounts[key] = (transitionCounts[key] ?: 0) + 1

        // Count completed calls (any state -> Idle, except Idle -> Idle)
        if (to is CallState.Idle && from !is CallState.Idle) {
            totalCalls++
        }

        // Count missed calls (Ringing -> Idle, no Active in between)
        if (from is CallState.Ringing && to is CallState.Idle) {
            missedCalls++
        }

        // Count hold cycles
        if (from is CallState.Active && to is CallState.Active) {
            if (!from.isOnHold && to.isOnHold) holdCycles++
            if (!from.isMuted && to.isMuted) muteCycles++

            consecutiveActiveTransitions++
            if (consecutiveActiveTransitions > maxConsecutiveActiveTransitions) {
                maxConsecutiveActiveTransitions = consecutiveActiveTransitions
            }
        } else {
            consecutiveActiveTransitions = 0
        }
    }

    private fun CallState.simpleName(): String = when (this) {
        is CallState.Idle -> "Idle"
        is CallState.Ringing -> "Ringing"
        is CallState.Active -> "Active"
        is CallState.Ending -> "Ending"
    }

    fun print(label: String) {
        println()
        println("--- $label ---")
        println("  Total calls completed: $totalCalls")
        println("  Missed calls: $missedCalls")
        println("  Hold cycles: $holdCycles")
        println("  Mute cycles: $muteCycles")
        println("  Total transitions: $totalTransitions")
        println("  Max consecutive Active->Active: $maxConsecutiveActiveTransitions")
        println("  Transition breakdown:")
        transitionCounts.entries.sortedByDescending { it.value }.forEach { (key, count) ->
            println("    $key: $count")
        }
        println()
    }
}

/**
 * Tracks registration state transitions for network disruption tests.
 */
private class RegistrationStats {
    var disconnects = 0
        private set
    var reconnects = 0
        private set
    var totalTransitions = 0
        private set

    private val transitions = mutableListOf<Pair<String, String>>()

    fun recordTransition(from: RegistrationState, to: RegistrationState) {
        totalTransitions++
        val fromName = from.simpleName()
        val toName = to.simpleName()
        transitions += fromName to toName

        // Count disconnects (Registered -> Failed, or Registered -> Idle)
        if (from is RegistrationState.Registered &&
            (to is RegistrationState.Failed || to is RegistrationState.Idle)
        ) {
            disconnects++
        }

        // Count reconnects (any non-Registered -> Registered)
        if (from !is RegistrationState.Registered && to is RegistrationState.Registered) {
            reconnects++
        }
    }

    private fun RegistrationState.simpleName(): String = when (this) {
        is RegistrationState.Idle -> "Idle"
        is RegistrationState.Registering -> "Registering"
        is RegistrationState.Registered -> "Registered"
        is RegistrationState.Failed -> "Failed"
    }

    fun print() {
        println("  Registration stats:")
        println("    Total transitions: $totalTransitions")
        println("    Disconnects: $disconnects")
        println("    Reconnects: $reconnects")
        println("    Transition sequence: ${transitions.joinToString(" -> ") { "${it.first}>${it.second}" }}")
        println()
    }
}

// endregion
