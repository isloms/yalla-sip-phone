package uz.yalla.sipphone.data.update

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists

object Sha256Verifier {

    /** Returns lowercase 64-char hex SHA256 of the file. */
    fun compute(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * Compare file hash to [expectedHex] (case-insensitive).
     * Returns false on missing file or hash mismatch. Never throws.
     */
    fun verify(file: Path, expectedHex: String): Boolean {
        if (!file.exists()) return false
        return runCatching { compute(file).equals(expectedHex, ignoreCase = true) }
            .getOrDefault(false)
    }
}
