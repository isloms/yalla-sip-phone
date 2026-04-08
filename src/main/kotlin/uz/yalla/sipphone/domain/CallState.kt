package uz.yalla.sipphone.domain

sealed interface CallState {
    data object Idle : CallState

    data class Ringing(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val isOutbound: Boolean,
        val accountId: String = "",
    ) : CallState

    data class Active(
        val callId: String,
        val remoteNumber: String,
        val remoteName: String?,
        val isOutbound: Boolean,
        val isMuted: Boolean,
        val isOnHold: Boolean,
        val accountId: String = "",
    ) : CallState

    data class Ending(
        val callId: String = "",
        val accountId: String = "",
    ) : CallState
}
