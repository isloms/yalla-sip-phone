package uz.yalla.sipphone.domain

object SipConstants {
    const val DEFAULT_PORT = 5060
    const val USER_AGENT = "YallaSipPhone/1.0"
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

    fun buildUserUri(user: String, server: String): String = "sip:$user@$server"

    fun buildRegistrarUri(server: String, port: Int): String = "sip:$server:$port"

    fun buildCallUri(number: String, host: String): String = "sip:$number@$host"

    fun extractHostFromUri(serverUri: String?): String {
        val uri = serverUri ?: return ""
        val atIndex = uri.lastIndexOf('@')
        return if (atIndex >= 0) uri.substring(atIndex + 1) else uri
    }
}
