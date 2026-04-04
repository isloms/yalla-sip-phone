package uz.yalla.sipphone.domain

sealed interface CallState {
    data object Idle : CallState
    data class Ringing(
        val callerNumber: String,
        val callerName: String?,
        val isOutbound: Boolean,
    ) : CallState
    data class Active(
        val remoteNumber: String,
        val remoteName: String?,
        val isOutbound: Boolean,
        val isMuted: Boolean,
        val isOnHold: Boolean,
    ) : CallState
    data object Ending : CallState
}
