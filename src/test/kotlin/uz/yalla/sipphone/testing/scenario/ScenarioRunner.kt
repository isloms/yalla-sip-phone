package uz.yalla.sipphone.testing.scenario

import kotlinx.coroutines.delay
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipError
import uz.yalla.sipphone.testing.engine.ScriptableCallEngine
import uz.yalla.sipphone.testing.engine.ScriptableRegistrationEngine
import kotlin.time.Duration

/**
 * Orchestrates multi-step operator simulation scenarios by coordinating
 * [ScriptableCallEngine] and [ScriptableRegistrationEngine].
 *
 * Example:
 * ```
 * val runner = ScenarioRunner(callEngine, registrationEngine)
 * runner.run {
 *     register("sip:102@192.168.0.22")
 *     incomingCall {
 *         ring("1001", holdFor = 3.seconds)
 *         active(holdFor = 30.seconds)
 *         ending(holdFor = 1.seconds)
 *         idle()
 *     }
 *     disconnect("Network timeout")
 * }
 * ```
 */
class ScenarioRunner(
    val callEngine: ScriptableCallEngine,
    val registrationEngine: ScriptableRegistrationEngine,
) {

    inner class ScenarioContext {

        /**
         * Simulate successful SIP registration.
         */
        suspend fun register(server: String = "sip:102@192.168.0.22") {
            registrationEngine.emit(RegistrationState.Registering)
            delay(50) // realistic tiny delay
            registrationEngine.emitRegistered(server)
        }

        /**
         * Simulate registration failure.
         */
        suspend fun registerFailed(code: Int = 403, reason: String = "Forbidden") {
            registrationEngine.emit(RegistrationState.Registering)
            delay(50)
            registrationEngine.emitFailed(code, reason)
        }

        /**
         * Simulate a network disconnect or server-side unregister.
         */
        suspend fun disconnect(reason: String = "Network timeout") {
            registrationEngine.emit(
                RegistrationState.Failed(
                    SipError.NetworkError(Exception(reason))
                )
            )
            delay(50)
            registrationEngine.emitDisconnected()
        }

        /**
         * Pause the scenario for a specified duration.
         * Useful for simulating inter-call gaps.
         */
        suspend fun pause(duration: Duration) {
            delay(duration)
        }

        /**
         * Play an incoming call scenario through the call engine.
         */
        suspend fun incomingCall(block: CallScenarioBuilder.() -> Unit) {
            val steps = callScenario(block)
            callEngine.playScenario(steps)
        }

        /**
         * Play an outbound call scenario through the call engine.
         * Automatically sets outbound=true on the first ring step if not specified.
         */
        suspend fun outboundCall(number: String, block: CallScenarioBuilder.() -> Unit) {
            val builder = CallScenarioBuilder()
            builder.block()
            val steps = builder.build().mapIndexed { index, step ->
                if (index == 0 && step.state is uz.yalla.sipphone.domain.CallState.Ringing) {
                    val ringing = step.state
                    step.copy(
                        state = ringing.copy(
                            callerNumber = number,
                            isOutbound = true,
                        )
                    )
                } else {
                    step
                }
            }
            callEngine.playScenario(steps)
        }
    }

    /**
     * Execute a scenario within the [ScenarioContext] DSL.
     */
    suspend fun run(block: suspend ScenarioContext.() -> Unit) {
        ScenarioContext().block()
    }
}
