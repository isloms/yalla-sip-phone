package uz.yalla.sipphone.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.update.UpdateInstaller
import uz.yalla.sipphone.domain.update.UpdateRelease
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloaderTest {

    private lateinit var tempRoot: Path
    private val fullBody = ByteArray(1024) { it.toByte() }

    @BeforeTest
    fun setup() {
        tempRoot = Files.createTempDirectory("downloader-test")
    }

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    private fun paths() = UpdatePaths(rootOverride = tempRoot)

    private fun computeSha(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun release(
        sha256: String = computeSha(fullBody),
        size: Long = fullBody.size.toLong(),
    ): UpdateRelease = UpdateRelease(
        version = "1.2.0",
        minSupportedVersion = "1.0.0",
        releaseNotes = "",
        installer = UpdateInstaller(
            url = "https://downloads.yalla.uz/a.msi",
            sha256 = sha256,
            size = size,
        ),
    )

    private fun mockClientFull(): HttpClient = HttpClient(MockEngine) {
        install(HttpTimeout)
        engine {
            addHandler { request ->
                val range = request.headers[HttpHeaders.Range]
                val (status, body) = if (range != null) {
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    HttpStatusCode.PartialContent to fullBody.copyOfRange(start, fullBody.size)
                } else {
                    HttpStatusCode.OK to fullBody
                }
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentLength, body.size.toString()),
                )
            }
        }
    }

    @Test
    fun `download writes full file and returns verified path`() = runTest {
        val rel = release()
        val downloader = UpdateDownloader(mockClientFull(), paths())
        val result = downloader.download(rel)
        assertTrue(result is DownloadResult.Success)
        val p = (result as DownloadResult.Success).msiFile
        assertTrue(p.exists())
        assertEquals(fullBody.size, p.readBytes().size)
    }

    @Test
    fun `download resumes from existing part file via Range header`() = runTest {
        val rel = release()
        val p = paths()
        p.partPathFor(rel.version).writeBytes(fullBody.copyOfRange(0, 512))
        p.metaPathFor(rel.version).toFile().writeText(
            """{"sha256":"${rel.installer.sha256}","size":${rel.installer.size}}"""
        )
        val downloader = UpdateDownloader(mockClientFull(), p)
        val result = downloader.download(rel)
        assertTrue(result is DownloadResult.Success)
        assertEquals(fullBody.size, (result as DownloadResult.Success).msiFile.readBytes().size)
    }

    @Test
    fun `download discards stale partial with different sha in meta`() = runTest {
        val rel = release()
        val p = paths()
        p.partPathFor(rel.version).writeBytes(byteArrayOf(99, 99, 99))
        p.metaPathFor(rel.version).toFile().writeText(
            """{"sha256":"${"b".repeat(64)}","size":${rel.installer.size}}"""
        )
        val downloader = UpdateDownloader(mockClientFull(), p)
        val result = downloader.download(rel)
        assertTrue(result is DownloadResult.Success)
        assertEquals(fullBody.size, (result as DownloadResult.Success).msiFile.readBytes().size)
    }

    @Test
    fun `download fails when sha mismatch after completion`() = runTest {
        val rel = release(sha256 = "f".repeat(64))
        val downloader = UpdateDownloader(mockClientFull(), paths())
        val result = downloader.download(rel)
        assertTrue(result is DownloadResult.VerifyFailed)
        assertFalse(paths().msiPathFor(rel.version).exists())
    }
}
