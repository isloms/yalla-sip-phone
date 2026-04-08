package uz.yalla.sipphone.data.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import uz.yalla.sipphone.data.network.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryImplTest {

    private val tokenProvider = InMemoryTokenProvider()
    private val authEventBus = AuthEventBus()

    private fun createClient(handler: MockRequestHandler): HttpClient {
        return HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
        }
    }

    private fun createRepo(handler: MockRequestHandler): AuthRepositoryImpl {
        val client = createClient(handler)
        val api = AuthApi(client, authEventBus)
        return AuthRepositoryImpl(api, tokenProvider)
    }

    @Test
    fun `successful login stores token and returns correct AuthResult`() = runTest {
        val loginResponse = """
            {"status":true,"code":200,"message":"login successful","result":{"token":"jwt-test","token_type":"Bearer ","expire":9999999999},"errors":null}
        """.trimIndent()
        val meResponse = """
            {"status":true,"code":200,"message":"success","result":{"id":1,"tm_user_id":1,"full_name":"Test Agent","roles":"admin","created_at":"2026-01-01","sips":[{"extension_number":103,"password":"demo","is_active":true,"sip_name":"Test SIP","server_url":"http://test.uz","server_port":5060,"domain":"test.uz","connection_type":"udp"}]},"errors":null}
        """.trimIndent()

        var requestCount = 0
        val repo = createRepo { request ->
            requestCount++
            val content = if (request.url.encodedPath.contains("login")) loginResponse else meResponse
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = repo.login("1234")
        assertTrue(result.isSuccess)

        val authResult = result.getOrThrow()
        assertEquals("jwt-test", authResult.token)
        assertEquals("Test Agent", authResult.agent.name)
        assertEquals("1", authResult.agent.id)
        assertTrue(authResult.accounts.isNotEmpty())
        val firstAccount = authResult.accounts.first()
        assertEquals(103, firstAccount.extensionNumber)
        assertEquals("http://test.uz", firstAccount.serverUrl)
        assertEquals("Test SIP", firstAccount.sipName)
        assertEquals("test.uz", firstAccount.credentials.server)
        assertEquals(5060, firstAccount.credentials.port)
        assertEquals("103", firstAccount.credentials.username)
        assertEquals("demo", firstAccount.credentials.password)
        assertEquals("UDP", firstAccount.credentials.transport)
        assertEquals(ApiConfig.DISPATCHER_URL, authResult.dispatcherUrl)

        // Token should be stored
        assertNotNull(tokenProvider.getToken())
        assertEquals("jwt-test", tokenProvider.getToken())
    }

    @Test
    fun `failed login with status=false does NOT store token`() = runTest {
        val failResponse = """
            {"status":false,"code":401,"message":"Error","result":null,"errors":"employee not found"}
        """.trimIndent()

        val repo = createRepo { _ ->
            respond(
                content = failResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = repo.login("wrong-pin")
        assertTrue(result.isFailure)

        val error = result.exceptionOrNull()
        assertIs<NetworkError.ClientError>(error)
        assertEquals("employee not found", error.serverMessage)

        // Token should NOT be stored
        assertNull(tokenProvider.getToken())
    }

    @Test
    fun `login clears token if me() fails after login succeeds`() = runTest {
        val loginResponse = """
            {"status":true,"code":200,"message":"login successful","result":{"token":"jwt-test","token_type":"Bearer ","expire":9999999999},"errors":null}
        """.trimIndent()
        val meFailResponse = """
            {"status":false,"code":500,"message":"internal error","result":null,"errors":null}
        """.trimIndent()

        var requestCount = 0
        val repo = createRepo { request ->
            requestCount++
            if (request.url.encodedPath.contains("login")) {
                respond(
                    content = loginResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = meFailResponse,
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val result = repo.login("1234")
        assertTrue(result.isFailure)

        // Token should be cleared after me() failure
        assertNull(tokenProvider.getToken())
    }

    @Test
    fun `logout clears token`() = runTest {
        // Pre-set a token
        tokenProvider.setToken("existing-token")
        assertEquals("existing-token", tokenProvider.getToken())

        val logoutResponse = """
            {"status":true,"code":200,"message":"success","result":null,"errors":null}
        """.trimIndent()

        val repo = createRepo { _ ->
            respond(
                content = logoutResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = repo.logout()
        assertTrue(result.isSuccess)

        // Token should be cleared
        assertNull(tokenProvider.getToken())
    }
}
