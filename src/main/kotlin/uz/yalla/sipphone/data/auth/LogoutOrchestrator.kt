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

        // 1. Call server logout BEFORE clearing token — invalidates session on server
        runCatching { authApi.logout() }
            .onFailure { logger.warn { "Server logout failed: ${it.message}" } }

        // 2. Clear token locally
        tokenProvider.clearToken()

        // 3. Unregister all SIP accounts
        runCatching { sipAccountManager.unregisterAll() }
            .onFailure { logger.warn { "SIP unregisterAll failed: ${it.message}" } }

        logger.info { "Logout sequence complete" }
    }

    /** Reset guard — call only when navigating back to login screen. */
    fun reset() {
        logoutInProgress = false
    }
}
