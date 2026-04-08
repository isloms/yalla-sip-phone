package uz.yalla.sipphone.domain

data class AuthResult(
    val token: String,
    val accounts: List<SipAccountInfo>,
    val dispatcherUrl: String,
    val agent: AgentInfo,
)

// Temporary backward compatibility — remove when LoginComponent is updated to use SipAccountManager
@Deprecated("Use accounts instead", ReplaceWith("accounts.first().credentials"))
val AuthResult.sipCredentials: SipCredentials
    get() = accounts.first().credentials
