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

    data class NotFound(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Not found: $code $reason"
    }

    data class RequestTimeout(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Request timeout: $code $reason"
    }

    data class BusyHere(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Busy: $code $reason"
    }

    data class Declined(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Call declined: $code $reason"
    }

    data class InternalError(val cause: Throwable) : SipError {
        override val displayMessage: String get() = "Internal error: ${cause.message}"
    }

    companion object {
        fun fromSipStatus(code: Int, reason: String): SipError = when (code) {
            401, 403 -> AuthFailed(code, reason)
            404 -> NotFound(code, reason)
            408 -> RequestTimeout(code, reason)
            486 -> BusyHere(code, reason)
            503, 504 -> NetworkError(Exception("$code $reason"))
            603 -> Declined(code, reason)
            in 500..599 -> ServerError(code, reason)
            else -> ServerError(code, reason)
        }

        fun fromException(e: Throwable): SipError = InternalError(e)
    }
}
