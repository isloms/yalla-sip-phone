package uz.yalla.sipphone.data.pjsip

import uz.yalla.sipphone.domain.SipError

sealed interface PjsipRegistrationState {
    data object Idle : PjsipRegistrationState
    data object Registering : PjsipRegistrationState
    data class Registered(val server: String) : PjsipRegistrationState
    data class Failed(val error: SipError) : PjsipRegistrationState
}
