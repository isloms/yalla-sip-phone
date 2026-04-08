package uz.yalla.sipphone.testing.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.testing.scenario.ScenarioStep

/**
 * Test-double [CallEngine] for operator simulation tests.
 *
 * Records every action dispatched by production code into [actions], which tests can
 * assert against. Exposes [emit] for instant state injection and [playScenario] for
 * time-based scenario playback.
 *
 * Configurable outcomes: set [makeCallResult], [sendDtmfResult], or [transferCallResult]
 * before calling to simulate failure paths.
 *
 * Example:
 * ```kotlin
 * val engine = ScriptableCallEngine()
 * engine.emit(CallState.Ringing(callId = "abc", callerNumber = "102", ...))
 * assertThat(engine.callState.value).isInstanceOf(CallState.Ringing::class.java)
 * ```
 */
class ScriptableCallEngine(
    initialState: CallState = CallState.Idle,
    var makeCallResult: Result<Unit> = Result.success(Unit),
    var sendDtmfResult: Result<Unit> = Result.success(Unit),
    var transferCallResult: Result<Unit> = Result.success(Unit),
) : CallEngine {

    // region State
    private val _callState = MutableStateFlow(initialState)
    override val callState = _callState.asStateFlow()
    // endregion

    // region Action recording
    sealed interface Action {
        data class MakeCall(val number: String, val accountId: String = "") : Action
        data object AnswerCall : Action
        data object HangupCall : Action
        data object ToggleMute : Action
        data object ToggleHold : Action
        data class SetMute(val callId: String, val muted: Boolean) : Action
        data class SetHold(val callId: String, val onHold: Boolean) : Action
        data class SendDtmf(val callId: String, val digits: String) : Action
        data class TransferCall(val callId: String, val destination: String) : Action
    }

    private val _actions = mutableListOf<Action>()
    val actions: List<Action> get() = _actions.toList()

    fun clearActions() {
        _actions.clear()
    }
    // endregion

    // region CallEngine implementation
    override suspend fun makeCall(number: String, accountId: String): Result<Unit> {
        _actions += Action.MakeCall(number, accountId)
        return makeCallResult
    }

    override suspend fun answerCall() {
        _actions += Action.AnswerCall
    }

    override suspend fun hangupCall() {
        _actions += Action.HangupCall
    }

    override suspend fun toggleMute() {
        _actions += Action.ToggleMute
    }

    override suspend fun toggleHold() {
        _actions += Action.ToggleHold
    }

    override suspend fun setMute(callId: String, muted: Boolean) {
        _actions += Action.SetMute(callId, muted)
        val state = _callState.value
        if (state is CallState.Active && state.callId == callId) {
            _callState.value = state.copy(isMuted = muted)
        }
    }

    override suspend fun setHold(callId: String, onHold: Boolean) {
        _actions += Action.SetHold(callId, onHold)
        val state = _callState.value
        if (state is CallState.Active && state.callId == callId) {
            _callState.value = state.copy(isOnHold = onHold)
        }
    }

    override suspend fun sendDtmf(callId: String, digits: String): Result<Unit> {
        _actions += Action.SendDtmf(callId, digits)
        return sendDtmfResult
    }

    override suspend fun transferCall(callId: String, destination: String): Result<Unit> {
        _actions += Action.TransferCall(callId, destination)
        return transferCallResult
    }
    // endregion

    // region Test control
    /**
     * Directly set the call state (no delay).
     */
    fun emit(state: CallState) {
        _callState.value = state
    }

    /**
     * Play a sequence of [ScenarioStep]s, emitting each state and
     * holding for the specified duration before advancing.
     */
    suspend fun playScenario(steps: List<ScenarioStep>) {
        for (step in steps) {
            _callState.value = step.state
            if (step.holdFor.isPositive()) {
                delay(step.holdFor)
            }
        }
    }
    // endregion
}
