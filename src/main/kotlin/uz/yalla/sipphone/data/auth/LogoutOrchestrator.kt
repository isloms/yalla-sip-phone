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
        if (logoutInProgress) return
        logoutInProgress = true
        logger.info { "Logout sequence starting..." }

        runCatching { authApi.logout() }
            .onFailure { logger.warn { "Server logout failed: ${it.message}" } }

        tokenProvider.clearToken()

        runCatching { sipAccountManager.unregisterAll() }
            .onFailure { logger.warn { "SIP unregisterAll failed: ${it.message}" } }

        logger.info { "Logout sequence complete" }
    }

    fun reset() {
        logoutInProgress = false
    }
}
