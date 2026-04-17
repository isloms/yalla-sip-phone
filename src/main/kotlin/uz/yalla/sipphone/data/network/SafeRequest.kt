package uz.yalla.sipphone.data.network

import io.github.oshai.kotlinlogging.KotlinLogging
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

@PublishedApi internal val logger = KotlinLogging.logger {}

suspend inline fun <reified T> HttpClient.safeRequest(
    authEventBus: AuthEventBus? = null,
    crossinline block: HttpRequestBuilder.() -> Unit,
): Result<T> {
    return try {
        val response: HttpResponse = request { block() }
        handleResponse<T>(response, authEventBus)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        logger.error(e) { "Request timeout" }
        Result.failure(NetworkError.NoConnection(e))
    } catch (e: IOException) {
        logger.error(e) { "IO error" }
        Result.failure(NetworkError.NoConnection(e))
    } catch (e: SerializationException) {
        logger.error(e) { "Deserialization failed" }
        Result.failure(NetworkError.ParseError(e))
    } catch (e: NetworkError) {
        logger.warn { "Network error: $e" }
        Result.failure(e)
    } catch (e: Exception) {
        logger.error(e) { "Unexpected error" }
        Result.failure(NetworkError.NoConnection(e))
    }
}

@PublishedApi
internal suspend inline fun <reified T> handleResponse(
    response: HttpResponse,
    authEventBus: AuthEventBus?,
): Result<T> {
    val httpStatus = response.status.value
    val url = response.request.url

    return when {
        httpStatus == 401 -> {
            logger.warn { "401 Unauthorized: $url" }
            authEventBus?.emit(AuthEvent.SessionExpired)
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
                    val err = envelope.errorMessage()
                    logger.warn { "$httpStatus $url — envelope error: code=${envelope.code}, msg=$err" }
                    Result.failure(
                        NetworkError.ClientError(
                            code = envelope.code,
                            serverMessage = err,
                        )
                    )
                }
            } catch (e: SerializationException) {
                logger.error(e) { "$httpStatus $url — deserialization failed" }
                Result.failure(NetworkError.ParseError(e))
            }
        }

        httpStatus in 400..499 -> {
            val msg = runCatching { response.body<ApiResponse<Unit>>().errorMessage() }.getOrNull()
            logger.warn { "$httpStatus $url — $msg" }
            Result.failure(NetworkError.ClientError(httpStatus, msg))
        }

        httpStatus in 500..599 -> {
            val msg = runCatching { response.body<ApiResponse<Unit>>().errorMessage() }.getOrNull()
            logger.error { "$httpStatus $url — $msg" }
            Result.failure(NetworkError.ServerError(httpStatus, msg))
        }

        else -> {
            logger.error { "$httpStatus $url — unexpected status" }
            Result.failure(NetworkError.ClientError(httpStatus, "Unexpected status: $httpStatus"))
        }
    }
}
