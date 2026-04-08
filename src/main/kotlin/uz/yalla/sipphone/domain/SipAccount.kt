package uz.yalla.sipphone.domain

data class SipAccount(
    val id: String,
    val name: String,
    val credentials: SipCredentials,
    val state: SipAccountState,
)
