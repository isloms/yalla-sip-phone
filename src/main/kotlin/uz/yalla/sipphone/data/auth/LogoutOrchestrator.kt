package uz.yalla.sipphone.data.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.domain.SipAccountManager
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

class LogoutOrchestrator(
    private val sipAccountManager: SipAccountManager,
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
) {
    private val logoutInProgress = AtomicBoolean(false)

    suspend fun logout() {
        if (!logoutInProgress.compareAndSet(false, true)) return
        try {
            logger.info { "Logout started" }

            runCatching { authApi.logout() }
                .onFailure { logger.warn { "Server logout failed: ${it.message}" } }

            tokenProvider.clearToken()

            runCatching { sipAccountManager.unregisterAll() }
                .onFailure { logger.warn { "SIP unregisterAll failed: ${it.message}" } }

            logger.info { "Logout sequence complete" }
        } finally {
            logoutInProgress.set(false)
        }
    }
}
