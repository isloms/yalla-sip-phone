package uz.yalla.sipphone.data.auth

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import uz.yalla.sipphone.data.auth.dto.LoginRequestDto
import uz.yalla.sipphone.data.auth.dto.LoginResultDto
import uz.yalla.sipphone.data.auth.dto.MeResultDto
import uz.yalla.sipphone.data.network.safeRequest

class AuthApi(
    private val client: HttpClient,
    private val authEventBus: AuthEventBus,
    private val baseUrlProvider: () -> String,
) {
    private fun endpoint(path: String): String =
        baseUrlProvider().trimEnd('/') + "/$path"

    suspend fun login(pinCode: String): Result<LoginResultDto> =
        client.safeRequest {
            url(endpoint("auth/login"))
            method = HttpMethod.Post
            setBody(LoginRequestDto(pinCode = pinCode))
        }

    suspend fun me(emitAuthEvent: Boolean = true): Result<MeResultDto> =
        client.safeRequest(if (emitAuthEvent) authEventBus else null) {
            url(endpoint("auth/me"))
            method = HttpMethod.Get
        }

    suspend fun logout(): Result<Unit> =
        client.safeRequest {
            url(endpoint("auth/logout"))
            method = HttpMethod.Post
        }
}
