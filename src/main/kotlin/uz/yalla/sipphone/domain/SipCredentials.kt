package uz.yalla.sipphone.domain

data class SipCredentials(
    val server: String,
    val port: Int = SipConstants.DEFAULT_PORT,
    val username: String,
    val password: String,
    val transport: String = "UDP",
) {
    override fun toString(): String =
        "SipCredentials(server=$server, port=$port, username=$username, password=***, transport=$transport)"
}
