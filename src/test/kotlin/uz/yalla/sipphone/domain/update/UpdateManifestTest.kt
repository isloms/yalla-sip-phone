package uz.yalla.sipphone.domain.update

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse 200 envelope with updateAvailable false`() {
        val body = """{ "updateAvailable": false }"""
        val envelope = json.decodeFromString<UpdateEnvelope>(body)
        assertFalse(envelope.updateAvailable)
        assertNull(envelope.release)
    }

    @Test
    fun `parse 200 envelope with updateAvailable true and release`() {
        val body = """
            {
              "updateAvailable": true,
              "release": {
                "version": "1.2.0",
                "minSupportedVersion": "1.0.0",
                "releaseNotes": "bug fixes",
                "installer": {
                  "url": "https://downloads.yalla.uz/a.msi",
                  "sha256": "${"a".repeat(64)}",
                  "size": 12345
                }
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<UpdateEnvelope>(body)
        assertTrue(envelope.updateAvailable)
        val r = envelope.release
        assertNotNull(r)
        assertEquals("1.2.0", r.version)
        assertEquals("1.0.0", r.minSupportedVersion)
        assertEquals("bug fixes", r.releaseNotes)
        assertEquals("https://downloads.yalla.uz/a.msi", r.installer.url)
        assertEquals("a".repeat(64), r.installer.sha256)
        assertEquals(12345L, r.installer.size)
    }

    @Test
    fun `validate rejects manifest with negative size`() {
        val r = fakeRelease(size = -1)
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with size too large`() {
        val r = fakeRelease(size = 3L * 1024 * 1024 * 1024)  // 3 GiB
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with wrong sha256 length`() {
        val r = fakeRelease(sha256 = "abc123")
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with non-hex sha256`() {
        val r = fakeRelease(sha256 = "g".repeat(64))
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with unparseable version`() {
        val r = fakeRelease(version = "not-a-version")
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with minSupportedVersion greater than version`() {
        val r = fakeRelease(version = "1.2.0", minSupportedVersion = "2.0.0")
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate accepts http url for LAN deployment`() {
        val r = fakeRelease(url = "http://192.168.0.98/a.msi")
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Valid)
    }

    @Test
    fun `validate accepts https url`() {
        val r = fakeRelease(url = "https://downloads.yalla.uz/a.msi")
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Valid)
    }

    @Test
    fun `validate rejects ftp url`() {
        val r = fakeRelease(url = "ftp://downloads.yalla.uz/a.msi")
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects url host not in allow-list`() {
        val r = fakeRelease(url = "https://evil.example.com/a.msi")
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Invalid)
    }

    @Test
    fun `validate accepts well-formed manifest`() {
        val r = fakeRelease()
        assertTrue(ManifestValidator.validate(r) is ManifestValidation.Valid)
    }

    private fun fakeRelease(
        version: String = "1.2.0",
        minSupportedVersion: String = "1.0.0",
        releaseNotes: String = "notes",
        url: String = "https://downloads.yalla.uz/a.msi",
        sha256: String = "a".repeat(64),
        size: Long = 12345L,
    ) = UpdateRelease(
        version = version,
        minSupportedVersion = minSupportedVersion,
        releaseNotes = releaseNotes,
        installer = UpdateInstaller(url = url, sha256 = sha256, size = size),
    )
}
