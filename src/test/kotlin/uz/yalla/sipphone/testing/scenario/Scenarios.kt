package uz.yalla.sipphone.testing.scenario

import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pre-built operator simulation scenarios as extension functions
 * on [ScenarioRunner.ScenarioContext].
 *
 * These compose naturally inside [ScenarioRunner.run] blocks:
 * ```
 * runner.run {
 *     register()
 *     singleCall()
 *     busyOperatorDay()
 * }
 * ```
 */

// region Single-call scenarios

/**
 * One basic incoming call: ring -> answer -> talk -> hangup -> idle.
 */
suspend fun ScenarioRunner.ScenarioContext.singleCall(
    number: String = "1001",
    name: String? = "Test Caller",
) {
    incomingCall {
        ring(number, name = name, holdFor = 3.seconds)
        active(holdFor = 30.seconds)
        ending(holdFor = 1.seconds)
        idle()
    }
}

// endregion

// region Multi-call scenarios

/**
 * Simulates a busy operator day with 10-15 varied calls mixed
 * with realistic inter-call gaps.
 *
 * Uses [TrafficPattern] to pick scenario types and durations,
 * producing a reproducible sequence when a seeded [Random] is passed.
 */
suspend fun ScenarioRunner.ScenarioContext.busyOperatorDay(
    random: Random = Random.Default,
) {
    val traffic = TrafficPattern(random)
    val callCount = random.nextInt(10, 16)

    register()

    repeat(callCount) { i ->
        val scenarioType = traffic.nextScenarioType()
        val callerNumber = "${1000 + i}"
        val ringDuration = traffic.nextRingDuration()
        val talkDuration = traffic.nextTalkDuration()

        playScenarioType(scenarioType, callerNumber, ringDuration, talkDuration, traffic)

        if (i < callCount - 1) {
            pause(traffic.nextInterCallGap())
        }
    }
}

/**
 * Stress test: fire [count] random calls from [TrafficPattern] in rapid succession.
 * Useful for verifying state machine stability under load.
 */
suspend fun ScenarioRunner.ScenarioContext.stressTest(
    count: Int = 50,
    random: Random = Random.Default,
) {
    val traffic = TrafficPattern(random)

    register()

    repeat(count) { i ->
        val scenarioType = traffic.nextScenarioType()
        val callerNumber = "${2000 + i}"
        val ringDuration = traffic.nextRingDuration()
        val talkDuration = traffic.nextTalkDuration()

        playScenarioType(scenarioType, callerNumber, ringDuration, talkDuration, traffic)

        // Minimal gap for stress — BURST-like behavior
        pause(traffic.nextInterCallGap().coerceAtMost(500.milliseconds))
    }
}

/**
 * Network disruption scenario:
 * 1. Register and take a few calls
 * 2. Network drops mid-call
 * 3. Reconnect and resume taking calls
 */
suspend fun ScenarioRunner.ScenarioContext.networkDisruption(
    random: Random = Random.Default,
) {
    val traffic = TrafficPattern(random)

    // Phase 1: Normal operation
    register()
    repeat(3) { i ->
        incomingCall {
            ring("${3000 + i}", name = "Pre-disruption $i", holdFor = traffic.nextRingDuration())
            active(holdFor = traffic.nextTalkDuration())
            ending(holdFor = 500.milliseconds)
            idle()
        }
        pause(traffic.nextInterCallGap())
    }

    // Phase 2: Network disruption during a call
    incomingCall {
        ring("3100", name = "During Disruption", holdFor = 2.seconds)
        active(holdFor = 5.seconds)
        // Call drops — goes straight to idle (network cut)
        idle()
    }
    disconnect("Network timeout")
    pause(3.seconds) // offline period

    // Phase 3: Recovery
    register()
    repeat(3) { i ->
        incomingCall {
            ring("${3200 + i}", name = "Post-recovery $i", holdFor = traffic.nextRingDuration())
            active(holdFor = traffic.nextTalkDuration())
            ending(holdFor = 500.milliseconds)
            idle()
        }
        if (i < 2) pause(traffic.nextInterCallGap())
    }
}

// endregion

// region Legacy Scenarios object (used by DemoMain)

/**
 * Pre-built call scenarios as `List<ScenarioStep>` for direct use
 * with [ScriptableCallEngine.playScenario].
 *
 * For new tests prefer the [ScenarioRunner.ScenarioContext] extension functions above.
 */
object Scenarios {

    /** A single clean inbound call: ring -> answer -> talk -> hangup. */
    fun simpleInboundCall(
        number: String = "998901234567",
        name: String? = "Alisher",
        ringDuration: kotlin.time.Duration = 3.seconds,
        talkDuration: kotlin.time.Duration = 30.seconds,
    ): List<ScenarioStep> = callScenario {
        ring(number, name = name, holdFor = ringDuration)
        active(holdFor = talkDuration)
        ending(holdFor = 1.seconds)
        idle()
    }

