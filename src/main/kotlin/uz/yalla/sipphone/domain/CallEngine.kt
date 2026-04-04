package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface CallEngine {
    val callState: StateFlow<CallState>
    suspend fun makeCall(number: String): Result<Unit>
    suspend fun answerCall()
    suspend fun hangupCall()
    suspend fun toggleMute()
    suspend fun toggleHold()
    suspend fun setMute(callId: String, muted: Boolean)
    suspend fun setHold(callId: String, onHold: Boolean)
}
