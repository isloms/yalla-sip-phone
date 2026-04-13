package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uz.yalla.sipphone.domain.update.UpdateRelease
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.moveTo
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

sealed interface DownloadResult {
    data class Success(val msiFile: Path) : DownloadResult
    data object VerifyFailed : DownloadResult
    data class Failed(val cause: Throwable?) : DownloadResult
}

/**
 * Sidecar metadata so we can detect if an on-disk `.part` file is stale —
 * e.g. a prior download was interrupted and the server has since republished
 * the MSI with a new SHA.
 */
@Serializable
private data class PartMeta(val sha256: String, val size: Long)

class UpdateDownloader(
    private val client: HttpClient,
    private val paths: UpdatePaths,
) {

    data class DownloadProgress(val bytesRead: Long, val total: Long)

    private val _progress = MutableStateFlow(DownloadProgress(0, 0))
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    suspend fun download(release: UpdateRelease): DownloadResult {
        val part = paths.partPathFor(release.version)
        val meta = paths.metaPathFor(release.version)
        val finalMsi = paths.msiPathFor(release.version)

        val resumeFrom: Long = runCatching {
            if (!part.exists() || !meta.exists()) return@runCatching 0L
            val m = Json.decodeFromString<PartMeta>(Files.readString(meta))
            if (m.sha256 != release.installer.sha256 || m.size != release.installer.size) {
                logger.info { "Partial file stale (sha/size differ), discarding" }
                part.deleteIfExists()
                meta.deleteIfExists()
                return@runCatching 0L
            }
            part.fileSize()
        }.getOrDefault(0L)

        val total = release.installer.size
        _progress.value = DownloadProgress(resumeFrom, total)
        meta.writeText("""{"sha256":"${release.installer.sha256}","size":$total}""")

        try {
            client.prepareGet(release.installer.url) {
                if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
                timeout { requestTimeoutMillis = 10 * 60 * 1000L /* 10 min */ }
            }.execute { response ->
                if (!response.status.isSuccess() && response.status != HttpStatusCode.PartialContent) {
                    throw IllegalStateException("Download HTTP ${response.status.value}")
                }
                val append = resumeFrom > 0 && response.status == HttpStatusCode.PartialContent
                val openOpts: Array<StandardOpenOption> = if (append) {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                } else {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                Files.newOutputStream(part, *openOpts).use { out ->
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    val input = channel.toInputStream()
                    val buf = ByteArray(64 * 1024)
                    var written = if (append) resumeFrom else 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        _progress.value = DownloadProgress(written, total)
                    }
                }
            }
        } catch (t: Throwable) {
            logger.warn(t) { "Download failed" }
            return DownloadResult.Failed(t)
        }

        runCatching { finalMsi.deleteIfExists() }
        try {
            part.moveTo(finalMsi, overwrite = true)
        } catch (t: Throwable) {
            return DownloadResult.Failed(t)
        }

        val ok = Sha256Verifier.verify(finalMsi, release.installer.sha256)
        if (!ok) {
            logger.warn { "SHA256 mismatch after download" }
            finalMsi.deleteIfExists()
            meta.deleteIfExists()
            return DownloadResult.VerifyFailed
        }

        meta.deleteIfExists()
        return DownloadResult.Success(finalMsi)
    }
}
