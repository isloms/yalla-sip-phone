package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Core SIP call operations contract.
 *
 * All suspend functions must be called from a coroutine context; implementations
 * may internally dispatch to a single-thread executor (e.g. pjsip event loop).
 *
 * State transitions are exposed via [callState]: Idle → Ringing → Active → Ending → Idle.
 */
interface CallEngine {
    /** Current call state. Collect to drive UI or react to call lifecycle events. */
    val callState: StateFlow<CallState>

    /**
     * Initiates an outbound call to [number].
     *
     * Returns [Result.failure] if there is already an active call, the engine is
     * not registered, or the underlying SIP stack rejects the request.
     */
    suspend fun makeCall(number: String): Result<Unit>

    /** Answers the current inbound ringing call. No-op if there is no inbound ringing call. */
    suspend fun answerCall()

    /** Hangs up the current call regardless of direction or state. */
    suspend fun hangupCall()

    /** Toggles microphone mute on the active call. No-op if not in [CallState.Active]. */
    suspend fun toggleMute()

    /** Toggles hold state on the active call. No-op if not in [CallState.Active]. */
    suspend fun toggleHold()

    /**
     * Sets microphone mute explicitly.
     *
     * @param callId must match [CallState.Active.callId]; ignored otherwise.
     * @param muted `true` to mute, `false` to unmute.
     */
    suspend fun setMute(callId: String, muted: Boolean)

    /**
     * Sets hold state explicitly.
     *
     * @param callId must match [CallState.Active.callId]; ignored otherwise.
     * @param onHold `true` to place on hold, `false` to resume.
     */
    suspend fun setHold(callId: String, onHold: Boolean)

    /**
     * Sends DTMF digits on the active call.
     *
     * @param callId must match the current active call id.
     * @param digits one or more characters from `[0-9*#A-D]`.
     */
    suspend fun sendDtmf(callId: String, digits: String): Result<Unit>

    /**
     * Blind-transfers the active call to [destination].
     *
     * @param destination E.164 number or SIP URI of the transfer target.
     */
    suspend fun transferCall(callId: String, destination: String): Result<Unit>
}
