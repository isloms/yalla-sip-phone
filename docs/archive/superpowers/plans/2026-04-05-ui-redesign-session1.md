# UI Redesign Session 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the SIP phone prototype into a call center workstation with login screen, fixed toolbar, webview placeholder, Yalla brand theme (dark/light), and desktop features.

**Architecture:** Two-window Compose Desktop app. Login window (small, centered) transitions to Main window (maximized, always-on-top) with fixed 56px toolbar + webview placeholder panel. Domain model extended with callId, AgentStatus, AuthResult. Existing SIP engine untouched except pjsip config fixes and callId addition.

**Tech Stack:** Kotlin 2.1.20, Compose Desktop 1.8.2, Decompose 3.4.0, Koin 4.1.1, MaterialKolor 2.0.0, pjsip JNI

**Spec:** `docs/superpowers/specs/2026-04-05-ui-redesign-design.md` (Sections 1-15 + Section 16 Amendments)

**Scope:** Session 1 = Tier 1 + Tier 2 (no JCEF, no JS Bridge). Webview area is a colored placeholder panel showing dispatcher URL text.

---

## File Map

### New Files (Session 1)

| File | Responsibility |
|------|---------------|
| `domain/AgentStatus.kt` | Agent status enum (Ready, Away, Break, WrapUp, Offline) |
| `domain/AgentInfo.kt` | Agent data class (id, name) |
| `domain/AuthResult.kt` | Domain model — SipCredentials + dispatcherUrl + AgentInfo |
| `domain/AuthRepository.kt` | Login interface |
| `domain/PhoneNumberValidator.kt` | Phone number validation (whitelist regex) |
| `util/PhoneNumberMasker.kt` | PII masking for logs |
| `data/auth/MockAuthRepository.kt` | Mock backend — password "test123" |
| `data/auth/LoginResponse.kt` | Backend DTO (mapped to AuthResult) |
| `di/AuthModule.kt` | Auth DI bindings |
| `ui/theme/YallaColors.kt` | Brand colors (light + dark + call states) — WCAG compliant |
| `feature/login/LoginComponent.kt` | Login business logic (auth + SIP register) |
| `feature/login/LoginScreen.kt` | Login UI (password field, button, manual SIP expandable) |
| `feature/main/MainComponent.kt` | Main screen orchestrator (toolbar + placeholder) |
| `feature/main/MainScreen.kt` | Main layout (toolbar + placeholder panel) |
| `feature/main/toolbar/ToolbarComponent.kt` | Toolbar state management |
| `feature/main/toolbar/ToolbarContent.kt` | Toolbar UI — 5 zones |
| `feature/main/toolbar/AgentStatusDropdown.kt` | Zone A — status dropdown |
| `feature/main/toolbar/CallControls.kt` | Zone C — call action buttons per state |
| `feature/main/toolbar/SettingsPopover.kt` | Zone D — settings dropdown |
| `feature/main/toolbar/CallQualityIndicator.kt` | Zone E — MOS dot |
| `feature/main/placeholder/WebviewPlaceholder.kt` | Temporary colored panel (replaced by JCEF in Session 2) |

### Modified Files

| File | Changes |
|------|---------|
| `domain/CallState.kt` | Add `callId: String` to Ringing and Active |
| `domain/CallEngine.kt` | Add `setMute(callId, bool)`, `setHold(callId, bool)` |
| `data/pjsip/PjsipCallManager.kt` | Generate callId, implement setMute/setHold, fix mute-after-hold |
| `data/pjsip/PjsipEngine.kt` | Delegate new setMute/setHold |
| `data/pjsip/PjsipEndpointManager.kt` | `regConfig.retryIntervalSec = 0`, log level 3 in release |
| `ui/theme/Theme.kt` | Yalla brand seed, dark mode support, new typography |
| `ui/theme/AppTokens.kt` | Toolbar tokens, new window sizes, quality dot size |
| `ui/strings/Strings.kt` | New strings for login, toolbar, settings, agent status |
| `navigation/Screen.kt` | Add Login, Main screens |
| `navigation/RootComponent.kt` | Login → Main flow |
| `navigation/RootContent.kt` | Remove (replaced by two-window approach in Main.kt) |
| `navigation/ComponentFactory.kt` | Add createLogin, createMain |
| `navigation/ComponentFactoryImpl.kt` | Implement new factories |
| `di/AppModule.kt` | Add authModule |
| `di/FeatureModule.kt` | Updated factory dependencies |
| `Main.kt` | Two-window approach, no SIP init at startup, always-on-top, no minimize |

### Test Files (New)

| File | Tests |
|------|-------|
| `test/.../domain/AgentStatusTest.kt` | Enum values, transitions |
| `test/.../domain/PhoneNumberValidatorTest.kt` | Valid/invalid numbers, premium blocking |
| `test/.../util/PhoneNumberMaskerTest.kt` | Masking format |
| `test/.../data/auth/MockAuthRepositoryTest.kt` | Success/failure paths |
| `test/.../feature/login/LoginComponentTest.kt` | Auth flow, SIP register, error states |
| `test/.../feature/main/toolbar/ToolbarComponentTest.kt` | Call state → toolbar state mapping |

---

## Task 1: Domain Model — AgentStatus + AgentInfo + AuthResult

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/AgentStatus.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/AgentInfo.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/AuthResult.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/AuthRepository.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/domain/AgentStatusTest.kt`

- [ ] **Step 1: Write AgentStatus test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/domain/AgentStatusTest.kt
package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentStatusTest {
    @Test
    fun `all statuses have display names`() {
        AgentStatus.entries.forEach { status ->
            assertNotNull(status.displayName)
            assert(status.displayName.isNotBlank())
        }
    }

    @Test
    fun `wrap-up is distinct from other statuses`() {
        val wrapUp = AgentStatus.WRAP_UP
        assertEquals("Wrap-Up", wrapUp.displayName)
        assert(wrapUp != AgentStatus.READY)
    }

    @Test
    fun `all statuses have color hex`() {
        AgentStatus.entries.forEach { status ->
            assert(status.colorHex.startsWith("#"))
            assertEquals(7, status.colorHex.length)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.AgentStatusTest" --info 2>&1 | tail -20`
