package uz.yalla.sipphone.domain

/**
 * Manages the SIP stack process lifecycle.
 *
 * [initialize] must be called once before any [RegistrationEngine] or [CallEngine]
 * operations. [shutdown] must always be called on app exit to release native resources
 * and prevent pjsip memory leaks.
 */
interface SipStackLifecycle {
    /**
     * Initialises the native SIP stack, creates transports, and starts the event loop.
     *
     * @return [Result.failure] if native library loading or endpoint initialisation fails.
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Tears down the SIP stack, cancels all calls and registrations, and frees native memory.
     *
     * Must be called exactly once before the process exits. Calling it more than once is a no-op.
     */
    suspend fun shutdown()
}
