package uz.yalla.sipphone.domain

/**
 * Represents the lifecycle of a single SIP call.
 *
 * Valid transitions: Idle → Ringing → Active → Ending → Idle.
 * Collect [CallEngine.callState] to observe transitions.
 */
sealed interface CallState {
    /** No call in progress. The engine is ready to make or receive calls. */
    data object Idle : CallState

    /**
     * A call is alerting — either an inbound ring or an outbound dial-tone.
     *
     * @param callId Stable identifier for this call leg; use it for [CallEngine] operations.
     * @param callerNumber Remote party number (inbound) or dialled number (outbound).
     * @param callerName Display name from the SIP From header, if present.
     * @param isOutbound `true` for calls initiated by [CallEngine.makeCall], `false` for inbound.
     */
    data class Ringing(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val isOutbound: Boolean,
    ) : CallState

    /**
     * The call is established and media is flowing.
     *
     * @param callId Same identifier as in the preceding [Ringing] state.
     * @param remoteNumber Remote party's number.
     * @param remoteName Remote party's display name, if available.
     * @param isOutbound `true` for outbound calls.
     * @param isMuted `true` when the local microphone is suppressed.
     * @param isOnHold `true` when the call is on hold (re-INVITE with `sendonly`).
     */
    data class Active(
        val callId: String,
        val remoteNumber: String,
        val remoteName: String?,
        val isOutbound: Boolean,
        val isMuted: Boolean,
        val isOnHold: Boolean,
    ) : CallState

    /**
     * Hangup has been requested; waiting for the pjsip disconnect callback.
     * Transitions to [Idle] once the callback fires or a safety timeout elapses.
     */
    data object Ending : CallState
}