Expected: FAIL — `AgentStatus` not found

- [ ] **Step 3: Implement domain models**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/AgentStatus.kt
package uz.yalla.sipphone.domain

enum class AgentStatus(val displayName: String, val colorHex: String) {
    READY("Ready", "#2E7D32"),
    AWAY("Away", "#F59E0B"),
    BREAK("Break", "#F97316"),
    WRAP_UP("Wrap-Up", "#8B5CF6"),
    OFFLINE("Offline", "#98A2B3"),
}
```

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/AgentInfo.kt
package uz.yalla.sipphone.domain

data class AgentInfo(
    val id: String,
    val name: String,
)
```

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/AuthResult.kt
package uz.yalla.sipphone.domain

data class AuthResult(
    val sipCredentials: SipCredentials,
    val dispatcherUrl: String,
    val agent: AgentInfo,
)
```

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/AuthRepository.kt
package uz.yalla.sipphone.domain

interface AuthRepository {
    suspend fun login(password: String): Result<AuthResult>
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.AgentStatusTest" --info 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add src/main/kotlin/uz/yalla/sipphone/domain/AgentStatus.kt src/main/kotlin/uz/yalla/sipphone/domain/AgentInfo.kt src/main/kotlin/uz/yalla/sipphone/domain/AuthResult.kt src/main/kotlin/uz/yalla/sipphone/domain/AuthRepository.kt src/test/kotlin/uz/yalla/sipphone/domain/AgentStatusTest.kt && git commit -m "feat(domain): add AgentStatus, AgentInfo, AuthResult, AuthRepository"
```

---

## Task 2: Domain Model — PhoneNumberValidator + PhoneNumberMasker

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/PhoneNumberValidator.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/util/PhoneNumberMasker.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/domain/PhoneNumberValidatorTest.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/util/PhoneNumberMaskerTest.kt`

- [ ] **Step 1: Write PhoneNumberValidator test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/domain/PhoneNumberValidatorTest.kt
package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PhoneNumberValidatorTest {
    @Test
    fun `valid local number`() {
        assertTrue(PhoneNumberValidator.validate("101").isSuccess)
    }

    @Test
    fun `valid international number`() {
        assertTrue(PhoneNumberValidator.validate("+998901234567").isSuccess)
    }

    @Test
    fun `valid number with star and hash`() {
        assertTrue(PhoneNumberValidator.validate("*72#").isSuccess)
    }

    @Test
    fun `rejects empty string`() {
        assertTrue(PhoneNumberValidator.validate("").isFailure)
    }

    @Test
    fun `rejects letters`() {
        assertTrue(PhoneNumberValidator.validate("abc123").isFailure)
    }

    @Test
    fun `rejects control characters`() {
        assertTrue(PhoneNumberValidator.validate("123\r\n456").isFailure)
    }

    @Test
    fun `rejects too long number`() {
        assertTrue(PhoneNumberValidator.validate("+1234567890123456789012").isFailure)
    }

    @Test
    fun `sanitized number is trimmed`() {
        val result = PhoneNumberValidator.validate("  +998901234567  ")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() == "+998901234567")
    }
}
```

