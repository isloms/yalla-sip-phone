package uz.yalla.sipphone.data.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import uz.yalla.sipphone.data.auth.AuthEvent
import uz.yalla.sipphone.data.auth.AuthEventBus
import java.io.IOException

suspend inline fun <reified T> HttpClient.safeRequest(
    authEventBus: AuthEventBus,
    crossinline block: HttpRequestBuilder.() -> Unit,
): Result<T> {
    return try {
        val response: HttpResponse = request { block() }
        handleResponse<T>(response, authEventBus)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        Result.failure(NetworkError.NoConnection(e))
    } catch (e: IOException) {
        Result.failure(NetworkError.NoConnection(e))
    } catch (e: SerializationException) {
        Result.failure(NetworkError.ParseError(e))
    } catch (e: NetworkError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(NetworkError.NoConnection(e))
    }
}

@PublishedApi
internal suspend inline fun <reified T> handleResponse(
    response: HttpResponse,
    authEventBus: AuthEventBus,
): Result<T> {
    val httpStatus = response.status.value

    return when {
        httpStatus == 401 -> {
            authEventBus.emit(AuthEvent.SessionExpired)
            Result.failure(NetworkError.Unauthorized)
        }

        httpStatus in 200..299 -> {
            try {
                val envelope: ApiResponse<T> = response.body()
                if (envelope.status && envelope.result != null) {
                    Result.success(envelope.result)
                } else if (envelope.status && envelope.result == null) {
                    @Suppress("UNCHECKED_CAST")
                    Result.success(Unit as T)
                } else {
                    Result.failure(
                        NetworkError.ClientError(
                            code = envelope.code,
                            serverMessage = envelope.errorMessage(),
                        )
                    )
                }
            } catch (e: SerializationException) {
                Result.failure(NetworkError.ParseError(e))
            }
        }

        httpStatus in 400..499 -> {
            val msg = runCatching { response.body<ApiResponse<Unit>>().errorMessage() }.getOrNull()
            Result.failure(NetworkError.ClientError(httpStatus, msg))
        }

        httpStatus in 500..599 -> {
            val msg = runCatching { response.body<ApiResponse<Unit>>().errorMessage() }.getOrNull()
            Result.failure(NetworkError.ServerError(httpStatus, msg))
        }

        else -> {
            Result.failure(NetworkError.ClientError(httpStatus, "Unexpected status: $httpStatus"))
        }
    }
}
