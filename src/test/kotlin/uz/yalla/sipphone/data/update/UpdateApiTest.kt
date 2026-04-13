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

    /** Wrap a payload in the RoyalTaxi standard ApiResponse envelope. */
    private fun envelope(result: String): String =
        """{"status":true,"code":200,"message":"success","result":$result,"errors":null}"""

    @Test
    fun `check returns NoUpdate when wrapped result has updateAvailable false`() = runTest {
        val body = envelope("""{"updateAvailable":false}""")
        val api = UpdateApi(
            clientReturning(HttpStatusCode.OK, body),
            baseUrl = "http://api/",
        )
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun `check returns Available with parsed release for updateAvailable true`() = runTest {
        val release = """
          {"updateAvailable":true,"release":{"version":"1.2.0","minSupportedVersion":"1.0.0","releaseNotes":"x","installer":{"url":"http://192.168.0.98:8080/releases/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, envelope(release)), baseUrl = "http://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("1.2.0", (result as UpdateCheckResult.Available).release.version)
    }

    @Test
    fun `check returns Available with https url`() = runTest {
        val release = """
          {"updateAvailable":true,"release":{"version":"1.2.0","minSupportedVersion":"1.0.0","releaseNotes":"x","installer":{"url":"https://downloads.yalla.uz/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, envelope(release)), baseUrl = "http://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Available)
    }

    @Test
    fun `check returns Malformed when manifest has bad semver`() = runTest {
        val release = """
          {"updateAvailable":true,"release":{"version":"not-semver","minSupportedVersion":"1.0.0","releaseNotes":"","installer":{"url":"http://192.168.0.98:8080/releases/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, envelope(release)), baseUrl = "http://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Malformed)
    }

    @Test
    fun `check returns Malformed when url host not in allowlist`() = runTest {
        val release = """
          {"updateAvailable":true,"release":{"version":"1.2.0","minSupportedVersion":"1.0.0","releaseNotes":"","installer":{"url":"http://evil.com/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, envelope(release)), baseUrl = "http://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Malformed)
    }

    @Test
    fun `check returns Error on 5xx`() = runTest {
        val api = UpdateApi(
            clientReturning(HttpStatusCode.InternalServerError, envelope("null")),
            baseUrl = "http://api/",
        )
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun `check returns Error when envelope status is false`() = runTest {
        val body = """{"status":false,"code":500,"message":"boom","result":null,"errors":"db error"}"""
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, body), baseUrl = "http://api/")
        val result = api.check(UpdateChannel.STABLE, "1.0.0", "iid")
        assertTrue(result is UpdateCheckResult.Error)
    }
}
