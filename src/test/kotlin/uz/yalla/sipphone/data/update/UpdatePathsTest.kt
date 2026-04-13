package uz.yalla.sipphone.data.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.setLastModifiedTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePathsTest {

    private lateinit var tempRoot: Path

    private fun newPaths(): UpdatePaths = UpdatePaths(rootOverride = tempRoot)

    @BeforeTest
    fun setup() {
        tempRoot = Files.createTempDirectory("yalla-updater-test")
    }

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `updatesDir exists after init`() {
        val p = newPaths()
        assertTrue(p.updatesDir.exists())
    }

    @Test
    fun `cleanupPartials removes stale part files older than 24h`() {
        val p = newPaths()
        val old = p.updatesDir.resolve("old.msi.part").createFile()
        old.setLastModifiedTime(FileTime.from(Instant.now().minus(48, ChronoUnit.HOURS)))
        val fresh = p.updatesDir.resolve("fresh.msi.part").createFile()
        p.cleanupPartials()
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `cleanupPartials removes stale msi files older than 24h`() {
        val p = newPaths()
        val old = p.updatesDir.resolve("YallaSipPhone-1.0.0.msi").createFile()
        old.setLastModifiedTime(FileTime.from(Instant.now().minus(48, ChronoUnit.HOURS)))
        p.cleanupPartials()
        assertFalse(old.exists())
    }

    @Test
    fun `msiPathFor uses version in filename`() {
        val p = newPaths()
        val path = p.msiPathFor("1.2.3")
        assertTrue(path.toString().endsWith("YallaSipPhone-1.2.3.msi"))
    }
}
