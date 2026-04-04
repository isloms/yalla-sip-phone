package uz.yalla.sipphone.domain

data class SipCredentials(
    val server: String,
    val port: Int = SipConstants.DEFAULT_PORT,
    val username: String,
    val password: String,
)
