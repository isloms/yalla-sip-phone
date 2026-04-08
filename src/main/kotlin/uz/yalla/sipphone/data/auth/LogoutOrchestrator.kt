package uz.yalla.sipphone.data.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.ConnectionManager
import uz.yalla.sipphone.domain.RegistrationEngine

private val logger = KotlinLogging.logger {}

class LogoutOrchestrator(
    private val authRepository: AuthRepository,
    private val registrationEngine: RegistrationEngine,
    private val connectionManager: ConnectionManager,
    private val tokenProvider: TokenProvider,
) {
    suspend fun logout() {
        logger.info { "Logout sequence starting..." }
        connectionManager.stopMonitoring()
        runCatching { registrationEngine.unregister() }
            .onFailure { logger.warn { "SIP unregister failed: ${it.message}" } }
        runCatching { authRepository.logout() }
            .onFailure { logger.warn { "API logout failed: ${it.message}" } }
        tokenProvider.clearToken()
        logger.info { "Logout sequence complete" }
    }
}
