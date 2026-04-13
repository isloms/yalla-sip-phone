package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import uz.yalla.sipphone.data.network.ApiResponse
import uz.yalla.sipphone.domain.update.ManifestValidation
import uz.yalla.sipphone.domain.update.ManifestValidator
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateEnvelope
import uz.yalla.sipphone.domain.update.UpdateRelease

private val logger = KotlinLogging.logger {}

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult
    data class Available(val release: UpdateRelease) : UpdateCheckResult
    data class Malformed(val reason: String) : UpdateCheckResult
    data class Error(val cause: Throwable?) : UpdateCheckResult
}

/**
 * Calls `GET {baseUrl}app-updates/latest` with the headers mandated by spec §6.1
 * and unwraps the RoyalTaxi standard `ApiResponse<T>` envelope before extracting
 * the [UpdateEnvelope] payload. Returns a typed [UpdateCheckResult] — never throws.
 */
class UpdateApi(
    private val client: HttpClient,
    private val baseUrl: String,
) {

    suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String = "windows",
    ): UpdateCheckResult {
        val url = baseUrl.trimEnd('/') + "/app-updates/latest"
        val response: HttpResponse = try {
            client.get(url) {
                header("X-App-Version", currentVersion)
                header("X-App-Platform", platform)
                header("X-App-Channel", channel.value)
                header("X-Install-Id", installId)
                header("User-Agent", "YallaSipPhone/$currentVersion ($platform)")
            }
        } catch (t: Throwable) {
            logger.warn(t) { "Update check network failure" }
            return UpdateCheckResult.Error(t)
        }

        if (!response.status.isSuccess()) {
            logger.warn { "Update check HTTP ${response.status.value}" }
            return UpdateCheckResult.Error(null)
        }

        // Backend wraps every endpoint in {status, code, message, result, errors}.
        // We deserialize that wrapper, then read the inner UpdateEnvelope.
        // Explicit reified type — without it Ktor cannot resolve the inner T
        // serializer because this function is not inline.
        val wrapped: ApiResponse<UpdateEnvelope> = try {
            response.body<ApiResponse<UpdateEnvelope>>()
        } catch (t: Throwable) {
            logger.warn(t) { "Update check malformed JSON" }
            return UpdateCheckResult.Malformed("unparseable JSON: ${t.message}")
        }

        if (!wrapped.status) {
            logger.warn { "Update check failed envelope: ${wrapped.message}" }
            return UpdateCheckResult.Error(null)
        }

        val envelope = wrapped.result
            ?: return UpdateCheckResult.NoUpdate

        if (!envelope.updateAvailable || envelope.release == null) {
            return UpdateCheckResult.NoUpdate
        }

        return when (val v = ManifestValidator.validate(envelope.release)) {
            is ManifestValidation.Valid -> UpdateCheckResult.Available(envelope.release)
            is ManifestValidation.Invalid -> {
                logger.warn { "Manifest rejected: ${v.reason}" }
                UpdateCheckResult.Malformed(v.reason)
            }
        }
    }
}
