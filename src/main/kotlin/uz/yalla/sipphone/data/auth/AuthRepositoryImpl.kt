package uz.yalla.sipphone.data.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import uz.yalla.sipphone.data.auth.dto.toAuthResult
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult

private val logger = KotlinLogging.logger {}

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
    private val appSettings: AppSettings,
) : AuthRepository {

    override suspend fun login(pinCode: String): Result<AuthResult> {
        val loginResult = authApi.login(pinCode)
        val loginDto = loginResult.getOrElse { return Result.failure(it) }

        tokenProvider.setToken(loginDto.token)
        logger.info { "Token received, fetching user info..." }

        // Don't emit SessionExpired on 401 during login — it's a login failure, not session expiry.
        // Retry once after delay — server may need time to persist token in session store.
        var meResult = authApi.me(emitAuthEvent = false)
        if (meResult.isFailure) {
            logger.warn { "First /me attempt failed, retrying after delay..." }
            delay(500)
            meResult = authApi.me(emitAuthEvent = false)
        }
        val meDto = meResult.getOrElse { error ->
            tokenProvider.clearToken()
            return Result.failure(error)
        }

        val authResult = meDto.toAuthResult(
            token = loginDto.token,
            dispatcherUrl = appSettings.dispatcherUrl,
            backendUrl = appSettings.backendUrl,
        )

        if (authResult.accounts.isEmpty()) {
            tokenProvider.clearToken()
            return Result.failure(IllegalStateException("No active SIP accounts found"))
        }

        logger.info { "Auth complete: agent=${authResult.agent.name}, accounts=${authResult.accounts.size}" }
        return Result.success(authResult)
    }

    override suspend fun logout(): Result<Unit> {
        val result = runCatching { authApi.logout() }
        result.onFailure { logger.warn(it) { "Logout API call failed, clearing token anyway" } }
        tokenProvider.clearToken()
        logger.info { "Logged out, token cleared" }
        return Result.success(Unit)
    }
}
