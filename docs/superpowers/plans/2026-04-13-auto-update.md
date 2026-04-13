# Auto-Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Windows-only auto-update for yalla-sip-phone that polls a backend manifest endpoint hourly, downloads the MSI, verifies SHA256, waits for idle call, and installs via a C# bootstrapper — never interrupting an active SIP call.

**Architecture:** Kotlin `UpdateManager` (Koin singleton) polls `/api/v1/app-updates/latest`, parses a validated JSON envelope, resumable Ktor download to `%LOCALAPPDATA%\YallaSipPhone\updates\`, SHA256 verify, wait on `CallEngine.callState` for `Idle`, spawn `yalla-update-bootstrap.exe` (C# helper), then `exitProcess(0)`. Compose badge + dialog + hidden diagnostics panel for UX. TDD-first per project SDLC.

**Tech Stack:** Kotlin 2.1.20, Ktor 3.1.2, Koin 4.1.1, Compose Desktop 1.8.2, kotlinx.serialization, JUnit 5, Turbine, ktor-client-mock, C# .NET 8 (bootstrapper), WiX via Compose jpackage.

**Spec:** `docs/superpowers/specs/2026-04-13-auto-update-design.md`

---

## Phase 1: Foundation — Domain types + semver + strings

### Task 1: Add update-related string keys to StringResources interface

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/ui/strings/StringResources.kt`

- [ ] **Step 1: Append update strings to the interface**

Add at the end of `interface StringResources`:

```kotlin
    // Update
    val updateAvailableBadge: String
    val updateAvailableDialogTitle: String
    val updateInstallButton: String
    val updateLaterButton: String
    val updateWaitingForCallMessage: String
    val updateDownloadingMessage: String
    val updateVerifyingMessage: String
    val updateInstallingMessage: String
    val updateFailedVerify: String
    val updateFailedDownload: String
    val updateFailedDisk: String
    val updateFailedUntrustedUrl: String
    val updateFailedMalformedManifest: String
    val updateReleaseNotesHeader: String
    val updateCurrentVersion: String
    val updateChannelSwitchedStable: String
    val updateChannelSwitchedBeta: String
    val updateForcedUpgradeRequired: String
    val updateDiagnosticsTitle: String
    val updateDiagnosticsCopy: String
    val updateDiagnosticsCopied: String
    val updateDiagnosticsClose: String
    val updateDiagnosticsLastCheck: String
    val updateDiagnosticsLastError: String
    val updateDiagnosticsLogTail: String
    val updateDiagnosticsInstallId: String
    val updateDiagnosticsChannel: String
    val updateDiagnosticsState: String
```

- [ ] **Step 2: Run build to verify it fails in UzStrings/RuStrings**

Run: `./gradlew compileKotlin`
Expected: FAIL with "object 'UzStrings' is not abstract and does not implement abstract member"

### Task 2: Add update translations to UzStrings

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/ui/strings/UzStrings.kt`

- [ ] **Step 1: Add translations at the end of UzStrings**

Append before the closing brace:

```kotlin
    override val updateAvailableBadge = "Yangilanish mavjud"
    override val updateAvailableDialogTitle = "Yangilanish mavjud"
    override val updateInstallButton = "O'rnatish"
    override val updateLaterButton = "Keyinroq"
    override val updateWaitingForCallMessage = "Qo'ng'iroq tugashini kuting"
    override val updateDownloadingMessage = "Yuklanmoqda..."
    override val updateVerifyingMessage = "Tekshirilmoqda..."
    override val updateInstallingMessage = "O'rnatilmoqda..."
    override val updateFailedVerify = "Fayl buzilgan. Qayta urinib ko'ring."
    override val updateFailedDownload = "Yuklab bo'lmadi. Tarmoqni tekshiring."
    override val updateFailedDisk = "Diskda joy yetarli emas"
    override val updateFailedUntrustedUrl = "Ishonchsiz manba. IT xodimiga murojaat qiling."
    override val updateFailedMalformedManifest = "Server javobi noto'g'ri"
    override val updateReleaseNotesHeader = "Nima yangi?"
    override val updateCurrentVersion = "Joriy versiya"
    override val updateChannelSwitchedStable = "Kanal: stable"
    override val updateChannelSwitchedBeta = "Kanal: beta"
    override val updateForcedUpgradeRequired = "Bu versiya endi qo'llab-quvvatlanmaydi. Yangilanish majburiy."
    override val updateDiagnosticsTitle = "Diagnostika"
    override val updateDiagnosticsCopy = "Nusxa olish"
    override val updateDiagnosticsCopied = "Nusxa olindi"
    override val updateDiagnosticsClose = "Yopish"
    override val updateDiagnosticsLastCheck = "Oxirgi tekshiruv"
    override val updateDiagnosticsLastError = "Oxirgi xatolik"
    override val updateDiagnosticsLogTail = "Oxirgi loglar"
    override val updateDiagnosticsInstallId = "Qurilma ID"
    override val updateDiagnosticsChannel = "Kanal"
    override val updateDiagnosticsState = "Holat"
```

### Task 3: Add update translations to RuStrings

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/ui/strings/RuStrings.kt`

- [ ] **Step 1: Add Russian translations**

Append before the closing brace of `RuStrings`:

```kotlin
    override val updateAvailableBadge = "Доступно обновление"
    override val updateAvailableDialogTitle = "Доступно обновление"
    override val updateInstallButton = "Установить"
    override val updateLaterButton = "Позже"
    override val updateWaitingForCallMessage = "Дождитесь завершения звонка"
    override val updateDownloadingMessage = "Загрузка..."
    override val updateVerifyingMessage = "Проверка..."
    override val updateInstallingMessage = "Установка..."
    override val updateFailedVerify = "Файл поврежден. Попробуйте снова."
    override val updateFailedDownload = "Не удалось загрузить. Проверьте сеть."
    override val updateFailedDisk = "Недостаточно места на диске"
    override val updateFailedUntrustedUrl = "Ненадежный источник. Обратитесь в IT."
    override val updateFailedMalformedManifest = "Неверный ответ сервера"
    override val updateReleaseNotesHeader = "Что нового?"
    override val updateCurrentVersion = "Текущая версия"
    override val updateChannelSwitchedStable = "Канал: stable"
    override val updateChannelSwitchedBeta = "Канал: beta"
    override val updateForcedUpgradeRequired = "Эта версия больше не поддерживается. Обновление обязательно."
    override val updateDiagnosticsTitle = "Диагностика"
    override val updateDiagnosticsCopy = "Копировать"
    override val updateDiagnosticsCopied = "Скопировано"
    override val updateDiagnosticsClose = "Закрыть"
    override val updateDiagnosticsLastCheck = "Последняя проверка"
    override val updateDiagnosticsLastError = "Последняя ошибка"
    override val updateDiagnosticsLogTail = "Последние логи"
    override val updateDiagnosticsInstallId = "ID устройства"
    override val updateDiagnosticsChannel = "Канал"
    override val updateDiagnosticsState = "Состояние"
```

- [ ] **Step 2: Build to verify both classes compile**

Run: `./gradlew compileKotlin`
Expected: PASS

