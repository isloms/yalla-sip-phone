package uz.yalla.sipphone.domain

data class AuthResult(
    val token: String,
    val sipCredentials: SipCredentials,
    val dispatcherUrl: String,
    val agent: AgentInfo,
)
