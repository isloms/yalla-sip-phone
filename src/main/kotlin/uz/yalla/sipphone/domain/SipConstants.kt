package uz.yalla.sipphone.domain

object SipConstants {
    const val APP_VERSION = "1.2.0"
    const val DEFAULT_PORT = 5060
    const val USER_AGENT = "YallaSipPhone/$APP_VERSION"
    const val AUTH_SCHEME_DIGEST = "digest"
    const val AUTH_REALM_ANY = "*"
    const val AUTH_DATA_TYPE_PLAINTEXT = 0
    const val STATUS_OK = 200
    const val STATUS_BUSY_HERE = 486
    const val STATUS_CLASS_SUCCESS = 2
    const val POLL_INTERVAL_MS = 50
    const val AUDIO_LEVEL_UNMUTED = 1.0f
    const val AUDIO_LEVEL_MUTED = 0.0f
    const val RATE_LIMIT_MS = 1000L
    const val UNREGISTER_DELAY_MS = 200L

    object Timeout {
        const val UNREGISTER_BEFORE_REREGISTER_MS = 3000L
        const val UNREGISTER_MS = 5000L
        const val DESTROY_MS = 3000L
    }

    object NativeLib {
        const val MAC = "libpjsua2.jnilib"
        const val WINDOWS = "pjsua2.dll"
        const val LINUX = "libpjsua2.so"
        const val FALLBACK = "pjsua2"
    }

    private val VALID_HOST_REGEX = Regex("""^[a-zA-Z0-9.\-:]+$""")
    private val VALID_USERNAME_REGEX = Regex("""^[a-zA-Z0-9._\-+]+$""")
    private val VALID_CALL_NUMBER_REGEX = Regex("""^[0-9*#+]+$""")

    // Prevents SIP header injection via control characters and special delimiters
    fun validateSipInput(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.any { it.isISOControl() }) return false
        if (value.contains('\r') || value.contains('\n')) return false
        if (value.contains(';') || value.contains('<') || value.contains('>')) return false
        return true
    }

    fun buildUserUri(user: String, server: String): String {
        require(validateSipInput(user) && VALID_USERNAME_REGEX.matches(user)) {
            "Invalid SIP username: $user"
        }
        require(validateSipInput(server) && VALID_HOST_REGEX.matches(server)) {
            "Invalid SIP server: $server"
        }
        return "sip:$user@$server"
    }

    fun buildRegistrarUri(server: String, port: Int): String {
        require(validateSipInput(server) && VALID_HOST_REGEX.matches(server)) {
            "Invalid SIP server: $server"
        }
        require(port in 1..65535) { "Invalid port: $port" }
        return "sip:$server:$port"
    }

    fun buildCallUri(number: String, host: String): String {
        require(validateSipInput(number) && VALID_CALL_NUMBER_REGEX.matches(number)) {
            "Invalid call number: $number"
        }
        require(validateSipInput(host) && VALID_HOST_REGEX.matches(host)) {
            "Invalid SIP host: $host"
        }
        return "sip:$number@$host"
    }

    fun extractHostFromUri(serverUri: String?): String {
        val uri = serverUri ?: return ""
        val atIndex = uri.lastIndexOf('@')
        return if (atIndex >= 0) uri.substring(atIndex + 1) else uri
    }
}
