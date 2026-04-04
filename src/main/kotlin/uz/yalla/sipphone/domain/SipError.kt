package uz.yalla.sipphone.domain

sealed interface SipError {
    val displayMessage: String

    data class AuthFailed(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Authentication failed: $code $reason"
    }

    data class NetworkError(val cause: Throwable) : SipError {
        override val displayMessage: String get() = "Network error: ${cause.message}"
    }

    data class ServerError(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Server error: $code $reason"
    }

    data class InternalError(val cause: Throwable) : SipError {
        override val displayMessage: String get() = "Internal error: ${cause.message}"
    }

    companion object {
        fun fromSipStatus(code: Int, reason: String): SipError = when {
            code == 401 || code == 403 -> AuthFailed(code, reason)
            code == 408 || code == 503 || code == 504 -> NetworkError(
                Exception("$code $reason")
            )
            code in 500..599 -> ServerError(code, reason)
            else -> ServerError(code, reason)
        }

        fun fromException(e: Throwable): SipError = InternalError(e)
    }
}
