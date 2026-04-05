package uz.yalla.sipphone.testing.scenario

import uz.yalla.sipphone.domain.CallState
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A single step in a call scenario: emit [state] and hold for [holdFor]
 * before advancing to the next step.
 */
data class ScenarioStep(
    val state: CallState,
    val holdFor: Duration = Duration.ZERO,
)

/**
 * DSL builder for constructing a sequence of [ScenarioStep]s that model
 * a realistic call lifecycle.
 *
 * Example:
 * ```
 * val steps = callScenario {
 *     ring("102", holdFor = 3.seconds)
 *     active("102", holdFor = 30.seconds)
 *     mute(holdFor = 5.seconds)
 *     unmute(holdFor = 10.seconds)
 *     ending(holdFor = 1.seconds)
 *     idle()
 * }
 * ```
 */
class CallScenarioBuilder {

    private val steps = mutableListOf<ScenarioStep>()
    private var currentCallId: String? = null
    private var lastActive: CallState.Active? = null

    fun ring(
        number: String,
        name: String? = null,
        outbound: Boolean = false,
        callId: String? = null,
        holdFor: Duration = Duration.ZERO,
    ) {
        val id = callId ?: currentCallId ?: generateCallId()
        currentCallId = id
        steps += ScenarioStep(
            state = CallState.Ringing(
                callId = id,
                callerNumber = number,
                callerName = name,
                isOutbound = outbound,
            ),
            holdFor = holdFor,
        )
    }

    fun active(
        number: String? = null,
        name: String? = null,
        outbound: Boolean? = null,
        muted: Boolean = false,
        onHold: Boolean = false,
        callId: String? = null,
        holdFor: Duration = Duration.ZERO,
    ) {
        val id = callId ?: currentCallId ?: generateCallId()
        currentCallId = id

        // Resolve defaults from the last ringing state if available
        val lastRinging = steps.lastOrNull()?.state as? CallState.Ringing
        val resolvedNumber = number ?: lastRinging?.callerNumber ?: "unknown"
        val resolvedName = name ?: lastRinging?.callerName
        val resolvedOutbound = outbound ?: lastRinging?.isOutbound ?: false

        val state = CallState.Active(
            callId = id,
            remoteNumber = resolvedNumber,
            remoteName = resolvedName,
            isOutbound = resolvedOutbound,
            isMuted = muted,
            isOnHold = onHold,
        )
        lastActive = state
        steps += ScenarioStep(state = state, holdFor = holdFor)
    }

    fun mute(holdFor: Duration = Duration.ZERO) {
        val base = requireLastActive("mute")
        val state = base.copy(isMuted = true)
        lastActive = state
        steps += ScenarioStep(state = state, holdFor = holdFor)
    }

    fun unmute(holdFor: Duration = Duration.ZERO) {
        val base = requireLastActive("unmute")
        val state = base.copy(isMuted = false)
        lastActive = state
        steps += ScenarioStep(state = state, holdFor = holdFor)
    }

    fun hold(holdFor: Duration = Duration.ZERO) {
        val base = requireLastActive("hold")
        val state = base.copy(isOnHold = true)
        lastActive = state
        steps += ScenarioStep(state = state, holdFor = holdFor)
    }

    fun unhold(holdFor: Duration = Duration.ZERO) {
        val base = requireLastActive("unhold")
        val state = base.copy(isOnHold = false)
        lastActive = state
        steps += ScenarioStep(state = state, holdFor = holdFor)
    }

    fun ending(holdFor: Duration = Duration.ZERO) {
        steps += ScenarioStep(state = CallState.Ending, holdFor = holdFor)
        lastActive = null
    }

    fun idle(holdFor: Duration = Duration.ZERO) {
        steps += ScenarioStep(state = CallState.Idle, holdFor = holdFor)
        currentCallId = null
        lastActive = null
    }

    fun build(): List<ScenarioStep> = steps.toList()

    private fun requireLastActive(operation: String): CallState.Active =
        lastActive ?: error(
            "Cannot $operation: no preceding active() step. " +
                "Add an active() step before calling $operation()."
        )

    private fun generateCallId(): String = UUID.randomUUID().toString().take(8)
}

/**
 * Build a call scenario using the [CallScenarioBuilder] DSL.
 */
fun callScenario(block: CallScenarioBuilder.() -> Unit): List<ScenarioStep> =
    CallScenarioBuilder().apply(block).build()
