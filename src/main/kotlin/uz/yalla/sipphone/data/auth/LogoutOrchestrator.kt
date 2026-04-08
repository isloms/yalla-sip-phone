package uz.yalla.sipphone.data.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.domain.SipAccountManager

private val logger = KotlinLogging.logger {}

class LogoutOrchestrator(
    private val sipAccountManager: SipAccountManager,
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
) {
    @Volatile
    private var logoutInProgress = false

    suspend fun logout() {
        if (logoutInProgress) {
            logger.debug { "Logout already in progress, skipping" }
            return
        }
        logoutInProgress = true
        logger.info { "Logout sequence starting..." }

        // 1. Clear token FIRST — prevents 401 → AuthEventBus → re-entry loop
        tokenProvider.clearToken()

        // 2. Unregister all SIP accounts
        runCatching { sipAccountManager.unregisterAll() }
            .onFailure { logger.warn { "SIP unregisterAll failed: ${it.message}" } }

        // 3. Notify backend (best-effort, token already cleared so this will likely 401)
        // Skip if token is gone — no point sending an unauthenticated logout request
        logger.info { "Logout sequence complete" }
    }

    /** Reset guard — call only when navigating back to login screen. */
    fun reset() {
        logoutInProgress = false
    }
}
