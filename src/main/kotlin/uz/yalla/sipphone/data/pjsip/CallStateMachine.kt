package uz.yalla.sipphone.data.pjsip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uz.yalla.sipphone.domain.CallState

sealed interface CallEvent {
    data class OutgoingDial(
        val callId: String,
        val remoteNumber: String,
        val accountId: String,
    ) : CallEvent

    data class IncomingRing(
        val callId: String,
        val remoteNumber: String,
        val remoteName: String?,
        val accountId: String,
        val remoteUri: String,
    ) : CallEvent

    data object Answered : CallEvent
    data object LocalHangup : CallEvent
    data object RemoteDisconnect : CallEvent
    data class MuteChanged(val muted: Boolean) : CallEvent
    data class HoldChanged(val onHold: Boolean) : CallEvent
}

class CallStateMachine {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    fun dispatch(event: CallEvent): CallState {
        val next = transition(_state.value, event)
        _state.value = next
        return next
    }

    private fun transition(current: CallState, event: CallEvent): CallState = when (event) {
        is CallEvent.OutgoingDial -> CallState.Ringing(
            callId = event.callId,
            callerNumber = event.remoteNumber,
            callerName = null,
            isOutbound = true,
            accountId = event.accountId,
        )
        is CallEvent.IncomingRing -> CallState.Ringing(
            callId = event.callId,
            callerNumber = event.remoteNumber,
            callerName = event.remoteName,
            isOutbound = false,
            accountId = event.accountId,
            remoteUri = event.remoteUri,
        )
        CallEvent.Answered -> when (current) {
            is CallState.Ringing -> CallState.Active(
                callId = current.callId,
                remoteNumber = current.callerNumber,
                remoteName = current.callerName,
                isOutbound = current.isOutbound,
                isMuted = false,
                isOnHold = false,
                accountId = current.accountId,
                remoteUri = current.remoteUri,
            )
            else -> current
        }
        CallEvent.LocalHangup -> when (current) {
            is CallState.Ringing -> CallState.Ending(callId = current.callId, accountId = current.accountId)
            is CallState.Active -> CallState.Ending(callId = current.callId, accountId = current.accountId)
            else -> current
        }
        CallEvent.RemoteDisconnect -> CallState.Idle
        is CallEvent.MuteChanged -> (current as? CallState.Active)?.copy(isMuted = event.muted) ?: current
        is CallEvent.HoldChanged -> (current as? CallState.Active)?.copy(isOnHold = event.onHold) ?: current
    }
}
