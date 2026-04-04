package uz.yalla.sipphone.domain

sealed interface RegistrationState {
    data object Idle : RegistrationState
    data object Registering : RegistrationState
    data class Registered(val server: String) : RegistrationState
    data class Failed(val error: SipError) : RegistrationState
}
