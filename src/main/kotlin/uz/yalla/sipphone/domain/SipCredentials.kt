package uz.yalla.sipphone.domain

data class SipCredentials(
    val server: String,
    val port: Int = DEFAULT_SIP_PORT,
    val username: String,
    val password: String,
) {
    companion object {
        const val DEFAULT_SIP_PORT = 5060
    }
}
