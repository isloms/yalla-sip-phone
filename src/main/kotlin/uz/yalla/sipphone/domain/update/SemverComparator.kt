package uz.yalla.sipphone.domain.update

/**
 * Strict MAJOR.MINOR.PATCH semver parser and comparator for the auto-updater.
 * The contract with the backend forbids pre-release suffixes or `v` prefix.
 */
data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {

    override fun compareTo(other: Semver): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

        fun parse(s: String): Semver {
            val match = PATTERN.matchEntire(s)
                ?: throw IllegalArgumentException("Not a strict semver: '$s'")
            return Semver(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
            )
        }

        fun parseOrNull(s: String): Semver? = runCatching { parse(s) }.getOrNull()
    }
}
