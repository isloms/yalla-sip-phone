package uz.yalla.sipphone.testing.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipError

/**
 * A scriptable [RegistrationEngine] for real-world operator simulation tests.
 *
 * Records every action dispatched by production code, and exposes
 * [emit] for driving registration state from the test side.
 */
class ScriptableRegistrationEngine(
    initialState: RegistrationState = RegistrationState.Idle,
    var registerResult: Result<Unit> = Result.success(Unit),
) : RegistrationEngine {

    // region State
    private val _registrationState = MutableStateFlow(initialState)
    override val registrationState = _registrationState.asStateFlow()
    // endregion

    // region Action recording
    sealed interface Action {
        data class Register(val credentials: SipCredentials) : Action
        data object Unregister : Action
    }

    private val _actions = mutableListOf<Action>()
    val actions: List<Action> get() = _actions.toList()

    fun clearActions() {
        _actions.clear()
    }
    // endregion

    // region RegistrationEngine implementation
    override suspend fun register(credentials: SipCredentials): Result<Unit> {
        _actions += Action.Register(credentials)
        if (registerResult.isSuccess) {
            _registrationState.value = RegistrationState.Registering
        }
        return registerResult
    }

    override suspend fun unregister() {
        _actions += Action.Unregister
        _registrationState.value = RegistrationState.Idle
    }
    // endregion

    // region Test control
    /**
     * Directly set the registration state.
     */
    fun emit(state: RegistrationState) {
        _registrationState.value = state
    }

    /** Convenience: transition to [RegistrationState.Registered]. */
    fun emitRegistered(server: String = "sip:102@192.168.0.22") {
        emit(RegistrationState.Registered(server))
    }

    /** Convenience: transition to [RegistrationState.Failed]. */
    fun emitFailed(code: Int = 403, reason: String = "Forbidden") {
        emit(RegistrationState.Failed(SipError.fromSipStatus(code, reason)))
    }

    /** Convenience: transition to [RegistrationState.Idle] (disconnected). */
    fun emitDisconnected() {
        emit(RegistrationState.Idle)
    }
    // endregion
}
