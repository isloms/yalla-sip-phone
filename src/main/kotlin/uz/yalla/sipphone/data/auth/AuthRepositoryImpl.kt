package uz.yalla.sipphone.data.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.data.auth.dto.toAuthResult
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult

private val logger = KotlinLogging.logger {}

object ApiConfig {
    const val BASE_URL = "http://192.168.0.98:8080/api/v1/"
    const val DISPATCHER_URL = "http://192.168.60.84:5173"
}

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
) : AuthRepository {

    override suspend fun login(pinCode: String): Result<AuthResult> {
        val loginResult = authApi.login(pinCode)
        val loginDto = loginResult.getOrElse { return Result.failure(it) }

        tokenProvider.setToken(loginDto.token)
        logger.info { "Token received, fetching user info..." }

        val meResult = authApi.me()
        val meDto = meResult.getOrElse { error ->
            tokenProvider.clearToken()
            return Result.failure(error)
        }

        val activeSip = meDto.sips.firstOrNull { it.isActive }
        if (activeSip == null) {
            tokenProvider.clearToken()
            return Result.failure(IllegalStateException("No active SIP connection available"))
        }

        val authResult = meDto.toAuthResult(
            token = loginDto.token,
            dispatcherUrl = ApiConfig.DISPATCHER_URL,
        )

        logger.info { "Auth complete: agent=${authResult.agent.name}, sip=${authResult.sipCredentials}" }
        return Result.success(authResult)
    }

    override suspend fun logout(): Result<Unit> {
        runCatching { authApi.logout() }
        tokenProvider.clearToken()
        logger.info { "Logged out, token cleared" }
        return Result.success(Unit)
    }
}