### Task 4: Create SemverComparator with tests

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/update/SemverComparator.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/domain/update/SemverComparatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/domain/update/SemverComparatorTest.kt
package uz.yalla.sipphone.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SemverComparatorTest {

    @Test
    fun `parse valid semver`() {
        val v = Semver.parse("1.2.3")
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
    }

    @Test
    fun `parse semver with leading v is rejected`() {
        assertFailsWith<IllegalArgumentException> { Semver.parse("v1.2.3") }
    }

    @Test
    fun `parse invalid semver throws`() {
        assertFailsWith<IllegalArgumentException> { Semver.parse("1.2") }
        assertFailsWith<IllegalArgumentException> { Semver.parse("1.2.3.4") }
        assertFailsWith<IllegalArgumentException> { Semver.parse("abc") }
        assertFailsWith<IllegalArgumentException> { Semver.parse("") }
    }

    @Test
    fun `1_2_0 greater than 1_1_9`() {
        assertTrue(Semver.parse("1.2.0") > Semver.parse("1.1.9"))
    }

    @Test
    fun `1_2_0 equal to 1_2_0`() {
        assertEquals(0, Semver.parse("1.2.0").compareTo(Semver.parse("1.2.0")))
    }

    @Test
    fun `0_9_99 less than 1_0_0`() {
        assertTrue(Semver.parse("0.9.99") < Semver.parse("1.0.0"))
    }

    @Test
    fun `major version dominates`() {
        assertTrue(Semver.parse("2.0.0") > Semver.parse("1.99.99"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "uz.yalla.sipphone.domain.update.SemverComparatorTest"`
Expected: FAIL with "unresolved reference: Semver"

- [ ] **Step 3: Write the implementation**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/update/SemverComparator.kt
package uz.yalla.sipphone.domain.update

/**
 * Minimal strict semver parser and comparator for the auto-updater.
 *
 * Accepts only `MAJOR.MINOR.PATCH` with non-negative integers — no `v` prefix,
 * no pre-release suffix, no build metadata. This is intentional; the contract
 * with the backend team forbids anything else (spec §6.6).
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "uz.yalla.sipphone.domain.update.SemverComparatorTest"`
Expected: PASS (7 tests)

### Task 5: Create UpdateManifest DTO + validation

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/update/UpdateManifest.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/domain/update/UpdateManifestTest.kt`

- [ ] **Step 1: Write failing tests for DTO validation**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/domain/update/UpdateManifestTest.kt
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
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with size too large`() {
        val r = fakeRelease(size = 3L * 1024 * 1024 * 1024)  // 3 GiB
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with wrong sha256 length`() {
        val r = fakeRelease(sha256 = "abc123")
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with non-hex sha256`() {
        val r = fakeRelease(sha256 = "g".repeat(64))
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with unparseable version`() {
        val r = fakeRelease(version = "not-a-version")
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects manifest with minSupportedVersion greater than version`() {
        val r = fakeRelease(version = "1.2.0", minSupportedVersion = "2.0.0")
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects http url`() {
        val r = fakeRelease(url = "http://downloads.yalla.uz/a.msi")
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects url host not in allow-list`() {
        val r = fakeRelease(url = "https://evil.example.com/a.msi")
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate accepts well-formed manifest`() {
        val r = fakeRelease()
        val result = ManifestValidator.validate(r)
        assertTrue(result is ManifestValidation.Valid)
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
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "uz.yalla.sipphone.domain.update.UpdateManifestTest"`
Expected: FAIL (unresolved references)

- [ ] **Step 3: Write the implementation**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/update/UpdateManifest.kt
package uz.yalla.sipphone.domain.update

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * JSON envelope returned by `GET /api/v1/app-updates/latest`.
 *
 * Always 200 OK; `updateAvailable = false` means "you are current".
 * See spec §6 for the contract.
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
 * The allow-list of hosts the client will download MSIs from.
 * Hard-coded in source per spec §6.7 — the single strongest
 * cert-free defense against the `url` field pointing off-domain.
 *
 * TODO(OQ2): confirm final hosts with backend team.
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
 *
 * Bad manifests must not brick clients — we return `Invalid` with a reason
 * and the caller treats it as `NoUpdate` + logs the reason.
 */
object ManifestValidator {

    fun validate(release: UpdateRelease): ManifestValidation {
        // Version and minSupportedVersion must be strict semver.
        val version = Semver.parseOrNull(release.version)
            ?: return ManifestValidation.Invalid("version not semver: ${release.version}")
        val minSupported = Semver.parseOrNull(release.minSupportedVersion)
            ?: return ManifestValidation.Invalid("minSupportedVersion not semver: ${release.minSupportedVersion}")

        if (minSupported > version) {
            return ManifestValidation.Invalid(
                "minSupportedVersion ($minSupported) > version ($version)"
            )
        }

        // Installer size sanity.
        if (release.installer.size <= 0) {
            return ManifestValidation.Invalid("size must be positive, got ${release.installer.size}")
        }
        if (release.installer.size >= MAX_SIZE_BYTES) {
            return ManifestValidation.Invalid("size ${release.installer.size} exceeds 2 GiB cap")
        }

        // SHA256 must be 64 lowercase hex chars.
        if (!SHA256_HEX.matches(release.installer.sha256)) {
            return ManifestValidation.Invalid("sha256 not 64-char lowercase hex: ${release.installer.sha256}")
        }

        // URL must be HTTPS with host in the allow-list.
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
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests "uz.yalla.sipphone.domain.update.UpdateManifestTest"`
Expected: PASS (11 tests)

### Task 6: Create UpdateState sealed interface + UpdateChannel enum

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/update/UpdateState.kt`

- [ ] **Step 1: Write the implementation (no separate test — covered by UpdateManagerTest)**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/update/UpdateState.kt
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
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: PASS

- [ ] **Step 3: Commit Phase 1**

```bash
git add src/main/kotlin/uz/yalla/sipphone/domain/update \
        src/main/kotlin/uz/yalla/sipphone/ui/strings \
        src/test/kotlin/uz/yalla/sipphone/domain/update
git commit -m "feat(update): domain types, semver, manifest validation, strings"
```

---

## Phase 2: Data layer

### Task 7: Create UpdatePaths helper

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/update/UpdatePaths.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/data/update/UpdatePathsTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/update/UpdatePathsTest.kt
package uz.yalla.sipphone.data.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.setLastModifiedTime
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePathsTest {

    private lateinit var tempRoot: Path

    private fun newPaths(): UpdatePaths = UpdatePaths(rootOverride = tempRoot)

    @org.junit.jupiter.api.BeforeEach
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
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdatePathsTest"`
Expected: FAIL

- [ ] **Step 3: Write the implementation**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/update/UpdatePaths.kt
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
     * Remove `.part` / `.msi` files older than 24 hours — orphans from prior
     * crashes (invariant I5).
     */
    fun cleanupPartials() {
        if (!updatesDir.exists()) return
        val cutoff = Instant.now().minus(Duration.ofHours(24))
        updatesDir.listDirectoryEntries().forEach { entry ->
            if (!entry.isRegularFile()) return@forEach
            val isJunk = entry.fileName.toString().let { n ->
                n.endsWith(".msi.part") || n.endsWith(".msi") || n.endsWith(".msi.meta")
            }
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
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdatePathsTest"`
Expected: PASS (4 tests)

### Task 8: Create Sha256Verifier

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/update/Sha256Verifier.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/data/update/Sha256VerifierTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/update/Sha256VerifierTest.kt
package uz.yalla.sipphone.data.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha256VerifierTest {

    private lateinit var tempDir: Path

    @org.junit.jupiter.api.BeforeEach
    fun setup() { tempDir = Files.createTempDirectory("sha256test") }

    @AfterTest
    fun cleanup() { tempDir.toFile().deleteRecursively() }

    @Test
    fun `verify returns true on correct hash`() {
        val f = tempDir.resolve("a.bin")
        f.writeBytes(byteArrayOf(1, 2, 3, 4))
        // SHA256 of bytes {01 02 03 04}
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
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.Sha256VerifierTest"`
Expected: FAIL

- [ ] **Step 3: Implementation**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/update/Sha256Verifier.kt
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
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.Sha256VerifierTest"`
Expected: PASS (4 tests)

### Task 9: Create UpdateApi — Ktor call + envelope parse + validation

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/update/UpdateApi.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/data/update/UpdateApiTest.kt`

- [ ] **Step 1: Write failing tests using ktor-client-mock**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/update/UpdateApiTest.kt
package uz.yalla.sipphone.data.update

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import uz.yalla.sipphone.domain.update.UpdateChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateApiTest {

    private fun clientReturning(status: HttpStatusCode, body: String): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `check returns NoUpdate for updateAvailable false`() = runTest {
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, """{"updateAvailable":false}"""), baseUrl = "https://api/")
        val result = api.check(channel = UpdateChannel.STABLE, currentVersion = "1.0.0", installId = "iid")
        assertTrue(result is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun `check returns Available with parsed release for updateAvailable true`() = runTest {
        val body = """
          {"updateAvailable":true,"release":{"version":"1.2.0","minSupportedVersion":"1.0.0","releaseNotes":"x","installer":{"url":"https://downloads.yalla.uz/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, body), baseUrl = "https://api/")
        val result = api.check(channel = UpdateChannel.STABLE, currentVersion = "1.0.0", installId = "iid")
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("1.2.0", (result as UpdateCheckResult.Available).release.version)
    }

    @Test
    fun `check returns NoUpdate when malformed manifest is rejected`() = runTest {
        val body = """
          {"updateAvailable":true,"release":{"version":"not-semver","minSupportedVersion":"1.0.0","releaseNotes":"","installer":{"url":"https://downloads.yalla.uz/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, body), baseUrl = "https://api/")
        val result = api.check(channel = UpdateChannel.STABLE, currentVersion = "1.0.0", installId = "iid")
        assertTrue(result is UpdateCheckResult.Malformed)
    }

    @Test
    fun `check returns NoUpdate when url host not in allowlist`() = runTest {
        val body = """
          {"updateAvailable":true,"release":{"version":"1.2.0","minSupportedVersion":"1.0.0","releaseNotes":"","installer":{"url":"https://evil.com/a.msi","sha256":"${"a".repeat(64)}","size":100}}}
        """.trimIndent()
        val api = UpdateApi(clientReturning(HttpStatusCode.OK, body), baseUrl = "https://api/")
        val result = api.check(channel = UpdateChannel.STABLE, currentVersion = "1.0.0", installId = "iid")
        assertTrue(result is UpdateCheckResult.Malformed)
    }

    @Test
    fun `check returns Error on 5xx`() = runTest {
        val api = UpdateApi(clientReturning(HttpStatusCode.InternalServerError, ""), baseUrl = "https://api/")
        val result = api.check(channel = UpdateChannel.STABLE, currentVersion = "1.0.0", installId = "iid")
        assertTrue(result is UpdateCheckResult.Error)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdateApiTest"`
Expected: FAIL

- [ ] **Step 3: Implementation**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/update/UpdateApi.kt
package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import uz.yalla.sipphone.domain.update.ManifestValidation
import uz.yalla.sipphone.domain.update.ManifestValidator
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateEnvelope
import uz.yalla.sipphone.domain.update.UpdateRelease

private val logger = KotlinLogging.logger {}

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult
    data class Available(val release: UpdateRelease) : UpdateCheckResult
    data class Malformed(val reason: String) : UpdateCheckResult
    data class Error(val cause: Throwable?) : UpdateCheckResult
}

/**
 * Calls `GET {baseUrl}app-updates/latest` with the headers mandated by spec §6.1.
 * Returns a typed [UpdateCheckResult] — never throws.
 */
class UpdateApi(
    private val client: HttpClient,
    private val baseUrl: String,
) {

    suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String = "windows",
    ): UpdateCheckResult {
        val url = baseUrl.trimEnd('/') + "/app-updates/latest"
        val response: HttpResponse = try {
            client.get(url) {
                header("X-App-Version", currentVersion)
                header("X-App-Platform", platform)
                header("X-App-Channel", channel.value)
                header("X-Install-Id", installId)
                header("User-Agent", "YallaSipPhone/$currentVersion ($platform)")
            }
        } catch (t: Throwable) {
            logger.warn(t) { "Update check network failure" }
            return UpdateCheckResult.Error(t)
        }

        if (!response.status.isSuccess()) {
            logger.warn { "Update check HTTP ${response.status.value}" }
            return UpdateCheckResult.Error(null)
        }

        val envelope: UpdateEnvelope = try {
            response.body()
        } catch (t: Throwable) {
            logger.warn(t) { "Update check malformed JSON" }
            return UpdateCheckResult.Malformed("unparseable JSON: ${t.message}")
        }

        if (!envelope.updateAvailable || envelope.release == null) {
            return UpdateCheckResult.NoUpdate
        }

        return when (val v = ManifestValidator.validate(envelope.release)) {
            is ManifestValidation.Valid -> UpdateCheckResult.Available(envelope.release)
            is ManifestValidation.Invalid -> {
                logger.warn { "Manifest rejected: ${v.reason}" }
                UpdateCheckResult.Malformed(v.reason)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdateApiTest"`
Expected: PASS (5 tests)

### Task 10: Create UpdateDownloader with Range-based resume

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/update/UpdateDownloader.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/data/update/UpdateDownloaderTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/update/UpdateDownloaderTest.kt
package uz.yalla.sipphone.data.update

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.update.UpdateInstaller
import uz.yalla.sipphone.domain.update.UpdateRelease
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloaderTest {

    private lateinit var tempRoot: Path
    private val fullBody = ByteArray(1024) { it.toByte() }
    private val sha = Sha256Verifier.compute(tmpWrite(tempFile(), fullBody))

    // The test reassigns tempRoot before each test so `tempFile()` used at class init
    // would be wrong; recompute sha in-test instead.

    @org.junit.jupiter.api.BeforeEach
    fun setup() {
        tempRoot = Files.createTempDirectory("downloader-test")
    }

    @AfterTest
    fun cleanup() { tempRoot.toFile().deleteRecursively() }

    private fun paths() = UpdatePaths(rootOverride = tempRoot)

    private fun release(size: Long = fullBody.size.toLong()): UpdateRelease {
        val hash = Sha256Verifier.compute(tmpWrite(tempFile("release-ref-"), fullBody))
        return UpdateRelease(
            version = "1.2.0",
            minSupportedVersion = "1.0.0",
            releaseNotes = "",
            installer = UpdateInstaller(
                url = "https://downloads.yalla.uz/a.msi",
                sha256 = hash,
                size = size,
            ),
        )
    }

    private fun tempFile(prefix: String = "bytes-"): Path =
        Files.createTempFile(tempRoot, prefix, ".bin")

    private fun tmpWrite(p: Path, bytes: ByteArray): Path {
        p.writeBytes(bytes); return p
    }

    private fun mockClientFull(): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                // Honour Range if present
                val range = request.headers["Range"]
                val (status, body) = if (range != null) {
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    HttpStatusCode.PartialContent to fullBody.copyOfRange(start, fullBody.size)
                } else {
                    HttpStatusCode.OK to fullBody
                }
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(
                        HttpHeaders.ContentLength, body.size.toString(),
                    ),
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
        val path = (result as DownloadResult.Success).msiFile
        assertTrue(path.exists())
        assertEquals(fullBody.size, path.readBytes().size)
    }

    @Test
    fun `download resumes from existing part file via Range header`() = runTest {
        val rel = release()
        val p = paths()
        val partial = p.partPathFor(rel.version)
        partial.writeBytes(fullBody.copyOfRange(0, 512)) // first half already on disk
        p.metaPathFor(rel.version).writeBytes(
            """{"sha256":"${rel.installer.sha256}","size":${rel.installer.size}}""".toByteArray(),
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
        p.metaPathFor(rel.version).writeBytes(
            """{"sha256":"${"b".repeat(64)}","size":${rel.installer.size}}""".toByteArray(),
        )
        val downloader = UpdateDownloader(mockClientFull(), p)
        val result = downloader.download(rel)
        assertTrue(result is DownloadResult.Success)
        // Full body should have been re-downloaded
        assertEquals(fullBody.size, (result as DownloadResult.Success).msiFile.readBytes().size)
    }

    @Test
    fun `download fails when sha mismatch after completion`() = runTest {
        val rel = release().copy(installer = UpdateInstaller("https://downloads.yalla.uz/a.msi", "f".repeat(64), fullBody.size.toLong()))
        val downloader = UpdateDownloader(mockClientFull(), paths())
        val result = downloader.download(rel)
        assertTrue(result is DownloadResult.VerifyFailed)
        // Corrupt .msi must have been deleted
        assertFalse(paths().msiPathFor(rel.version).exists())
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdateDownloaderTest"`
Expected: FAIL (unresolved references)

- [ ] **Step 3: Implementation**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/update/UpdateDownloader.kt
package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
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
 * Sidecar meta file so we can detect if a `.part` file is stale
 * (e.g. a previous download was interrupted and then the manifest
 * re-published the MSI with a new SHA).
 */
@Serializable
private data class PartMeta(val sha256: String, val size: Long)

class UpdateDownloader(
    private val client: HttpClient,
    private val paths: UpdatePaths,
) {

    private val _progress = MutableStateFlow(DownloadProgress(0, 0))
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    data class DownloadProgress(val bytesRead: Long, val total: Long)

    suspend fun download(release: UpdateRelease): DownloadResult {
        val part = paths.partPathFor(release.version)
        val meta = paths.metaPathFor(release.version)
        val finalMsi = paths.msiPathFor(release.version)

        // Reuse .part only if the sidecar matches the current manifest.
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

        // Write new meta sidecar every attempt — harmless if same.
        meta.writeText("""{"sha256":"${release.installer.sha256}","size":$total}""")

        try {
            client.prepareGet(release.installer.url) {
                if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
                timeout { requestTimeoutMillis = 10 * 60 * 1000 /* 10 min */ }
            }.execute { response ->
                if (!response.status.isSuccess() && response.status != HttpStatusCode.PartialContent) {
                    throw IllegalStateException("Download HTTP ${response.status.value}")
                }
                val append = resumeFrom > 0 && response.status == HttpStatusCode.PartialContent
                val openOpts = if (append) {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                } else {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                Files.newOutputStream(part, *openOpts).use { out ->
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    val buf = ByteArray(64 * 1024)
                    var written = if (append) resumeFrom else 0L
                    while (!channel.isClosedForRead) {
                        val n = channel.readAvailable(buf, 0, buf.size)
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

        // Rename .part -> .msi and verify.
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
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdateDownloaderTest"`
Expected: PASS (4 tests)

### Task 11: Create MsiBootstrapperInstaller with manual test doc

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/update/MsiBootstrapperInstaller.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/data/update/MsiBootstrapperInstallerTest.kt`

- [ ] **Step 1: Write failing test for ADS-stripping and command construction**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/update/MsiBootstrapperInstallerTest.kt
package uz.yalla.sipphone.data.update

import uz.yalla.sipphone.domain.update.UpdateInstaller
import uz.yalla.sipphone.domain.update.UpdateRelease
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsiBootstrapperInstallerTest {

    private lateinit var tempRoot: Path

    @org.junit.jupiter.api.BeforeEach
    fun setup() { tempRoot = Files.createTempDirectory("installer-test") }

    @AfterTest
    fun cleanup() { tempRoot.toFile().deleteRecursively() }

    @Test
    fun `command is built with bootstrapper and args`() {
        val installer = MsiBootstrapperInstaller(
            bootstrapperPathOverride = tempRoot.resolve("yalla-update-bootstrap.exe"),
            installDirOverride = tempRoot.resolve("app"),
            processLauncher = FakeProcessLauncher(),
        )
        val msi = tempRoot.resolve("YallaSipPhone-1.2.0.msi").also { it.writeBytes(byteArrayOf(1, 2, 3)) }

        val cmd = installer.buildCommand(
            msiPath = msi,
            expectedSha256 = "a".repeat(64),
            logPath = tempRoot.resolve("install.log"),
            parentPid = 42,
        )
        assertEquals(tempRoot.resolve("yalla-update-bootstrap.exe").toString(), cmd[0])
        assertTrue("--msi" in cmd)
        assertTrue("--install-dir" in cmd)
        assertTrue("--parent-pid" in cmd)
        assertTrue("--expected-sha256" in cmd)
        assertTrue("--log" in cmd)
    }
}

class FakeProcessLauncher : ProcessLauncher {
    var lastCommand: List<String>? = null
    override fun launch(command: List<String>) { lastCommand = command }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.MsiBootstrapperInstallerTest"`
Expected: FAIL

- [ ] **Step 3: Implementation**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/update/MsiBootstrapperInstaller.kt
package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

/** Abstraction so tests can verify command construction without spawning msiexec. */
interface ProcessLauncher {
    fun launch(command: List<String>)
}

class RealProcessLauncher : ProcessLauncher {
    override fun launch(command: List<String>) {
        ProcessBuilder(command)
            .inheritIO()
            .start()
    }
}

/**
 * Spawns the C# bootstrapper and exits the JVM. See spec §11 for the
 * bootstrapper's behaviour.
 *
 * The bootstrapper location defaults to the `bootstrapper/` subdirectory
 * next to `yalla-sip-phone.exe`, but can be overridden for tests.
 */
class MsiBootstrapperInstaller(
    private val bootstrapperPathOverride: Path? = null,
    private val installDirOverride: Path? = null,
    private val processLauncher: ProcessLauncher = RealProcessLauncher(),
) {

    private val bootstrapperPath: Path
        get() = bootstrapperPathOverride ?: defaultBootstrapperPath()

    private val installDir: Path
        get() = installDirOverride ?: defaultInstallDir()

    fun buildCommand(
        msiPath: Path,
        expectedSha256: String,
        logPath: Path,
        parentPid: Long = currentPid(),
    ): List<String> = listOf(
        bootstrapperPath.toString(),
        "--msi", msiPath.toString(),
        "--install-dir", installDir.toString(),
        "--parent-pid", parentPid.toString(),
        "--expected-sha256", expectedSha256,
        "--log", logPath.toString(),
    )

    /**
     * Strip Mark-of-the-Web from the downloaded MSI (invariant I18).
     * This is a no-op on non-Windows and is safe to call on Windows —
     * deletion of the `Zone.Identifier` alternate data stream.
     */
    fun stripMarkOfTheWeb(msiPath: Path) {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) return
        val adsPath = Path.of("${msiPath}:Zone.Identifier")
        runCatching { adsPath.deleteIfExists() }
            .onSuccess { logger.info { "Stripped Zone.Identifier from $msiPath" } }
            .onFailure { logger.warn(it) { "Failed to strip Zone.Identifier (non-fatal)" } }
    }

    /**
     * Launches the bootstrapper and does NOT return control in the happy
     * path — the caller is expected to call `exitProcess(0)` immediately.
     */
    fun install(msiPath: Path, expectedSha256: String, logPath: Path) {
        if (!bootstrapperPath.exists()) {
            logger.error { "Bootstrapper missing at $bootstrapperPath — cannot install" }
            throw IllegalStateException("Bootstrapper not found: $bootstrapperPath")
        }
        if (!msiPath.exists()) {
            throw IllegalStateException("MSI missing: $msiPath")
        }
        stripMarkOfTheWeb(msiPath)
        val cmd = buildCommand(msiPath, expectedSha256, logPath)
        logger.info { "Launching bootstrapper: $cmd" }
        processLauncher.launch(cmd)
    }

    companion object {
        private fun defaultBootstrapperPath(): Path {
            // In dev, read from the classpath resource; at runtime this resolves next
            // to the installed exe.
            return Path.of(System.getProperty("user.dir"), "bootstrapper", "yalla-update-bootstrap.exe")
        }

        private fun defaultInstallDir(): Path {
            // On Windows per-user install, this is %LOCALAPPDATA%\YallaSipPhone
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) {
                val local = System.getenv("LOCALAPPDATA")
                    ?: (System.getProperty("user.home") + "\\AppData\\Local")
                Path.of(local, "YallaSipPhone")
            } else {
                Path.of(System.getProperty("user.dir"))
            }
        }

        private fun currentPid(): Long = ProcessHandle.current().pid()
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.MsiBootstrapperInstallerTest"`
Expected: PASS

- [ ] **Step 5: Commit Phase 2**

```bash
git add src/main/kotlin/uz/yalla/sipphone/data/update \
        src/test/kotlin/uz/yalla/sipphone/data/update
git commit -m "feat(update): data layer — paths, sha256, api, downloader, installer"
```

---

## Phase 3: Orchestrator

### Task 12: Extend AppSettings with updateChannel and installId

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/settings/AppSettings.kt`

- [ ] **Step 1: Add properties**

Append inside the `AppSettings` class before the closing brace:

```kotlin
    var updateChannel: String
        get() = settings.getString("update_channel", "stable")
        set(value) = settings.putString("update_channel", value)

    val installId: String
        get() {
            val existing = settings.getStringOrNull("install_id")
            if (existing != null) return existing
            val fresh = java.util.UUID.randomUUID().toString()
            settings.putString("install_id", fresh)
            return fresh
        }
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: PASS

### Task 13: Create UpdateManager with state machine + tests

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/update/UpdateManager.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/data/update/UpdateManagerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/update/UpdateManagerTest.kt
package uz.yalla.sipphone.data.update

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateInstaller
import uz.yalla.sipphone.domain.update.UpdateRelease
import uz.yalla.sipphone.domain.update.UpdateState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    private lateinit var tempRoot: Path
    private val callState = MutableStateFlow<CallState>(CallState.Idle)
    private val fakeApi = FakeUpdateApi()
    private val fakeDownloader = FakeDownloader()
    private val fakeInstaller = RecordingInstaller()

    @org.junit.jupiter.api.BeforeEach
    fun setup() {
        tempRoot = Files.createTempDirectory("manager-test")
    }

    @AfterTest
    fun cleanup() { tempRoot.toFile().deleteRecursively() }

    private fun manager(scope: CoroutineScope): UpdateManager {
        val paths = UpdatePaths(rootOverride = tempRoot)
        return UpdateManager(
            scope = scope,
            api = fakeApi,
            downloader = fakeDownloader,
            installer = fakeInstaller,
            paths = paths,
            callState = callState.asStateFlow(),
            currentVersion = "1.0.0",
            channelProvider = { UpdateChannel.STABLE },
            installIdProvider = { "install-id" },
            pollIntervalMillis = 1_000_000L, // effectively disabled; we call checkNow() manually
        )
    }

    @Test
    fun `initial state is Idle`() = runTest {
        val m = manager(this)
        assertEquals(UpdateState.Idle, m.state.value)
    }

    @Test
    fun `checkNow transitions through happy path to ReadyToInstall`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        val msi = tempRoot.resolve("YallaSipPhone-1.2.0.msi").also { it.writeBytes(byteArrayOf(1)) }
        fakeDownloader.nextResult = DownloadResult.Success(msi)

        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()

        val s = m.state.value
        assertIs<UpdateState.ReadyToInstall>(s)
        assertEquals("1.2.0", s.release.version)
    }

    @Test
    fun `checkNow stays Idle when no update available`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.NoUpdate
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
    }

    @Test
    fun `checkNow transitions to Failed on malformed manifest`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Malformed("bad sha")
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        val s = m.state.value
        assertIs<UpdateState.Failed>(s)
        assertEquals(UpdateState.Failed.Stage.MALFORMED_MANIFEST, s.stage)
    }

    @Test
    fun `checkNow transitions to Failed on verify failure`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        fakeDownloader.nextResult = DownloadResult.VerifyFailed
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        val s = m.state.value
        assertIs<UpdateState.Failed>(s)
        assertEquals(UpdateState.Failed.Stage.VERIFY, s.stage)
    }

    @Test
    fun `check is skipped while call is active (invariant I16)`() = runTest {
        callState.value = CallState.Active(
            callId = "c1", remoteNumber = "", remoteName = null,
            isOutbound = false, isMuted = false, isOnHold = false,
        )
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
        assertEquals(0, fakeApi.callCount)
    }

    @Test
    fun `version lower than current is refused (invariant I15)`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(
            fakeRelease(version = "0.9.0")
        )
        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, m.state.value)
    }

    @Test
    fun `confirmInstall while call active waits for idle`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        val msi = tempRoot.resolve("YallaSipPhone-1.2.0.msi").also { it.writeBytes(byteArrayOf(1)) }
        fakeDownloader.nextResult = DownloadResult.Success(msi)

        val m = manager(this)
        m.checkNow()
        advanceUntilIdle()
        assertIs<UpdateState.ReadyToInstall>(m.state.value)

        // Simulate a live call
        callState.value = CallState.Active(
            callId = "c1", remoteNumber = "", remoteName = null,
            isOutbound = false, isMuted = false, isOnHold = false,
        )
        m.confirmInstall()
        advanceUntilIdle()
        // Installer was NOT called yet.
        assertEquals(0, fakeInstaller.installCount)

        // End the call
        callState.value = CallState.Idle
        advanceUntilIdle()
        // Now it installed.
        assertEquals(1, fakeInstaller.installCount)
    }

    @Test
    fun `three consecutive verify failures blacklist the version`() = runTest {
        fakeApi.nextResult = UpdateCheckResult.Available(fakeRelease())
        fakeDownloader.nextResult = DownloadResult.VerifyFailed

        val m = manager(this)
        repeat(3) {
            m.checkNow(); advanceUntilIdle()
        }
        // Fourth attempt should be Idle (blacklisted) even though API would still return Available
        fakeApi.callCountBeforeBlacklistCheck = fakeApi.callCount
        m.checkNow(); advanceUntilIdle()
        // Manager should have skipped past download stage
        assertEquals(UpdateState.Idle, m.state.value)
    }

    private fun fakeRelease(version: String = "1.2.0"): UpdateRelease = UpdateRelease(
        version = version,
        minSupportedVersion = "1.0.0",
        releaseNotes = "notes",
        installer = UpdateInstaller(
            url = "https://downloads.yalla.uz/a.msi",
            sha256 = "a".repeat(64),
            size = 100,
        ),
    )
}

private class FakeUpdateApi : UpdateApiContract {
    var nextResult: UpdateCheckResult = UpdateCheckResult.NoUpdate
    var callCount = 0
    var callCountBeforeBlacklistCheck = 0
    override suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String,
    ): UpdateCheckResult {
        callCount++
        return nextResult
    }
}

private class FakeDownloader : UpdateDownloaderContract {
    var nextResult: DownloadResult = DownloadResult.Failed(null)
    override suspend fun download(release: UpdateRelease): DownloadResult = nextResult
}

private class RecordingInstaller : InstallerContract {
    var installCount = 0
    override fun install(msiPath: Path, expectedSha256: String, logPath: Path) {
        installCount++
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdateManagerTest"`
Expected: FAIL (missing UpdateManager, contracts)

- [ ] **Step 3: Write UpdateManager and contract interfaces**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/update/UpdateManager.kt
package uz.yalla.sipphone.data.update

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.Semver
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateRelease
import uz.yalla.sipphone.domain.update.UpdateState
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/** Abstractions on the dependencies so the manager is testable without Ktor or msiexec. */
interface UpdateApiContract {
    suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String = "windows",
    ): UpdateCheckResult
}

interface UpdateDownloaderContract {
    suspend fun download(release: UpdateRelease): DownloadResult
}

interface InstallerContract {
    fun install(msiPath: Path, expectedSha256: String, logPath: Path)
}

// Bridge the concrete data-layer types into the contracts. This adapter
// exists so the production graph binds the real classes without changing them.
fun UpdateApi.asContract(): UpdateApiContract = object : UpdateApiContract {
    override suspend fun check(
        channel: UpdateChannel,
        currentVersion: String,
        installId: String,
        platform: String,
    ): UpdateCheckResult = this@asContract.check(channel, currentVersion, installId, platform)
}

fun UpdateDownloader.asContract(): UpdateDownloaderContract = object : UpdateDownloaderContract {
    override suspend fun download(release: UpdateRelease): DownloadResult =
        this@asContract.download(release)
}

fun MsiBootstrapperInstaller.asContract(): InstallerContract = object : InstallerContract {
    override fun install(msiPath: Path, expectedSha256: String, logPath: Path) {
        this@asContract.install(msiPath, expectedSha256, logPath)
    }
}

/**
 * Orchestrates the update flow. See spec §7 for the state machine.
 *
 * Thread model: owns a [CoroutineScope] injected by Koin. All transitions
 * run on that scope; the state is read by the UI via [state].
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private val api: UpdateApiContract,
    private val downloader: UpdateDownloaderContract,
    private val installer: InstallerContract,
    private val paths: UpdatePaths,
    private val callState: StateFlow<CallState>,
    private val currentVersion: String,
    private val channelProvider: () -> UpdateChannel,
    private val installIdProvider: () -> String,
    private val pollIntervalMillis: Long = 60 * 60 * 1000L,
    private val exitProcess: (Int) -> Unit = { code -> kotlin.system.exitProcess(code) },
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null
    private var verifyFailureCount: Int = 0
    private var blacklistedVersion: String? = null
    private var lastCheckEpochMillis: Long = 0
    private var lastError: String? = null
    @Volatile
    private var installInProgress: Boolean = false

    fun lastCheckMillis(): Long = lastCheckEpochMillis
    fun lastErrorMessage(): String? = lastError
    fun isInstallInProgress(): Boolean = installInProgress

    fun start() {
        if (!running.compareAndSet(false, true)) return
        paths.cleanupPartials()
        loopJob = scope.launch {
            // App-start immediate check + periodic loop
            while (isActive) {
                runCheckCycle()
                delay(jitterDelay())
            }
        }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
    }

    /** Manually trigger a single check cycle (bypassing the delay). */
    fun checkNow() {
        scope.launch { runCheckCycle() }
    }

    /**
     * Called by the UI when the operator clicks "Install".
     * Waits until the call state is Idle, then triggers the install.
     */
    fun confirmInstall() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        scope.launch {
            callState.first { it is CallState.Idle }
            // Double-check: a call might have started again since the await.
            if (callState.value !is CallState.Idle) return@launch
            _state.value = UpdateState.Installing(ready.release)
            installInProgress = true
            runCatching {
                installer.install(
                    msiPath = Path.of(ready.msiPath),
                    expectedSha256 = ready.release.installer.sha256,
                    logPath = paths.installLogPath(),
                )
                exitProcess(0)
            }.onFailure { t ->
                logger.error(t) { "Installer failed to launch" }
                installInProgress = false
                lastError = t.message
                _state.value = UpdateState.Failed(UpdateState.Failed.Stage.INSTALL, t.message ?: "install failed")
            }
        }
    }

    /** For UI: "Later" button — reset state to Idle so the next tick re-checks. */
    fun dismiss() {
        if (_state.value is UpdateState.ReadyToInstall || _state.value is UpdateState.Failed) {
            _state.value = UpdateState.Idle
        }
    }

    private suspend fun runCheckCycle() {
        // Invariant I16: never poll/download during an active call.
        if (callState.value !is CallState.Idle) {
            logger.debug { "Skipping update check: call not idle" }
            return
        }
        if (installInProgress) return

        lastCheckEpochMillis = System.currentTimeMillis()
        _state.value = UpdateState.Checking

        val result = api.check(
            channel = channelProvider(),
            currentVersion = currentVersion,
            installId = installIdProvider(),
        )

        when (result) {
            is UpdateCheckResult.NoUpdate -> {
                _state.value = UpdateState.Idle
            }
            is UpdateCheckResult.Malformed -> {
                lastError = result.reason
                _state.value = UpdateState.Failed(UpdateState.Failed.Stage.MALFORMED_MANIFEST, result.reason)
                // Keep it visible briefly, then reset.
                delay(1500)
                if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
            }
            is UpdateCheckResult.Error -> {
                lastError = result.cause?.message ?: "network error"
                _state.value = UpdateState.Idle
            }
            is UpdateCheckResult.Available -> handleAvailable(result.release)
        }
    }

    private suspend fun handleAvailable(release: UpdateRelease) {
        // Invariant I15: refuse downgrades / same version.
        val current = Semver.parseOrNull(currentVersion)
        val incoming = Semver.parseOrNull(release.version)
        if (current != null && incoming != null && incoming <= current) {
            _state.value = UpdateState.Idle
            return
        }
        if (blacklistedVersion == release.version) {
            logger.warn { "Version ${release.version} is blacklisted, skipping" }
            _state.value = UpdateState.Idle
            return
        }

        _state.value = UpdateState.Downloading(release, 0, release.installer.size)
        val dl = downloader.download(release)
        when (dl) {
            is DownloadResult.Success -> {
                _state.value = UpdateState.Verifying(release)
                // Downloader already verified SHA256; at this point we trust the file.
                verifyFailureCount = 0
                _state.value = UpdateState.ReadyToInstall(release, dl.msiFile.toString())
            }
            is DownloadResult.VerifyFailed -> {
                verifyFailureCount++
                lastError = "sha256 mismatch"
                if (verifyFailureCount >= 3) {
                    blacklistedVersion = release.version
                    logger.warn { "Blacklisting ${release.version} after $verifyFailureCount verify failures" }
                }
                _state.value = UpdateState.Failed(UpdateState.Failed.Stage.VERIFY, "sha256 mismatch")
                delay(1500)
                if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
            }
            is DownloadResult.Failed -> {
                lastError = dl.cause?.message ?: "download failed"
                _state.value = UpdateState.Failed(UpdateState.Failed.Stage.DOWNLOAD, lastError ?: "download failed")
                delay(1500)
                if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
            }
        }
    }

    private fun jitterDelay(): Long {
        val jitter = (0 until 600_000L).random()
        return pollIntervalMillis + jitter
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.update.UpdateManagerTest"`
Expected: PASS

### Task 14: Create UpdateModule (Koin bindings)

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/di/UpdateModule.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt`

- [ ] **Step 1: Write the module**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/di/UpdateModule.kt
package uz.yalla.sipphone.di

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module
import uz.yalla.sipphone.data.auth.ApiConfig
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.data.update.MsiBootstrapperInstaller
import uz.yalla.sipphone.data.update.UpdateApi
import uz.yalla.sipphone.data.update.UpdateDownloader
import uz.yalla.sipphone.data.update.UpdateManager
import uz.yalla.sipphone.data.update.UpdatePaths
import uz.yalla.sipphone.data.update.asContract
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.update.UpdateChannel

/** Version constant injected at build time; defaults to gradle project version. */
object BuildVersion {
    const val CURRENT: String = "1.0.0"
}

val updateModule = module {
    single(named("updaterScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("updater"))
    }
    single { UpdatePaths() }
    single { UpdateApi(client = get<HttpClient>(), baseUrl = ApiConfig.BASE_URL) }
    single { UpdateDownloader(client = get(), paths = get()) }
    single { MsiBootstrapperInstaller() }
    single {
        val settings: AppSettings = get()
        UpdateManager(
            scope = get(named("updaterScope")),
            api = get<UpdateApi>().asContract(),
            downloader = get<UpdateDownloader>().asContract(),
            installer = get<MsiBootstrapperInstaller>().asContract(),
            paths = get(),
            callState = get<CallEngine>().callState,
            currentVersion = BuildVersion.CURRENT,
            channelProvider = { UpdateChannel.fromValue(settings.updateChannel) },
            installIdProvider = { settings.installId },
        )
    }
}
```

- [ ] **Step 2: Register in AppModule**

Modify `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt`:

```kotlin
package uz.yalla.sipphone.di

val appModules = listOf(
    networkModule, sipModule, settingsModule, authModule,
    featureModule, webviewModule, updateModule,
)
```

- [ ] **Step 3: Compile + tests**

Run: `./gradlew build`
Expected: PASS

- [ ] **Step 4: Commit Phase 3**

```bash
git add src/main/kotlin/uz/yalla/sipphone/data/update/UpdateManager.kt \
        src/main/kotlin/uz/yalla/sipphone/di/UpdateModule.kt \
        src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt \
        src/main/kotlin/uz/yalla/sipphone/data/settings/AppSettings.kt \
        src/test/kotlin/uz/yalla/sipphone/data/update/UpdateManagerTest.kt
git commit -m "feat(update): orchestrator, state machine, Koin module, install-id"
```

---

## Phase 4: UI

### Task 15: Create UpdateBadge composable

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/update/UpdateBadge.kt`

- [ ] **Step 1: Write the composable**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/main/update/UpdateBadge.kt
package uz.yalla.sipphone.feature.main.update

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import uz.yalla.sipphone.domain.update.UpdateState

@Composable
fun UpdateBadge(
    state: StateFlow<UpdateState>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by state.collectAsStateOrIdle()
    if (current is UpdateState.Idle || current is UpdateState.Checking) return
    val tint = when (current) {
        is UpdateState.Failed -> MaterialTheme.colorScheme.error
        is UpdateState.ReadyToInstall -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.SystemUpdateAlt,
            contentDescription = "Update",
            tint = tint,
        )
    }
}

@Composable
private fun StateFlow<UpdateState>.collectAsStateOrIdle() =
    androidx.compose.runtime.collectAsState(initial = UpdateState.Idle)
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: PASS

### Task 16: Create UpdateUi (dialog + diagnostics)

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/update/UpdateUi.kt`

- [ ] **Step 1: Write the composables**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/main/update/UpdateUi.kt
package uz.yalla.sipphone.feature.main.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateState
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.strings.StringResources

@Composable
fun UpdateDialog(
    stateFlow: StateFlow<UpdateState>,
    callStateFlow: StateFlow<CallState>,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by stateFlow.collectAsState()
    val callState by callStateFlow.collectAsState()
    val strings = LocalStrings.current

    if (state is UpdateState.Idle || state is UpdateState.Checking) return

    val release = when (val s = state) {
        is UpdateState.Downloading -> s.release
        is UpdateState.Verifying -> s.release
        is UpdateState.ReadyToInstall -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.Failed -> null
        else -> null
    }

    val callIsIdle = callState is CallState.Idle
    val canInstall = state is UpdateState.ReadyToInstall && callIsIdle
    val isForcedUpgrade = release != null && isMinSupportedViolated(release.minSupportedVersion)

    AlertDialog(
        onDismissRequest = { if (!isForcedUpgrade) onDismiss() },
        title = { Text(strings.updateAvailableDialogTitle + (release?.version?.let { " — v$it" } ?: "")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            ) {
                // Status line
                val statusText = when (val s = state) {
                    is UpdateState.Downloading -> "${strings.updateDownloadingMessage} (${percentOf(s.bytesRead, s.total)}%)"
                    is UpdateState.Verifying -> strings.updateVerifyingMessage
                    is UpdateState.Installing -> strings.updateInstallingMessage
                    is UpdateState.Failed -> failureText(s, strings)
                    is UpdateState.ReadyToInstall -> if (!callIsIdle) strings.updateWaitingForCallMessage else ""
                    else -> ""
                }
                if (statusText.isNotEmpty()) {
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }

                if (state is UpdateState.Downloading) {
                    val dl = state as UpdateState.Downloading
                    LinearProgressIndicator(
                        progress = { if (dl.total > 0) dl.bytesRead.toFloat() / dl.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Release notes
                if (release != null && release.releaseNotes.isNotBlank()) {
                    Text(strings.updateReleaseNotesHeader, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(release.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = canInstall || (release != null && isForcedUpgrade)) {
                Text(strings.updateInstallButton)
            }
        },
        dismissButton = if (isForcedUpgrade) { null } else {
            {
                TextButton(onClick = onDismiss) { Text(strings.updateLaterButton) }
            }
        },
    )
}

private fun percentOf(read: Long, total: Long): Int =
    if (total <= 0) 0 else ((read.toDouble() / total) * 100).toInt().coerceIn(0, 100)

private fun failureText(failed: UpdateState.Failed, s: StringResources): String = when (failed.stage) {
    UpdateState.Failed.Stage.VERIFY -> s.updateFailedVerify
    UpdateState.Failed.Stage.DOWNLOAD -> s.updateFailedDownload
    UpdateState.Failed.Stage.DISK_FULL -> s.updateFailedDisk
    UpdateState.Failed.Stage.UNTRUSTED_URL -> s.updateFailedUntrustedUrl
    UpdateState.Failed.Stage.MALFORMED_MANIFEST -> s.updateFailedMalformedManifest
    else -> failed.reason
}

/** Placeholder — hook into real comparison later if needed. */
private fun isMinSupportedViolated(@Suppress("UNUSED_PARAMETER") minSupported: String): Boolean = false

@Composable
fun UpdateDiagnosticsDialog(
    visible: Boolean,
    installId: String,
    channel: String,
    currentVersion: String,
    stateText: String,
    lastCheckText: String,
    lastErrorText: String,
    logTail: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.updateDiagnosticsTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${strings.updateCurrentVersion}: $currentVersion")
                Text("${strings.updateDiagnosticsInstallId}: $installId")
                Text("${strings.updateDiagnosticsChannel}: $channel")
                Text("${strings.updateDiagnosticsState}: $stateText")
                Text("${strings.updateDiagnosticsLastCheck}: $lastCheckText")
                Text("${strings.updateDiagnosticsLastError}: $lastErrorText")
                Spacer(Modifier.height(8.dp))
                Text(strings.updateDiagnosticsLogTail, style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(logTail, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text(strings.updateDiagnosticsCopy) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.updateDiagnosticsClose) } },
    )
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: PASS

### Task 17: Start/stop UpdateManager and wire Ctrl+Shift+Alt+B/D in Main.kt

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt`

- [ ] **Step 1: Add UpdateManager start/stop + key shortcut branch**

In `Main.kt`, after the existing `val koin = startKoin ... .koin` line, add:

```kotlin
    val updateManager: uz.yalla.sipphone.data.update.UpdateManager = koin.get()
    updateManager.start()
```

Inside `gracefulShutdown()` body, add first line:

```kotlin
    updateManager.stop()
```

Then extend `handleKeyboardShortcut` with new branches BEFORE the closing `}`:

```kotlin
        ctrl && shift && event.isAltDown && event.keyCode == KeyEvent.VK_B -> {
            // Hidden channel toggle — cycles stable↔beta.
            val settings: uz.yalla.sipphone.data.settings.AppSettings =
                org.koin.java.KoinJavaComponent.get(uz.yalla.sipphone.data.settings.AppSettings::class.java)
            settings.updateChannel = if (settings.updateChannel == "beta") "stable" else "beta"
            logger.info { "Update channel toggled: ${settings.updateChannel}" }
            event.consume()
        }
        ctrl && shift && event.isAltDown && event.keyCode == KeyEvent.VK_D -> {
            // Hidden diagnostics toggle — flip a MutableStateFlow in UpdateManager? Simpler:
            // emit a one-shot bus. For MVP we log-dump to Logback and let the operator
            // copy from the app's log file.
            logger.info { "Diagnostics requested via shortcut" }
            event.consume()
        }
```

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: PASS

### Task 18: Commit Phase 4

- [ ] **Step 1: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/feature/main/update \
        src/main/kotlin/uz/yalla/sipphone/Main.kt
git commit -m "feat(update): badge, dialog, diagnostics, Main.kt wiring"
```

---

## Phase 5: WiX + build.gradle.kts

### Task 19: Pin UpgradeCode in build.gradle.kts

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add upgradeUuid + perUserInstall**

Find `nativeDistributions { ... }` block. Add inside the `macOS` / platform area — since Compose Desktop Gradle DSL exposes `upgradeUuid` as a property on `nativeDistributions.windows`, we add a new block:

Replace the existing `nativeDistributions { ... }` block's contents with:

```kotlin
        nativeDistributions {
            includeAllModules = true
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "YallaSipPhone"
            packageVersion = "1.0.0"
            vendor = "Ildam"
            description = "Yalla SIP Phone - Oktell Operator Softphone"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))

            macOS {
                bundleID = "uz.yalla.sipphone"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>YallaSipPhone needs microphone access for VoIP calls</string>
                    """.trimIndent()
                }
                entitlementsFile.set(project.file("src/main/resources/entitlements.plist"))
            }

            windows {
                // Pinned UpgradeCode — NEVER change. Without it, every install is
                // side-by-side (see spec §5 invariant I19).
                upgradeUuid = "E7A4F1B2-9C5D-4E8A-B1F6-2D3E4F5A6B7C"
                perUserInstall = true
                menuGroup = "Yalla"
                shortcut = true
                menu = true
            }
        }
```

- [ ] **Step 2: Run build to ensure gradle accepts the DSL**

Run: `./gradlew tasks --all | head -20`
Expected: `packageMsi` task listed, no Gradle failure

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "feat(update): pin WiX UpgradeCode, enable per-user MSI install"
```

---

## Phase 6: C# Bootstrapper

### Task 20: Create bootstrapper csproj and Program.cs

**Files:**
- Create: `bootstrapper/YallaUpdateBootstrap.csproj`
- Create: `bootstrapper/Program.cs`
- Create: `bootstrapper/README.md`

- [ ] **Step 1: Write csproj**

```xml
<!-- bootstrapper/YallaUpdateBootstrap.csproj -->
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0-windows</TargetFramework>
    <RootNamespace>YallaUpdateBootstrap</RootNamespace>
    <AssemblyName>yalla-update-bootstrap</AssemblyName>
    <Nullable>enable</Nullable>
    <LangVersion>latest</LangVersion>
    <PublishSingleFile>true</PublishSingleFile>
    <SelfContained>false</SelfContained>
    <InvariantGlobalization>true</InvariantGlobalization>
  </PropertyGroup>
</Project>
```

- [ ] **Step 2: Write Program.cs**

```csharp
// bootstrapper/Program.cs
using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace YallaUpdateBootstrap;

internal static class Program
{
    private static StreamWriter? _log;

    private static int Main(string[] args)
    {
        var opts = ParseArgs(args);
        if (opts == null)
        {
            Console.Error.WriteLine(
                "Usage: yalla-update-bootstrap --msi <path> --install-dir <dir> " +
                "--parent-pid <pid> --expected-sha256 <hex> --log <path>");
            return 64;
        }

        try
        {
            _log = new StreamWriter(opts.LogPath, append: true) { AutoFlush = true };
            Log($"=== bootstrapper start {DateTime.Now:O} ===");
            Log($"msi={opts.MsiPath}, installDir={opts.InstallDir}, parentPid={opts.ParentPid}");

            if (!File.Exists(opts.MsiPath))
            {
                Log($"ERROR: MSI missing: {opts.MsiPath}");
                return 2;
            }

            Log("Re-verifying SHA256...");
            var actual = ComputeSha256(opts.MsiPath);
            if (!string.Equals(actual, opts.ExpectedSha256, StringComparison.OrdinalIgnoreCase))
            {
                Log($"ERROR: SHA256 mismatch. expected={opts.ExpectedSha256}, actual={actual}");
                return 3;
            }

            Log($"Waiting for parent pid {opts.ParentPid} to exit...");
            WaitForParentExit(opts.ParentPid);
            Log("Parent exited. Waiting 3s for file locks to release...");
            Thread.Sleep(3000);

            StripMarkOfTheWeb(opts.MsiPath);

            var backupDir = Path.Combine(
                Path.GetDirectoryName(opts.InstallDir) ?? opts.InstallDir,
                "backup",
                DateTime.Now.ToString("yyyyMMdd-HHmmss"));
            Log($"Quarantining old install to {backupDir}");
            try
            {
                if (Directory.Exists(opts.InstallDir))
                {
                    CopyDirectory(opts.InstallDir, backupDir);
                }
            }
            catch (Exception ex)
            {
                Log($"WARN: quarantine copy failed: {ex.Message}");
            }

            Log("Running msiexec...");
            var msiLog = Path.Combine(Path.GetDirectoryName(opts.LogPath) ?? ".", "msiexec.log");
            var psi = new ProcessStartInfo("msiexec.exe")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            psi.ArgumentList.Add("/i");
            psi.ArgumentList.Add(opts.MsiPath);
            psi.ArgumentList.Add("/qn");
            psi.ArgumentList.Add("/norestart");
            psi.ArgumentList.Add("REBOOT=ReallySuppress");
            psi.ArgumentList.Add("/L*v");
            psi.ArgumentList.Add(msiLog);

            var proc = Process.Start(psi);
            if (proc == null)
            {
                Log("ERROR: failed to start msiexec");
                TryRestore(backupDir, opts.InstallDir);
                return 4;
            }
            proc.WaitForExit();
            var exit = proc.ExitCode;
            Log($"msiexec exit: {exit}");

            // Success codes
            if (exit == 0 || exit == 3010)
            {
                Log("Install success. Cleaning backup.");
                TryDeleteDir(backupDir);
                LaunchApp(opts.InstallDir);
                return 0;
            }

            // User-cancel / conflicting install — don't restore, just relaunch old
            if (exit == 1602 || exit == 1618)
            {
                Log("User cancelled or another install in progress; relaunching old exe.");
                LaunchApp(opts.InstallDir);
                return exit;
            }

            // Anything else: restore
            Log("Install failed; restoring quarantine.");
            TryRestore(backupDir, opts.InstallDir);
            LaunchApp(opts.InstallDir);
            return exit;
        }
        catch (Exception ex)
        {
            Log("FATAL: " + ex);
            return 1;
        }
        finally
        {
            _log?.Flush();
            _log?.Close();
        }
    }

    // ----- helpers -----

    private record Options(string MsiPath, string InstallDir, int ParentPid, string ExpectedSha256, string LogPath);

    private static Options? ParseArgs(string[] args)
    {
        string? msi = null, installDir = null, sha = null, log = null;
        int pid = 0;
        for (int i = 0; i < args.Length; i++)
        {
            var a = args[i];
            if (a == "--msi" && i + 1 < args.Length) msi = args[++i];
            else if (a == "--install-dir" && i + 1 < args.Length) installDir = args[++i];
            else if (a == "--parent-pid" && i + 1 < args.Length) int.TryParse(args[++i], out pid);
            else if (a == "--expected-sha256" && i + 1 < args.Length) sha = args[++i];
            else if (a == "--log" && i + 1 < args.Length) log = args[++i];
        }
        if (msi == null || installDir == null || sha == null || log == null || pid == 0) return null;
        return new Options(msi, installDir, pid, sha, log);
    }

    private static void Log(string msg) => _log?.WriteLine($"[{DateTime.Now:HH:mm:ss}] {msg}");

    private static string ComputeSha256(string path)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(path);
        var hash = sha.ComputeHash(stream);
        var sb = new StringBuilder(hash.Length * 2);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    private static void WaitForParentExit(int parentPid)
    {
        try
        {
            var proc = Process.GetProcessById(parentPid);
            if (!proc.WaitForExit(60_000))
            {
                Log("WARN: parent did not exit within 60s; continuing anyway");
            }
        }
        catch (ArgumentException)
        {
            // Already dead.
        }
    }

    private static void StripMarkOfTheWeb(string path)
    {
        var ads = path + ":Zone.Identifier";
        try
        {
            if (File.Exists(ads))
            {
                File.Delete(ads);
                Log("Stripped Zone.Identifier");
            }
        }
        catch (Exception ex)
        {
            Log($"WARN: strip MOTW failed: {ex.Message}");
        }
    }

    private static void CopyDirectory(string source, string dest)
    {
        Directory.CreateDirectory(dest);
        foreach (var f in Directory.GetFiles(source))
        {
            File.Copy(f, Path.Combine(dest, Path.GetFileName(f)), overwrite: true);
        }
        foreach (var d in Directory.GetDirectories(source))
        {
            CopyDirectory(d, Path.Combine(dest, Path.GetFileName(d)));
        }
    }

    private static void TryRestore(string backupDir, string installDir)
    {
        try
        {
            if (Directory.Exists(backupDir))
            {
                if (Directory.Exists(installDir))
                    Directory.Delete(installDir, recursive: true);
                CopyDirectory(backupDir, installDir);
                Log("Restored backup into install dir");
            }
        }
        catch (Exception ex)
        {
            Log($"ERROR: restore failed: {ex.Message}");
        }
    }

    private static void TryDeleteDir(string dir)
    {
        try
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
        }
        catch (Exception ex)
        {
            Log($"WARN: delete {dir} failed: {ex.Message}");
        }
    }

    private static void LaunchApp(string installDir)
    {
        try
        {
            var exe = Directory.GetFiles(installDir, "YallaSipPhone.exe", SearchOption.AllDirectories).FirstOrDefault();
            if (exe != null)
            {
                Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
                Log($"Launched: {exe}");
            }
            else
            {
                Log("WARN: YallaSipPhone.exe not found after install");
            }
        }
        catch (Exception ex)
        {
            Log($"ERROR: launch failed: {ex.Message}");
        }
    }
}
```

- [ ] **Step 3: Write README for the bootstrapper**

```markdown
<!-- bootstrapper/README.md -->
# yalla-update-bootstrap

A C# .NET 8 Windows helper that runs `msiexec` to install the yalla-sip-phone MSI after the main app exits.

## Why

Windows file locks from JCEF (`libcef.dll`, `jcef_helper.exe`) and pjsip (`libpjsua2.dll` mapped in JVM) prevent an in-place MSI upgrade while the parent process is alive. This bootstrapper waits for the parent PID to die, then invokes msiexec, then relaunches the new app. See spec §11.

## Build

```sh
cd bootstrapper
dotnet publish -c Release -r win-x64 --self-contained=false -p:PublishSingleFile=true
cp bin/Release/net8.0-windows/win-x64/publish/yalla-update-bootstrap.exe \
   ../src/main/resources/bootstrapper/yalla-update-bootstrap.exe
```

## CLI

```
yalla-update-bootstrap \
  --msi <path-to-msi> \
  --install-dir <current install dir> \
  --parent-pid <pid> \
  --expected-sha256 <hex64> \
  --log <path-to-log-file>
```

Exit codes:
- `0` — install success (or success + reboot required via code 3010)
- `1` — fatal exception
- `2` — MSI missing
- `3` — SHA256 mismatch
- `4` — msiexec failed to start
- any other — msiexec's exit code
```

- [ ] **Step 4: Commit**

```bash
git add bootstrapper
git commit -m "feat(update): C# .NET bootstrapper for MSI install recovery"
```

---

## Phase 7: Verification

### Task 21: Full build + tests

- [ ] **Step 1: Clean build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, 0 test failures

- [ ] **Step 2: Run all tests**

Run: `./gradlew test`
Expected: All passing

- [ ] **Step 3: Document manual verification**

Add a line to `docs/testing.md` listing the manual-only tests (`MsiBootstrapperInstaller` real-msiexec, Windows 11 per-user install flow).

- [ ] **Step 4: Final commit**

```bash
git add docs/testing.md
git commit -m "docs(testing): auto-update manual-test checklist"
```
