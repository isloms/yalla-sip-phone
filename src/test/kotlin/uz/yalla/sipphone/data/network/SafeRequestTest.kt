package uz.yalla.sipphone.data.network

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uz.yalla.sipphone.data.auth.AuthEventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SafeRequestTest {

    private val authEventBus = AuthEventBus()

    private fun createClient(handler: MockRequestHandler): HttpClient {
        return HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    @Test
    fun `success response returns result`() = runTest {
        val client = createClient { _ ->
            respond(
                content = """{"status":true,"code":200,"message":"ok","result":{"value":"hello"},"errors":null}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = client.safeRequest<TestDto>(authEventBus) {
            url.takeFrom("http://test.com/api")
            method = HttpMethod.Get
        }
        assertTrue(result.isSuccess)
        assertEquals("hello", result.getOrThrow().value)
    }

    @Test
    fun `HTTP 401 returns Unauthorized`() = runTest {
        val client = createClient { _ ->
            respond(
                content = """{"status":false,"code":401,"message":"invalid token","result":null,"errors":null}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = client.safeRequest<TestDto>(authEventBus) {
            url.takeFrom("http://test.com/api")
            method = HttpMethod.Get
        }
        assertTrue(result.isFailure)
        assertIs<NetworkError.Unauthorized>(result.exceptionOrNull())
    }

    @Test
    fun `HTTP 200 with status=false returns ClientError`() = runTest {
        val client = createClient { _ ->
            respond(
                content = """{"status":false,"code":401,"message":"Error","result":null,"errors":"employee not found"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = client.safeRequest<TestDto>(authEventBus) {
            url.takeFrom("http://test.com/api")
            method = HttpMethod.Get
        }
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<NetworkError.ClientError>(error)
        assertEquals("employee not found", error.serverMessage)
    }

    @Test
    fun `HTTP 500 returns ServerError`() = runTest {
        val client = createClient { _ ->
            respond(
                content = """{"status":false,"code":500,"message":"boom","result":null,"errors":null}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = client.safeRequest<TestDto>(authEventBus) {
            url.takeFrom("http://test.com/api")
            method = HttpMethod.Get
        }
        assertTrue(result.isFailure)
        assertIs<NetworkError.ServerError>(result.exceptionOrNull())
    }
}

@Serializable
private data class TestDto(val value: String)
