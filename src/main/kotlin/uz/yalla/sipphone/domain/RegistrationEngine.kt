package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * SIP account registration contract.
 *
 * State transitions: Idle → Registering → Registered | Failed.
 * After [unregister] the state returns to [RegistrationState.Idle].
 */
interface RegistrationEngine {
    /** Current registration state. Collect to react to SIP connectivity changes. */
    val registrationState: StateFlow<RegistrationState>

    /**
     * Registers with the SIP server using [credentials].
     *
     * Returns [Result.failure] if the stack is not initialised or the underlying
     * account creation fails. Actual auth/server errors are reflected in
     * [registrationState] as [RegistrationState.Failed].
     */
    suspend fun register(credentials: SipCredentials): Result<Unit>

    /** Unregisters the current account. No-op if not registered. */
    suspend fun unregister()
}
