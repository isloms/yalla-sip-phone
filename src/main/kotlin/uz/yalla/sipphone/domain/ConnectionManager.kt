package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Monitors SIP registration health and drives automatic reconnection.
 *
 * Call [startMonitoring] once after the stack is initialised. The manager watches
 * [RegistrationEngine.registrationState] and re-registers with exponential back-off
 * on failure. Call [stopMonitoring] on app exit before [SipStackLifecycle.shutdown].
 */
interface ConnectionManager {
    /** Current connectivity state. Drives the toolbar indicator in the UI. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Starts the registration health monitor with the given [credentials].
     * If already monitoring, the previous session is cancelled and restarted.
     */
    fun startMonitoring(credentials: SipCredentials)

    /** Stops the monitor and cancels any pending reconnection attempts. */
    fun stopMonitoring()
}

/** Represents the current SIP connectivity state as seen by the UI layer. */
sealed interface ConnectionState {
    /** SIP registration is active and calls can be made/received. */
    data object Connected : ConnectionState

    /** Not registered and no reconnection in progress. */
    data object Disconnected : ConnectionState

    /**
     * Actively attempting to re-register after a failure.
     *
     * @param attempt 1-based reconnection attempt count.
     * @param nextRetryMs milliseconds until the next retry is scheduled.
     */
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState
}