- [ ] **Step 2: Write PhoneNumberMasker test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/util/PhoneNumberMaskerTest.kt
package uz.yalla.sipphone.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneNumberMaskerTest {
    @Test
    fun `masks international number`() {
        assertEquals("+998 90 *** ** 67", PhoneNumberMasker.mask("+998901234567"))
    }

    @Test
    fun `masks short number shows last 2`() {
        assertEquals("***01", PhoneNumberMasker.mask("101"))
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", PhoneNumberMasker.mask(""))
    }

    @Test
    fun `single digit returns masked`() {
        assertEquals("*", PhoneNumberMasker.mask("5"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.PhoneNumberValidatorTest" --tests "uz.yalla.sipphone.util.PhoneNumberMaskerTest" --info 2>&1 | tail -20`
Expected: FAIL

- [ ] **Step 4: Implement PhoneNumberValidator**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/PhoneNumberValidator.kt
package uz.yalla.sipphone.domain

object PhoneNumberValidator {
    private val VALID_DIAL_PATTERN = Regex("^[+]?[0-9*#]{1,20}$")

    fun validate(number: String): Result<String> {
        val sanitized = number.trim()

        if (sanitized.isEmpty()) {
            return Result.failure(IllegalArgumentException("Phone number is empty"))
        }

        if (sanitized.any { it.code < 32 }) {
            return Result.failure(IllegalArgumentException("Phone number contains control characters"))
        }

        if (!VALID_DIAL_PATTERN.matches(sanitized)) {
            return Result.failure(IllegalArgumentException("Invalid phone number format"))
        }

        return Result.success(sanitized)
    }
}
```

- [ ] **Step 5: Implement PhoneNumberMasker**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/util/PhoneNumberMasker.kt
package uz.yalla.sipphone.util

object PhoneNumberMasker {
    fun mask(number: String): String {
        if (number.isEmpty()) return ""
        if (number.length <= 2) return "*".repeat(number.length)

        val visible = number.takeLast(2)
        val prefix = number.dropLast(2)

        return if (number.length > 10) {
            // International format: +998 90 *** ** 67
            val countryAndArea = number.take(6)
            val masked = "*".repeat(number.length - 8)
            "$countryAndArea $masked $visible"
                .replace(Regex("(\\+\\d{3})(\\d{2})"), "$1 $2")
        } else {
            "*".repeat(prefix.length) + visible
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.PhoneNumberValidatorTest" --tests "uz.yalla.sipphone.util.PhoneNumberMaskerTest" --info 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add src/main/kotlin/uz/yalla/sipphone/domain/PhoneNumberValidator.kt src/main/kotlin/uz/yalla/sipphone/util/PhoneNumberMasker.kt src/test/kotlin/uz/yalla/sipphone/domain/PhoneNumberValidatorTest.kt src/test/kotlin/uz/yalla/sipphone/util/PhoneNumberMaskerTest.kt && git commit -m "feat(domain): add PhoneNumberValidator and PhoneNumberMasker"
```

---

## Task 3: Domain Model — Add callId to CallState + setMute/setHold to CallEngine

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/domain/CallState.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/domain/CallEngine.kt`
- Modify: `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngine.kt`

- [ ] **Step 1: Update CallState with callId**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/CallState.kt
package uz.yalla.sipphone.domain

sealed interface CallState {
    data object Idle : CallState
    data class Ringing(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val isOutbound: Boolean,
    ) : CallState
    data class Active(
        val callId: String,
        val remoteNumber: String,
        val remoteName: String?,
        val isOutbound: Boolean,
        val isMuted: Boolean,
        val isOnHold: Boolean,
    ) : CallState
    data object Ending : CallState
}
```

- [ ] **Step 2: Update CallEngine with explicit setMute/setHold**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/domain/CallEngine.kt
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface CallEngine {
    val callState: StateFlow<CallState>
    suspend fun makeCall(number: String): Result<Unit>
    suspend fun answerCall()
    suspend fun hangupCall()
    suspend fun toggleMute()
    suspend fun toggleHold()
    suspend fun setMute(callId: String, muted: Boolean)
    suspend fun setHold(callId: String, onHold: Boolean)
}
```

- [ ] **Step 3: Update FakeCallEngine to compile**

Read `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngine.kt` first, then add `callId` to emitted states and implement new methods. Add `callId` parameter with default `"test-call-id"`. Add `setMute`/`setHold` that delegate to toggleMute/toggleHold for backward compat.

- [ ] **Step 4: Fix all compilation errors in existing tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --info 2>&1 | tail -40`

Update any test that constructs `CallState.Ringing(...)` or `CallState.Active(...)` to include the `callId` parameter.

- [ ] **Step 5: Verify all tests pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --info 2>&1 | tail -20`
Expected: ALL PASS

- [ ] **Step 6: Update PjsipCallManager to generate callId and implement setMute/setHold**

In `data/pjsip/PjsipCallManager.kt`:
- Import `java.util.UUID`
- Generate `callId = UUID.randomUUID().toString()` when a call starts
- Pass `callId` to `CallState.Ringing(callId = callId, ...)` and `CallState.Active(callId = callId, ...)`
- Add `setMute(callId, muted)` — validates callId matches active call, then calls existing mute logic
- Add `setHold(callId, onHold)` — validates callId matches active call, then calls existing hold logic
- Fix mute-after-hold: in `connectCallAudio()`, check `isMuted` flag and skip `captureMedia.startTransmit(audioMedia)` if muted

In `data/pjsip/PjsipEngine.kt`:
- Add `override suspend fun setMute(callId: String, muted: Boolean)` delegating to `callManager.setMute(callId, muted)`
- Add `override suspend fun setHold(callId: String, onHold: Boolean)` delegating to `callManager.setHold(callId, onHold)`

- [ ] **Step 7: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(domain): add callId to CallState, setMute/setHold to CallEngine"
```

---

## Task 4: pjsip Config Fixes

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipEndpointManager.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccountManager.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCallManager.kt`

- [ ] **Step 1: Fix pjsip retry conflict**

In `PjsipEndpointManager.kt`, find where `epConfig.logConfig` is set. Change log level:
```kotlin
epConfig.logConfig.level = 3  // was 5 — level 5 exposes SIP auth headers
epConfig.logConfig.consoleLevel = 3
```

In `PjsipAccountManager.kt`, find where `AccountConfig.regConfig` is set. Add:
```kotlin
accConfig.regConfig.retryIntervalSec = 0  // disable pjsip built-in retry, we handle reconnect ourselves
```

- [ ] **Step 2: Fix holdInProgress guard**

In `PjsipCallManager.kt`, find where `holdInProgress = false` is set in `finally` block. Move it to `onCallMediaState` callback instead:
```kotlin
// In the hold/resume method, remove holdInProgress = false from finally block
// In connectCallAudio() (called from onCallMediaState), add:
holdInProgress = false
```

- [ ] **Step 3: Fix mute state after hold/resume**

In `PjsipCallManager.kt`, in `connectCallAudio()`, after connecting playback media, add mute check:
```kotlin
// After: playbackMedia.startTransmit(audioMedia)
// Add mute check:
if (!isMuted) {
    captureMedia.startTransmit(audioMedia)
}
// Remove the unconditional captureMedia.startTransmit(audioMedia)
```

- [ ] **Step 4: Verify build compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run existing tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -10`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "fix(pjsip): disable built-in retry, reduce log level, fix mute-after-hold and hold guard"
```

---

## Task 5: Mock Auth Backend

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/auth/LoginResponse.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/di/AuthModule.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepositoryTest.kt`

- [ ] **Step 1: Write MockAuthRepository test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepositoryTest.kt
package uz.yalla.sipphone.data.auth

import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.AuthRepository
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class MockAuthRepositoryTest {
    private val repo: AuthRepository = MockAuthRepository()

    @Test
    fun `correct password returns success`() = runTest {
        val result = repo.login("test123")
        assertTrue(result.isSuccess)
        val auth = result.getOrThrow()
        assertEquals("101", auth.sipCredentials.username)
        assertEquals("192.168.30.103", auth.sipCredentials.server)
        assertEquals("Alisher", auth.agent.name)
    }

    @Test
    fun `wrong password returns failure`() = runTest {
        val result = repo.login("wrong")
        assertTrue(result.isFailure)
    }

    @Test
    fun `empty password returns failure`() = runTest {
        val result = repo.login("")
        assertTrue(result.isFailure)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.data.auth.MockAuthRepositoryTest" --info 2>&1 | tail -20`
Expected: FAIL

- [ ] **Step 3: Implement LoginResponse DTO**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/auth/LoginResponse.kt
package uz.yalla.sipphone.data.auth

import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.SipCredentials

data class LoginResponse(
    val sipServer: String,
    val sipPort: Int,
    val sipUsername: String,
    val sipPassword: String,
    val sipTransport: String,
    val dispatcherUrl: String,
    val agentId: String,
    val agentName: String,
) {
    fun toAuthResult(): AuthResult = AuthResult(
        sipCredentials = SipCredentials(
            server = sipServer,
            port = sipPort,
            username = sipUsername,
            password = sipPassword,
        ),
        dispatcherUrl = dispatcherUrl,
        agent = AgentInfo(id = agentId, name = agentName),
    )
}
```

- [ ] **Step 4: Implement MockAuthRepository**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt
package uz.yalla.sipphone.data.auth

import kotlinx.coroutines.delay
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult

class MockAuthRepository : AuthRepository {
    override suspend fun login(password: String): Result<AuthResult> {
        delay(1000) // simulate network
        return if (password == "test123") {
            Result.success(
                LoginResponse(
                    sipServer = "192.168.30.103",
                    sipPort = 5060,
                    sipUsername = "101",
                    sipPassword = "secret",
                    sipTransport = "UDP",
                    dispatcherUrl = "https://dispatcher.yalla.uz/panel",
                    agentId = "agent-042",
                    agentName = "Alisher",
                ).toAuthResult()
            )
        } else {
            Result.failure(IllegalArgumentException("Invalid password"))
        }
    }
}
```

- [ ] **Step 5: Create AuthModule and update AppModule**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/di/AuthModule.kt
package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.auth.MockAuthRepository
import uz.yalla.sipphone.domain.AuthRepository

val authModule = module {
    single<AuthRepository> { MockAuthRepository() }
}
```

Update `AppModule.kt` to include `authModule`:
```kotlin
val appModules = listOf(sipModule, settingsModule, authModule, featureModule)
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.data.auth.MockAuthRepositoryTest" --info 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(auth): add MockAuthRepository with LoginResponse DTO and AuthModule"
```

---

## Task 6: Yalla Brand Theme — Colors + Dark Mode

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/theme/YallaColors.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/ui/theme/AppTokens.kt`

- [ ] **Step 1: Create YallaColors with WCAG-compliant values**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/ui/theme/YallaColors.kt
package uz.yalla.sipphone.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class YallaColors(
    // Brand
    val brandPrimary: Color,
    val brandPrimaryDisabled: Color,
    val brandPrimaryText: Color, // for text on dark bg
    // Backgrounds
    val backgroundBase: Color,
    val backgroundSecondary: Color,
    val backgroundTertiary: Color,
    // Text
    val textBase: Color,
    val textSubtle: Color,
    // Borders
    val borderDisabled: Color,
    val borderFilled: Color,
    // Error
    val errorText: Color,
    val errorIndicator: Color,
    // Call states
    val callReady: Color,
    val callIncoming: Color,
    val callMuted: Color,
    val callOffline: Color,
    val callWrapUp: Color,
) {
    companion object {
        val Light = YallaColors(
            brandPrimary = Color(0xFF562DF8),
            brandPrimaryDisabled = Color(0xFFC8CBFA),
            brandPrimaryText = Color(0xFF562DF8),
            backgroundBase = Color(0xFFFFFFFF),
            backgroundSecondary = Color(0xFFF7F7F7),
            backgroundTertiary = Color(0xFFE9EAEA),
            textBase = Color(0xFF101828),
            textSubtle = Color(0xFF6B7280),       // WCAG 5.0:1 on white
            borderDisabled = Color(0xFFE4E7EC),
            borderFilled = Color(0xFF101828),
            errorText = Color(0xFFD32F2F),         // WCAG 5.5:1 on white
            errorIndicator = Color(0xFFF42500),
            callReady = Color(0xFF2E7D32),
            callIncoming = Color(0xFFF59E0B),
            callMuted = Color(0xFFF42500),
            callOffline = Color(0xFF98A2B3),
            callWrapUp = Color(0xFF8B5CF6),
        )

        val Dark = YallaColors(
            brandPrimary = Color(0xFF562DF8),
            brandPrimaryDisabled = Color(0xFF2C2D34),
            brandPrimaryText = Color(0xFF8B6FFF),  // WCAG 5.2:1 on dark
            backgroundBase = Color(0xFF1A1A20),
            backgroundSecondary = Color(0xFF21222B),
            backgroundTertiary = Color(0xFF383843),
            textBase = Color(0xFFFFFFFF),
            textSubtle = Color(0xFF9CA3AF),        // WCAG 5.5:1 on dark
            borderDisabled = Color(0xFF383843),
            borderFilled = Color(0xFFFFFFFF),
            errorText = Color(0xFFFF6B6B),          // WCAG 5.8:1 on dark
            errorIndicator = Color(0xFFF42500),
            callReady = Color(0xFF4CAF50),          // WCAG 4.8:1 on dark (was #2E7D32 2.6:1)
            callIncoming = Color(0xFFF59E0B),
            callMuted = Color(0xFFF42500),
            callOffline = Color(0xFF98A2B3),
            callWrapUp = Color(0xFF8B5CF6),
        )
    }
}

val LocalYallaColors = staticCompositionLocalOf { YallaColors.Light }
```

- [ ] **Step 2: Update Theme.kt for dark mode and Yalla brand**

Rewrite `Theme.kt` to support `isDark` parameter, new seed color `#562DF8`, SF Pro Display / Roboto font, and provide `YallaColors` via CompositionLocal. Keep existing `ExtendedColors` for backward compatibility but update success color to use `YallaColors.callReady`.

The theme composable becomes:
```kotlin
@Composable
fun YallaSipPhoneTheme(
    isDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val seedColor = Color(0xFF562DF8)  // Yalla purple
    val colorScheme = rememberDynamicColorScheme(seedColor, isDark = isDark, isAmoled = false)
    val yallaColors = if (isDark) YallaColors.Dark else YallaColors.Light
    // ... provide all composition locals
}
```

- [ ] **Step 3: Update AppTokens with toolbar dimensions**

Add to `AppTokens`:
```kotlin
// Toolbar
val toolbarHeight: Dp = 56.dp,
val toolbarZoneAWidth: Dp = 120.dp,
val toolbarZoneCWidth: Dp = 200.dp,
val toolbarZoneDWidth: Dp = 40.dp,
val toolbarZoneEWidth: Dp = 32.dp,
val toolbarDividerHeight: Dp = 1.dp,

// Call quality
val qualityDotSize: Dp = 12.dp,

// Window sizes (updated)
val loginWindowSize: DpSize = DpSize(420.dp, 520.dp),
val mainWindowMinSize: DpSize = DpSize(1280.dp, 720.dp),
```

Remove or keep old `registrationWindowSize`/`dialerWindowSize` for backward compat until old screens are removed.

- [ ] **Step 4: Verify build compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(theme): add Yalla brand colors with dark mode, WCAG-compliant contrast fixes"
```

---

## Task 7: Strings Update

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/ui/strings/Strings.kt`

- [ ] **Step 1: Add new strings**

Add to `Strings.kt`:
```kotlin
// Login
const val LOGIN_TITLE = "Yalla SIP Phone"
const val LOGIN_PASSWORD_LABEL = "Password"
const val LOGIN_BUTTON = "Login"
const val LOGIN_MANUAL_CONNECTION = "Manual connection"
const val LOGIN_ERROR_INVALID = "Invalid password"
const val LOGIN_ERROR_NETWORK = "Network error. Please try again."
const val LOGIN_ERROR_CONNECTION_LOST = "Connection lost. Please re-login."

// Agent Status
const val AGENT_READY = "Ready"
const val AGENT_AWAY = "Away"
const val AGENT_BREAK = "Break"
const val AGENT_WRAP_UP = "Wrap-Up"
const val AGENT_OFFLINE = "Offline"
const val AGENT_RECONNECTING = "Reconnecting..."

// Toolbar
const val STATUS_RINGING = "Ringing..."
const val CALL_QUALITY_EXCELLENT = "Excellent"
const val CALL_QUALITY_GOOD = "Good"
const val CALL_QUALITY_FAIR = "Fair"
const val CALL_QUALITY_POOR = "Poor"

// Settings
const val SETTINGS_TITLE = "Settings"
const val SETTINGS_THEME = "Theme"
const val SETTINGS_THEME_LIGHT = "Light"
const val SETTINGS_THEME_DARK = "Dark"
const val SETTINGS_LOGOUT = "Logout"
const val SETTINGS_LOGOUT_CONFIRM = "Logout and close Yalla SIP Phone?"

// Desktop
const val WINDOW_TITLE_PATTERN = "Yalla SIP Phone — %s"
const val NOTIFICATION_INCOMING = "Incoming Call"

// Webview placeholder
const val PLACEHOLDER_DISPATCHER = "Dispatcher panel will load here"
const val PLACEHOLDER_URL = "URL: %s"
```

- [ ] **Step 2: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(strings): add login, toolbar, settings, and desktop strings"
```

---

## Task 8: Login Screen — LoginComponent + LoginScreen

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/login/LoginComponent.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/login/LoginScreen.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/feature/login/LoginComponentTest.kt`

- [ ] **Step 1: Write LoginComponent test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/feature/login/LoginComponentTest.kt
package uz.yalla.sipphone.feature.login

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.data.auth.MockAuthRepository
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginComponentTest {
    private val testDispatcher = StandardTestDispatcher()
    private val lifecycle = LifecycleRegistry()
    private val fakeRegistration = FakeRegistrationEngine()
    private val authRepo: AuthRepository = MockAuthRepository()
    private var navigatedToMain = false

    private lateinit var component: LoginComponent

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        lifecycle.onCreate()
        lifecycle.onStart()
        lifecycle.onResume()
        component = LoginComponent(
            componentContext = DefaultComponentContext(lifecycle),
            authRepository = authRepo,
            registrationEngine = fakeRegistration,
            onLoginSuccess = { navigatedToMain = true },
            ioDispatcher = testDispatcher,
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is idle`() {
        assertEquals(LoginState.Idle, component.loginState.value)
    }

    @Test
    fun `login with correct password triggers SIP registration`() = runTest(testDispatcher) {
        component.login("test123")
        advanceUntilIdle()
        // After auth success, component calls registrationEngine.register
        assertTrue(fakeRegistration.registrationState.value is RegistrationState.Registering ||
                   fakeRegistration.registrationState.value is RegistrationState.Registered)
    }

    @Test
    fun `login with wrong password shows error`() = runTest(testDispatcher) {
        component.login("wrong")
        advanceUntilIdle()
        val state = component.loginState.value
        assertTrue(state is LoginState.Error)
    }

    @Test
    fun `successful SIP registration navigates to main`() = runTest(testDispatcher) {
        component.login("test123")
        advanceUntilIdle()
        // Simulate SIP registration success
        fakeRegistration.emitRegistered("192.168.30.103")
        advanceUntilIdle()
        assertTrue(navigatedToMain)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.feature.login.LoginComponentTest" --info 2>&1 | tail -20`
Expected: FAIL

- [ ] **Step 3: Implement LoginComponent**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/login/LoginComponent.kt
package uz.yalla.sipphone.feature.login

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.util.PhoneNumberMasker

private val logger = KotlinLogging.logger {}

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data class Error(val message: String) : LoginState
    data class Authenticated(val authResult: AuthResult) : LoginState
}

class LoginComponent(
    componentContext: ComponentContext,
    private val authRepository: AuthRepository,
    private val registrationEngine: RegistrationEngine,
    private val onLoginSuccess: (AuthResult) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComponentContext by componentContext {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    val registrationState: StateFlow<RegistrationState> = registrationEngine.registrationState

    private val scope = coroutineScope()
    private var lastAuthResult: AuthResult? = null

    init {
        scope.launch {
            registrationEngine.registrationState.collect { state ->
                if (state is RegistrationState.Registered && lastAuthResult != null) {
                    logger.info { "SIP registered, navigating to main" }
                    onLoginSuccess(lastAuthResult!!)
                }
            }
        }
    }

    fun login(password: String) {
        if (_loginState.value is LoginState.Loading) return

        _loginState.value = LoginState.Loading
        scope.launch(ioDispatcher) {
            val result = authRepository.login(password)
            result.fold(
                onSuccess = { authResult ->
                    lastAuthResult = authResult
                    _loginState.value = LoginState.Authenticated(authResult)
                    logger.info { "Auth success, registering SIP as ${authResult.sipCredentials.username}" }
                    registrationEngine.register(authResult.sipCredentials)
                },
                onFailure = { error ->
                    _loginState.value = LoginState.Error(error.message ?: "Login failed")
                    logger.warn { "Auth failed: ${error.message}" }
                },
            )
        }
    }

    fun manualConnect(
        server: String,
        port: Int,
        username: String,
        password: String,
    ) {
        val credentials = uz.yalla.sipphone.domain.SipCredentials(
            server = server,
            port = port,
            username = username,
            password = password,
        )
        lastAuthResult = AuthResult(
            sipCredentials = credentials,
            dispatcherUrl = "",
            agent = uz.yalla.sipphone.domain.AgentInfo("manual", username),
        )
        _loginState.value = LoginState.Loading
        scope.launch(ioDispatcher) {
            registrationEngine.register(credentials)
        }
    }
}
```

- [ ] **Step 4: Implement LoginScreen UI**

Create `src/main/kotlin/uz/yalla/sipphone/feature/login/LoginScreen.kt` — Telegram Desktop style login with:
- Yalla logo/title at top center
- Password field with visibility toggle
- Login button (full width, brand primary)
- Loading state: button shows spinner
- Error state: red text below field
- Manual connection expandable (collapsed by default, SIP form inside)
- Version text at bottom

Use `LocalYallaColors.current` for all colors, `LocalAppTokens.current` for spacing.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.feature.login.LoginComponentTest" --info 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(login): add LoginComponent with two-phase auth and LoginScreen UI"
```

---

## Task 9: Toolbar — ToolbarComponent + ToolbarContent

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponent.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarContent.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/AgentStatusDropdown.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/CallControls.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/CallQualityIndicator.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/SettingsPopover.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponentTest.kt`

- [ ] **Step 1: Write ToolbarComponent test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponentTest.kt
package uz.yalla.sipphone.feature.main.toolbar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.FakeCallEngine
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ToolbarComponentTest {
    private val fakeCallEngine = FakeCallEngine()
    private val fakeRegistrationEngine = FakeRegistrationEngine()

    private val component = ToolbarComponent(
        callEngine = fakeCallEngine,
        registrationEngine = fakeRegistrationEngine,
    )

    @Test
    fun `initial agent status is ready`() {
        assertEquals(AgentStatus.READY, component.agentStatus.value)
    }

    @Test
    fun `setAgentStatus updates status`() {
        component.setAgentStatus(AgentStatus.AWAY)
        assertEquals(AgentStatus.AWAY, component.agentStatus.value)
    }

    @Test
    fun `makeCall validates number`() = runTest {
        val result = component.makeCall("abc")
        assertEquals(false, result)
    }

    @Test
    fun `makeCall with valid number succeeds`() = runTest {
        val result = component.makeCall("+998901234567")
        assertEquals(true, result)
    }

    @Test
    fun `call state flows from engine`() {
        assertEquals(CallState.Idle, component.callState.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — `ToolbarComponent` not found

- [ ] **Step 3: Implement ToolbarComponent**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponent.kt
package uz.yalla.sipphone.feature.main.toolbar

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.PhoneNumberValidator
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState

private val logger = KotlinLogging.logger {}

class ToolbarComponent(
    private val callEngine: CallEngine,
    private val registrationEngine: RegistrationEngine,
) {
    val callState: StateFlow<CallState> = callEngine.callState
    val registrationState: StateFlow<RegistrationState> = registrationEngine.registrationState

    private val _agentStatus = MutableStateFlow(AgentStatus.READY)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _phoneInput = MutableStateFlow("")
    val phoneInput: StateFlow<String> = _phoneInput.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun setAgentStatus(status: AgentStatus) {
        _agentStatus.value = status
    }

    fun updatePhoneInput(value: String) {
        _phoneInput.value = value
    }

    fun makeCall(number: String): Boolean {
        val validation = PhoneNumberValidator.validate(number)
        if (validation.isFailure) {
            logger.warn { "Invalid phone number: ${validation.exceptionOrNull()?.message}" }
            return false
        }
        scope.launch { callEngine.makeCall(validation.getOrThrow()) }
        return true
    }

    fun answerCall() {
        scope.launch { callEngine.answerCall() }
    }

    fun rejectCall() {
        scope.launch { callEngine.hangupCall() }
    }

    fun hangupCall() {
        scope.launch { callEngine.hangupCall() }
    }

    fun toggleMute() {
        scope.launch { callEngine.toggleMute() }
    }

    fun toggleHold() {
        scope.launch { callEngine.toggleHold() }
    }

    fun disconnect() {
        scope.launch { registrationEngine.unregister() }
    }
}
```

- [ ] **Step 4: Implement ToolbarContent, AgentStatusDropdown, CallControls, CallQualityIndicator, SettingsPopover**

Create each file as a Compose composable:

**ToolbarContent.kt** — Main toolbar layout: Row with 5 zones (A-E), fixed 56px height, `backgroundSecondary` color, 1px bottom border. Uses `LocalYallaColors`, `LocalAppTokens`.

**AgentStatusDropdown.kt** — Zone A: colored dot + status text + dropdown arrow. `DropdownMenu` with status options. Each option has colored dot + label. Closes on selection or outside click. IMPORTANT: dropdown must not extend below toolbar if it would overlap webview — set `offset` to keep within toolbar.

**CallControls.kt** — Zone C: different content per `CallState`. Idle: `[Call]` button. Incoming: `[Answer]` `[Reject]`. Active: `[Mute]` `[Hold]` `[End]`. On Hold: `[Mute]` `[Resume]` `[End]`. Uses `visibility` approach — all buttons have reserved space, invisible when not active.

**CallQualityIndicator.kt** — Zone E: 12dp colored dot + "Good"/"Fair"/"Poor" text. Only visible during active call.

**SettingsPopover.kt** — Zone D: gear icon, click opens dropdown with theme toggle, manual SIP (expandable), logout button, version.

- [ ] **Step 5: Run tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.feature.main.toolbar.ToolbarComponentTest" --info 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(toolbar): add ToolbarComponent with 5-zone layout, agent status, call controls, settings"
```

---

## Task 10: Main Screen + Webview Placeholder

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainComponent.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainScreen.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/placeholder/WebviewPlaceholder.kt`

- [ ] **Step 1: Implement MainComponent**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/main/MainComponent.kt
package uz.yalla.sipphone.feature.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.feature.main.toolbar.ToolbarComponent

class MainComponent(
    componentContext: ComponentContext,
    val authResult: AuthResult,
    callEngine: CallEngine,
    registrationEngine: RegistrationEngine,
    private val onLogout: () -> Unit,
) : ComponentContext by componentContext {

    val toolbar = ToolbarComponent(
        callEngine = callEngine,
        registrationEngine = registrationEngine,
    )

    val dispatcherUrl: String = authResult.dispatcherUrl
    val agentInfo: AgentInfo = authResult.agent

    private val scope = coroutineScope()

    init {
        // Auto-logout on disconnect (when no active call)
        scope.launch {
            combine(
                registrationEngine.registrationState,
                callEngine.callState,
            ) { regState, callState ->
                val isDisconnected = regState is RegistrationState.Idle || regState is RegistrationState.Failed
                val noActiveCall = callState is CallState.Idle
                isDisconnected && noActiveCall
            }
                .drop(1)
                .first { it }
            onLogout()
        }
    }

    fun logout() {
        toolbar.disconnect()
    }
}
```

- [ ] **Step 2: Implement MainScreen**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/main/MainScreen.kt
package uz.yalla.sipphone.feature.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import uz.yalla.sipphone.feature.main.placeholder.WebviewPlaceholder
import uz.yalla.sipphone.feature.main.toolbar.ToolbarContent

@Composable
fun MainScreen(component: MainComponent) {
    Column(modifier = Modifier.fillMaxSize()) {
        ToolbarContent(
            toolbarComponent = component.toolbar,
            agentName = component.agentInfo.name,
            onLogout = component::logout,
        )
        WebviewPlaceholder(
            dispatcherUrl = component.dispatcherUrl,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}
```

- [ ] **Step 3: Implement WebviewPlaceholder**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/main/placeholder/WebviewPlaceholder.kt
package uz.yalla.sipphone.feature.main.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun WebviewPlaceholder(dispatcherUrl: String, modifier: Modifier = Modifier) {
    val colors = LocalYallaColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundBase),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = Strings.PLACEHOLDER_DISPATCHER,
                color = colors.textSubtle,
            )
            Text(
                text = Strings.PLACEHOLDER_URL.format(dispatcherUrl),
                color = colors.textSubtle,
            )
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(main): add MainComponent, MainScreen, and WebviewPlaceholder"
```

---

## Task 11: Navigation Refactor — Two Windows + Login → Main Flow

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/navigation/Screen.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/navigation/RootComponent.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/navigation/ComponentFactory.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/navigation/ComponentFactoryImpl.kt`
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/Main.kt`
- Delete or keep: `src/main/kotlin/uz/yalla/sipphone/navigation/RootContent.kt` (keep for now, deprecated)

- [ ] **Step 1: Update Screen sealed interface**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/navigation/Screen.kt
package uz.yalla.sipphone.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Screen {
    @Serializable data object Login : Screen
    @Serializable data object Main : Screen
    // Keep old screens for compilation, mark deprecated
    @Deprecated("Use Login") @Serializable data object Registration : Screen
    @Deprecated("Use Main") @Serializable data object Dialer : Screen
}
```

- [ ] **Step 2: Update ComponentFactory**

```kotlin
// Add to ComponentFactory interface:
fun createLogin(context: ComponentContext, onLoginSuccess: (AuthResult) -> Unit): LoginComponent
fun createMain(context: ComponentContext, authResult: AuthResult, onLogout: () -> Unit): MainComponent
```

- [ ] **Step 3: Update ComponentFactoryImpl**

Add implementations using Koin to get `AuthRepository`, `RegistrationEngine`, `CallEngine`.

- [ ] **Step 4: Rewrite Main.kt with two-window approach**

Key changes:
- No SIP init at startup (moved to login flow)
- `application` block with two `Window` composables
- Login window: 420x520, centered, not resizable, `alwaysOnTop = false`
- Main window: maximized, `alwaysOnTop = true`, min 1280x720
- Main window: no minimize (intercept `isMinimized` and restore), close confirmation dialog
- Window title: "Yalla SIP Phone — {Agent Name}"
- Theme state in `MutableStateFlow<Boolean>` for dark mode toggle
- Shutdown hook for SIP cleanup

- [ ] **Step 5: Fix compilation — update all references**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | tail -30`
Fix any remaining compilation errors.

- [ ] **Step 6: Run all tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -20`
Expected: ALL PASS (may need to fix old tests referencing Registration/Dialer screens)

- [ ] **Step 7: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(navigation): two-window approach, Login → Main flow, always-on-top, no minimize"
```

---

## Task 12: Desktop Features — Notifications + Ringtone + Close Behavior

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt` (close confirmation)
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainComponent.kt` (notification + ringtone on incoming)

- [ ] **Step 1: Add close confirmation dialog**

In Main.kt's main window `onCloseRequest`, show a Swing `JOptionPane.showConfirmDialog` asking "Logout and close Yalla SIP Phone?". If confirmed, SIP unregister + exit. If cancelled, do nothing.

- [ ] **Step 2: Add incoming call notification**

In `MainComponent` or `ToolbarComponent`, observe `callState` changes. When `CallState.Ringing(isOutbound = false)`:
- macOS: run `osascript -e 'display notification "Incoming Call" with title "Yalla SIP Phone" sound name "default"'` via `ProcessBuilder`
- Play bundled ringtone via `javax.sound.sampled.Clip` in a loop
- Stop ringtone when call state leaves `Ringing` (answered or ended)
- CRITICAL: stop ringtone BEFORE `answerCall()` is called

- [ ] **Step 3: Add ringtone resource**

Place a short professional `.wav` file at `src/main/resources/ringtone.wav` (or generate a simple tone programmatically for MVP).

- [ ] **Step 4: Test manually**

Run the app, verify:
- Close button shows confirmation dialog
- (If SIP server available) incoming call plays ringtone

- [ ] **Step 5: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(desktop): add close confirmation, incoming call notification, ringtone"
```

---

## Task 13: Keyboard Shortcuts

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt` (AWT-level key listener)
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponent.kt` (shortcut actions)

- [ ] **Step 1: Register AWT-level keyboard shortcuts**

In Main.kt, after main window is created, register `Toolkit.getDefaultToolkit().addAWTEventListener`:

```kotlin
Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
    if (event is java.awt.event.KeyEvent && event.id == java.awt.event.KeyEvent.KEY_PRESSED) {
        val ctrl = event.isControlDown || event.isMetaDown
        val shift = event.isShiftDown
        when {
            // Ctrl+Enter = answer
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_ENTER ->
                toolbar?.answerCall()
            // Ctrl+Shift+E = reject/end
            ctrl && shift && event.keyCode == java.awt.event.KeyEvent.VK_E ->
                toolbar?.let { if (isIncoming) it.rejectCall() else it.hangupCall() }
            // Ctrl+M = mute
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_M ->
                toolbar?.toggleMute()
            // Ctrl+H = hold
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_H ->
                toolbar?.toggleHold()
            // Ctrl+L = focus phone input
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_L ->
                { /* request focus on phone input field */ }
        }
    }
}, java.awt.AWTEvent.KEY_EVENT_MASK)
```

Note: When JCEF is added in Session 2, these will be moved to `CefKeyboardHandler.onPreKeyEvent()`.

- [ ] **Step 2: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(shortcuts): add AWT-level keyboard shortcuts (Ctrl+Enter, Ctrl+Shift+E, Ctrl+M, Ctrl+H)"
```

---

## Task 14: Integration Test — Full Flow

- [ ] **Step 1: Run full build**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -20`
Expected: ALL PASS

- [ ] **Step 3: Manual smoke test**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew run`

Verify:
1. Login window appears (centered, 420x520)
2. Enter "test123" → loading spinner → SIP registration → main window
3. Main window: maximized, always on top, toolbar + placeholder panel
4. Agent status dropdown works
5. Dark/light theme toggle in settings
6. Close shows confirmation dialog
7. Phone input field works, Call button validates number
8. Ctrl+L focuses phone input

- [ ] **Step 4: Update VISION.md**

Add Phase 3.5 entry documenting UI redesign progress.

- [ ] **Step 5: Final commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && git add -A && git commit -m "feat(ui-redesign): complete Session 1 — login, toolbar, theme, desktop features"
```

---

## Summary

| Task | Description | Est. Time |
|------|-------------|-----------|
| 1 | Domain: AgentStatus, AgentInfo, AuthResult, AuthRepository | 15 min |
| 2 | Domain: PhoneNumberValidator + Masker | 20 min |
| 3 | Domain: callId in CallState + setMute/setHold | 30 min |
| 4 | pjsip: retry fix, log level, mute-after-hold, hold guard | 20 min |
| 5 | Mock Auth Backend | 20 min |
| 6 | Yalla Brand Theme (colors + dark mode) | 45 min |
| 7 | Strings Update | 10 min |
| 8 | Login Screen (Component + UI) | 60 min |
| 9 | Toolbar (Component + 5 zones + all states) | 90 min |
| 10 | Main Screen + Placeholder | 20 min |
| 11 | Navigation Refactor (two windows) | 60 min |
| 12 | Desktop Features (notifications, ringtone, close) | 30 min |
| 13 | Keyboard Shortcuts | 15 min |
| 14 | Integration + Smoke Test | 20 min |
| **Total** | | **~7.5 hours** |
