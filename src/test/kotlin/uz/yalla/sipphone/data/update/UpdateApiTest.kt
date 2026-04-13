package uz.yalla.sipphone.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import uz.yalla.sipphone.domain.update.UpdateChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateApiTest {

    private fun clientReturning(status: HttpStatusCode, body: String): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `check returns NoUpdate for updateAvailable false`() = runTest {
        val api = UpdateApi(
            clientReturning(HttpStatusCode.OK, """{"updateAvailable":false}"""),
            baseUrl = "https://api/",
        )
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun `check returns Available with parsed release for updateAvailable true`() = runTest {
        val body = """
          {"updateAvailable":true,"release":{"version":"1.2.0","minSupportedVersion":"1.0.0","releaseNotes":"x","installer":{"url":"https://downloads.yalla.uz/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, body), baseUrl = "https://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("1.2.0", (result as UpdateCheckResult.Available).release.version)
    }

    @Test
    fun `check returns Malformed when manifest has bad semver`() = runTest {
        val body = """
          {"updateAvailable":true,"release":{"version":"not-semver","minSupportedVersion":"1.0.0","releaseNotes":"","installer":{"url":"https://downloads.yalla.uz/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, body), baseUrl = "https://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Malformed)
    }

    @Test
    fun `check returns Malformed when url host not in allowlist`() = runTest {
        val body = """
          {"updateAvailable":true,"release":{"version":"1.2.0","minSupportedVersion":"1.0.0","releaseNotes":"","installer":{"url":"https://evil.com/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, body), baseUrl = "https://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Malformed)
    }

    @Test
    fun `check returns Error on 5xx`() = runTest {
        val api = UpdateApi(
            clientReturning(HttpStatusCode.InternalServerError, """{"error":"boom"}"""),
            baseUrl = "https://api/",
        )
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Error)
    }
}
