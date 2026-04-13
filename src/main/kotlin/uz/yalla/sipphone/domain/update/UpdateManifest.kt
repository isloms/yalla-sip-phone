package uz.yalla.sipphone.domain.update

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * JSON envelope returned by `GET /api/v1/app-updates/latest`.
 *
 * Always 200 OK; `updateAvailable = false` means the client is current.
 * A 200 envelope (rather than 204) is forward-compat — new fields can be
 * added to the response over the years without changing the shape.
 */
@Serializable
data class UpdateEnvelope(
    val updateAvailable: Boolean,
    val release: UpdateRelease? = null,
)

@Serializable
data class UpdateRelease(
    val version: String,
    val minSupportedVersion: String,
    val releaseNotes: String = "",
    val installer: UpdateInstaller,
)

@Serializable
data class UpdateInstaller(
    val url: String,
    val sha256: String,
    val size: Long,
)

/**
 * Allow-list of hosts the client will download MSIs from. Hard-coded
 * in source — the single strongest cert-free defense against the `url`
 * field pointing off-domain. TODO(OQ2): confirm final hosts with backend.
 */
internal val UPDATE_URL_ALLOWLIST: List<String> = listOf(
    "downloads.yalla.uz",
    "updates.yalla.local",
)

private val SHA256_HEX = Regex("""^[0-9a-f]{64}$""")
private const val MAX_SIZE_BYTES: Long = 2L * 1024 * 1024 * 1024  // 2 GiB

sealed interface ManifestValidation {
    data object Valid : ManifestValidation
    data class Invalid(val reason: String) : ManifestValidation
}

/**
 * Validates a release manifest before any field is trusted (invariant I17).
 * Bad manifests must never brick clients — callers treat `Invalid` as
 * `NoUpdate` and log the reason.
 */
object ManifestValidator {

    fun validate(release: UpdateRelease): ManifestValidation {
        val version = Semver.parseOrNull(release.version)
            ?: return ManifestValidation.Invalid("version not semver: ${release.version}")
        val minSupported = Semver.parseOrNull(release.minSupportedVersion)
            ?: return ManifestValidation.Invalid("minSupportedVersion not semver: ${release.minSupportedVersion}")

        if (minSupported > version) {
            return ManifestValidation.Invalid("minSupportedVersion ($minSupported) > version ($version)")
        }

        if (release.installer.size <= 0) {
            return ManifestValidation.Invalid("size must be positive, got ${release.installer.size}")
        }
        if (release.installer.size >= MAX_SIZE_BYTES) {
            return ManifestValidation.Invalid("size ${release.installer.size} exceeds 2 GiB cap")
        }

        if (!SHA256_HEX.matches(release.installer.sha256)) {
            return ManifestValidation.Invalid("sha256 not 64-char lowercase hex: ${release.installer.sha256}")
        }

        val uri = runCatching { URI(release.installer.url) }.getOrNull()
            ?: return ManifestValidation.Invalid("url not parseable: ${release.installer.url}")
        if (uri.scheme != "https") {
            return ManifestValidation.Invalid("url must be https, got ${uri.scheme}")
        }
        val host = uri.host ?: return ManifestValidation.Invalid("url has no host: ${release.installer.url}")
        if (host !in UPDATE_URL_ALLOWLIST) {
            return ManifestValidation.Invalid("host '$host' not in allow-list")
        }

        return ManifestValidation.Valid
    }
}
