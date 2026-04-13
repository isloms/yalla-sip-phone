package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

private val logger = KotlinLogging.logger {}

/**
 * Filesystem paths the updater uses.
 *
 * On Windows, resolves to `%LOCALAPPDATA%\YallaSipPhone\updates\`.
 * Elsewhere, falls back to `~/.yalla-sip-phone/updates/` (for dev on macOS/Linux).
 *
 * `rootOverride` exists only for tests.
 */
class UpdatePaths(rootOverride: Path? = null) {

    val updatesDir: Path = (rootOverride ?: resolveDefaultRoot()).resolve("updates")
        .also { it.createDirectories() }

    fun msiPathFor(version: String): Path =
        updatesDir.resolve("YallaSipPhone-$version.msi")

    fun partPathFor(version: String): Path =
        updatesDir.resolve("YallaSipPhone-$version.msi.part")

    fun metaPathFor(version: String): Path =
        updatesDir.resolve("YallaSipPhone-$version.msi.meta")

    fun installLogPath(): Path =
        updatesDir.resolve("install.log")

    /**
     * Remove `.part` / `.msi` / `.meta` files older than 24 hours — orphans
     * from prior crashes (invariant I5).
     */
    fun cleanupPartials() {
        if (!updatesDir.exists()) return
        val cutoff = Instant.now().minus(Duration.ofHours(24))
        updatesDir.listDirectoryEntries().forEach { entry ->
            if (!entry.isRegularFile()) return@forEach
            val name = entry.fileName.toString()
            val isJunk = name.endsWith(".msi.part") ||
                name.endsWith(".msi") ||
                name.endsWith(".msi.meta")
            if (!isJunk) return@forEach
            val mtime = runCatching { entry.getLastModifiedTime().toInstant() }.getOrNull()
            if (mtime != null && mtime.isBefore(cutoff)) {
                runCatching { Files.deleteIfExists(entry) }
                    .onSuccess { logger.info { "GC'd stale update file: $entry" } }
                    .onFailure { logger.warn(it) { "Failed to GC $entry" } }
            }
        }
    }

    companion object {
        private fun resolveDefaultRoot(): Path {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) {
                val local = System.getenv("LOCALAPPDATA")
                    ?: (System.getProperty("user.home") + "\\AppData\\Local")
                Path.of(local, "YallaSipPhone")
            } else {
                Path.of(System.getProperty("user.home"), ".yalla-sip-phone")
            }
        }
    }
}
