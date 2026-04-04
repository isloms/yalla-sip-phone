package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCallEngine(
    var makeCallResult: Result<Unit> = Result.success(Unit),
) : CallEngine {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState = _callState.asStateFlow()

    var lastCallNumber: String? = null
    var answerCallCount = 0
    var hangupCallCount = 0
    var toggleMuteCount = 0
    var toggleHoldCount = 0
    var setMuteCount = 0
    var setHoldCount = 0

    override suspend fun makeCall(number: String): Result<Unit> {
        lastCallNumber = number
        return makeCallResult
    }

    override suspend fun answerCall() {
        answerCallCount++
    }

    override suspend fun hangupCall() {
        hangupCallCount++
    }

    override suspend fun toggleMute() {
        toggleMuteCount++
    }

    override suspend fun toggleHold() {
        toggleHoldCount++
    }

    override suspend fun setMute(callId: String, muted: Boolean) {
        setMuteCount++
        val state = _callState.value
        if (state is CallState.Active && state.callId == callId) {
            _callState.value = state.copy(isMuted = muted)
        }
    }

    override suspend fun setHold(callId: String, onHold: Boolean) {
        setHoldCount++
        val state = _callState.value
        if (state is CallState.Active && state.callId == callId) {
            _callState.value = state.copy(isOnHold = onHold)
        }
    }

    fun simulateRinging(
        callerNumber: String = "102",
        callerName: String? = null,
        isOutbound: Boolean = false,
        callId: String = "test-call-id",
    ) {
        _callState.value = CallState.Ringing(callId, callerNumber, callerName, isOutbound)
    }

    fun simulateActive(
        remoteNumber: String = "102",
        remoteName: String? = null,
        isOutbound: Boolean = false,
        isMuted: Boolean = false,
        isOnHold: Boolean = false,
        callId: String = "test-call-id",
    ) {
        _callState.value = CallState.Active(callId, remoteNumber, remoteName, isOutbound, isMuted, isOnHold)
    }

    fun simulateIdle() {
        _callState.value = CallState.Idle
    }
}
