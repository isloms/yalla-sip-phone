package uz.yalla.sipphone.data.network

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import uz.yalla.sipphone.data.auth.TokenProvider

private val logger = KotlinLogging.logger {}

fun createHttpClient(
    tokenProvider: TokenProvider,
): HttpClient = HttpClient(CIO) {

    expectSuccess = false

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        })
    }

    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }

    install(Logging) {
        this.logger = object : Logger {
            override fun log(message: String) {
                uz.yalla.sipphone.data.network.logger.debug { message }
            }
        }
        level = LogLevel.HEADERS
        sanitizeHeader { header -> header == "Authorization" }
    }

    defaultRequest {
        contentType(ContentType.Application.Json)
    }

}.also { client ->
    // Manual auth — reads fresh from tokenProvider on every request, no cache.
    client.requestPipeline.intercept(HttpRequestPipeline.State) {
        if (!context.url.encodedPath.contains("/auth/login")) {
            tokenProvider.getToken()?.let { token ->
                context.bearerAuth(token)
            }
        }
    }
}
