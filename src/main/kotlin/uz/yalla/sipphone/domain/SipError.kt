package uz.yalla.sipphone.domain

/**
 * Typed SIP error hierarchy surfaced through [RegistrationState.Failed] and [CallEngine] results.
 *
 * Use [displayMessage] for human-readable UI strings.
 * Use [fromSipStatus] to map raw SIP response codes to the appropriate subtype.
 */
sealed interface SipError {
    /** Localised message suitable for display in the operator UI. */
    val displayMessage: String

    /** SIP 401/403 — credentials rejected by the server. */
    data class AuthFailed(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Authentication failed: $code $reason"
    }

    /** Transport-level failure (e.g. socket closed, DNS failure, SIP 503/504). */
    data class NetworkError(val cause: Throwable) : SipError {
        override val displayMessage: String get() = "Network error: ${cause.message}"
    }

    /** SIP 5xx server-side error not covered by a more specific subtype. */
    data class ServerError(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Server error: $code $reason"
    }

    /** SIP 404 — destination number or URI does not exist on the server. */
    data class NotFound(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Not found: $code $reason"
    }

    /** SIP 408 — server did not respond within the transaction timeout. */
    data class RequestTimeout(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Request timeout: $code $reason"
    }

    /** SIP 486 — callee is currently busy. */
    data class BusyHere(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Busy: $code $reason"
    }

    /** SIP 603 — callee explicitly declined the call. */
    data class Declined(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Call declined: $code $reason"
    }

    /** Unexpected exception from the pjsip stack or application code. */
    data class InternalError(val cause: Throwable) : SipError {
        override val displayMessage: String get() = "Internal error: ${cause.message}"
    }

    companion object {
        /**
         * Maps a raw SIP status [code] and [reason] phrase to the appropriate [SipError] subtype.
         * Unmapped 5xx codes fall through to [ServerError].
         */
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

        /** Wraps an arbitrary [Throwable] as [InternalError]. */
        fun fromException(e: Throwable): SipError = InternalError(e)
    }
}
