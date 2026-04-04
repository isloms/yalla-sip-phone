package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCallEngine : CallEngine {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState = _callState.asStateFlow()

    var lastCallNumber: String? = null
    var answerCallCount = 0
    var hangupCallCount = 0
    var toggleMuteCount = 0
    var toggleHoldCount = 0

    override suspend fun makeCall(number: String): Result<Unit> {
        lastCallNumber = number
        return Result.success(Unit)
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

    fun simulateRinging(callerNumber: String = "102", callerName: String? = null, isOutbound: Boolean = false) {
        _callState.value = CallState.Ringing(callerNumber, callerName, isOutbound)
    }

    fun simulateActive(
        remoteNumber: String = "102",
        remoteName: String? = null,
        isOutbound: Boolean = false,
        isMuted: Boolean = false,
        isOnHold: Boolean = false,
    ) {
        _callState.value = CallState.Active(remoteNumber, remoteName, isOutbound, isMuted, isOnHold)
    }

    fun simulateIdle() {
        _callState.value = CallState.Idle
    }
}