    /** Full busy operator day: multiple calls, pauses, network gap. */
    fun busyOperatorDay(): List<ScenarioStep> = callScenario {
        // Call 1: Alisher calls in
        ring("998901234567", name = "Alisher", holdFor = 3.seconds)
        active(holdFor = 30.seconds)
        ending(holdFor = 1.seconds)
        idle(holdFor = 3.seconds)

        // Call 2: Missed call
        ring("998907654321", holdFor = 8.seconds)
        idle(holdFor = 4.seconds)

        // Call 3: Dilshod — with mute
        ring("998935551234", name = "Dilshod", holdFor = 3.seconds)
        active(holdFor = 10.seconds)
        mute(holdFor = 5.seconds)
        unmute(holdFor = 10.seconds)
        ending(holdFor = 1.seconds)
        idle(holdFor = 5.seconds)

        // Gap for network disconnect/reconnect (handled externally)
        idle(holdFor = 6.seconds)

        // Call 4: Outbound to Sardor — with hold
        ring("998909876543", name = "Sardor", outbound = true, holdFor = 4.seconds)
        active(holdFor = 15.seconds)
        hold(holdFor = 8.seconds)
        unhold(holdFor = 10.seconds)
        ending(holdFor = 1.seconds)
        idle(holdFor = 3.seconds)

        // Call 5: Quick confirmation from Bekzod
        ring("998712223344", name = "Bekzod", holdFor = 2.seconds)
        active(holdFor = 7.seconds)
        ending(holdFor = 1.seconds)
        idle(holdFor = 3.seconds)

        // Call 6: Complex call from Rustam
        ring("998946667788", name = "Rustam", holdFor = 3.seconds)
        active(holdFor = 12.seconds)
        mute(holdFor = 4.seconds)
        unmute(holdFor = 8.seconds)
        hold(holdFor = 6.seconds)
        unhold(holdFor = 15.seconds)
        ending(holdFor = 1.seconds)
        idle()
    }
}

// endregion

// region Internal helpers

/**
 * Dispatch a single call scenario based on [ScenarioType].
 */
private suspend fun ScenarioRunner.ScenarioContext.playScenarioType(
    type: ScenarioType,
    callerNumber: String,
    ringDuration: kotlin.time.Duration,
    talkDuration: kotlin.time.Duration,
    traffic: TrafficPattern,
) {
    when (type) {
        ScenarioType.NORMAL_AGENT_HANGUP -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            active(holdFor = talkDuration)
            ending(holdFor = 500.milliseconds)
            idle()
        }

        ScenarioType.NORMAL_CALLER_HANGUP -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            active(holdFor = talkDuration)
            ending(holdFor = 200.milliseconds)
            idle()
        }

        ScenarioType.CALLER_ABANDON -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            // Caller hangs up before agent answers
            ending(holdFor = 200.milliseconds)
            idle()
        }

        ScenarioType.HOLD_RESUME -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            active(holdFor = talkDuration.div(3))
            hold(holdFor = talkDuration.div(3))
            unhold(holdFor = talkDuration.div(3))
            ending(holdFor = 500.milliseconds)
            idle()
        }

        ScenarioType.MUTE_UNMUTE -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            active(holdFor = talkDuration.div(3))
            mute(holdFor = talkDuration.div(3))
            unmute(holdFor = talkDuration.div(3))
            ending(holdFor = 500.milliseconds)
            idle()
        }

        ScenarioType.TRANSFER -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            active(holdFor = talkDuration.div(2))
            // Transfer ends the call from this agent's perspective
            ending(holdFor = 500.milliseconds)
            idle()
        }

        ScenarioType.SHORT_CALL -> incomingCall {
            ring(callerNumber, holdFor = 1.seconds)
            active(holdFor = talkDuration.coerceAtMost(8.seconds))
            ending(holdFor = 200.milliseconds)
            idle()
        }

        ScenarioType.LONG_CALL -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            active(holdFor = talkDuration.coerceAtLeast(120.seconds))
            ending(holdFor = 500.milliseconds)
            idle()
        }

        ScenarioType.BUSY_CALLEE -> outboundCall(callerNumber) {
            ring(callerNumber, outbound = true, holdFor = 1.seconds)
            // 486 Busy Here — goes straight to idle
            ending(holdFor = 200.milliseconds)
            idle()
        }

        ScenarioType.NO_ANSWER_TIMEOUT -> incomingCall {
            ring(callerNumber, holdFor = 30.seconds)
            // Timeout — goes straight to idle
            idle()
        }

        ScenarioType.DTMF_NAVIGATION -> outboundCall(callerNumber) {
            ring(callerNumber, outbound = true, holdFor = ringDuration)
            active(holdFor = talkDuration)
            ending(holdFor = 500.milliseconds)
            idle()
        }

        ScenarioType.RAPID_FIRE -> {
            // Two quick calls back-to-back
            incomingCall {
                ring(callerNumber, holdFor = 1.seconds)
                active(holdFor = 5.seconds)
                ending(holdFor = 200.milliseconds)
                idle()
            }
            pause(200.milliseconds)
            incomingCall {
                ring("${callerNumber}b", holdFor = 1.seconds)
                active(holdFor = 5.seconds)
                ending(holdFor = 200.milliseconds)
                idle()
            }
        }

        ScenarioType.NETWORK_DROP -> incomingCall {
            ring(callerNumber, holdFor = ringDuration)
            active(holdFor = talkDuration.div(2))
            // Network drop — abrupt transition to idle
            idle()
        }
    }
}

// endregion
