package uz.yalla.sipphone.data.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha256VerifierTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("sha256test")
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `verify returns true on correct hash`() {
        val f = tempDir.resolve("a.bin")
        f.writeBytes(byteArrayOf(1, 2, 3, 4))
        // SHA256 of {01 02 03 04}
        val expected = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        assertTrue(Sha256Verifier.verify(f, expected))
    }

    @Test
    fun `verify returns false on tampered file`() {
        val f = tempDir.resolve("b.bin")
        f.writeBytes(byteArrayOf(1, 2, 3, 4))
        val wrong = "0".repeat(64)
        assertFalse(Sha256Verifier.verify(f, wrong))
    }

    @Test
    fun `verify returns false for missing file`() {
        val missing = tempDir.resolve("missing.bin")
        assertFalse(Sha256Verifier.verify(missing, "a".repeat(64)))
    }

    @Test
    fun `compute returns 64-char lowercase hex`() {
        val f = tempDir.resolve("c.bin")
        f.writeBytes(ByteArray(1024) { it.toByte() })
        val hash = Sha256Verifier.compute(f)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash))
    }
}
