package uz.yalla.sipphone.domain

/**
 * Represents the SIP account registration lifecycle.
 *
 * Collect [RegistrationEngine.registrationState] to observe transitions.
 */
sealed interface RegistrationState {
    /** No account registered; initial state after stack initialisation. */
    data object Idle : RegistrationState

    /** REGISTER request has been sent; waiting for a server response. */
    data object Registering : RegistrationState

    /**
     * Registration is active and the account is reachable.
     *
     * @param server The SIP server URI returned in the REGISTER 200 OK Contact header.
     */
    data class Registered(val server: String) : RegistrationState

    /**
     * Registration attempt failed or an existing registration was revoked.
     *
     * @param error Structured error describing the cause; use [SipError.displayMessage] for UI.
     */
    data class Failed(val error: SipError) : RegistrationState
}
