package uz.yalla.sipphone.domain.update

/**
 * Collapsed state machine per spec §7.
 *
 * Transitions:
 *   Idle → Checking → Idle (no update)
 *                   → Downloading → Verifying → ReadyToInstall → Installing → [process exit]
 *                                              → Failed(VERIFY) → Idle (with blacklist inc)
 *                   → Failed(CHECK) → Idle
 *
 * `WaitingForIdleCall` is NOT a state — it's a UI predicate:
 *   `canInstallNow = state is ReadyToInstall && callEngine.callState is Idle`
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val release: UpdateRelease, val bytesRead: Long, val total: Long) : UpdateState
    data class Verifying(val release: UpdateRelease) : UpdateState
    data class ReadyToInstall(val release: UpdateRelease, val msiPath: String) : UpdateState
    data class Installing(val release: UpdateRelease) : UpdateState
    data class Failed(val stage: Stage, val reason: String) : UpdateState {
        enum class Stage { CHECK, DOWNLOAD, VERIFY, INSTALL, UNTRUSTED_URL, MALFORMED_MANIFEST, DISK_FULL }
    }
}

enum class UpdateChannel(val value: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromValue(s: String?): UpdateChannel = when (s) {
            BETA.value -> BETA
            else -> STABLE
        }
    }
}
