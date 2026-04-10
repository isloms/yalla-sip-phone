# Codebase Quality Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all CRITICAL/HIGH issues from full codebase audit — PJSIP thread safety, race conditions, lifecycle leaks, architecture violations.

**Architecture:** Four phases — PJSIP threading safety (1 architectural fix resolves 8 issues), quick safety fixes (atomic ops, validation, timeouts), architecture cleanup (DI, lifecycle, logout), code quality polish.

**Tech Stack:** Kotlin, PJSIP (JNI), Coroutines, Decompose, Koin, JCEF, Ktor

---

## Phase 1: PJSIP Thread Safety

> One architectural change — marshal ALL native callbacks to pjDispatcher — fixes CRITICAL issues #1-3, HIGH #7, MEDIUM #11 from concurrency audit.

### Task 1: Marshal PJSIP native callbacks to pjDispatcher

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccount.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCall.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccountManager.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCallManager.kt`

**Context:** PJSIP native callbacks (`onRegState`, `onCallState`, `onCallMediaState`, `onIncomingCall`) fire from a native C++ thread. These callbacks mutate shared state (`currentCall`, `accounts` map, `_callState` MutableStateFlow, `holdInProgress`) without synchronization. The `pjDispatcher` is a `newSingleThreadContext("pjsip")` — all PJSIP operations should be serialized onto it.

**Key constraint:** PJSUA2 callback parameters (e.g., `OnRegStateParam`) may be invalidated after the callback returns. You MUST capture needed values into local variables before dispatching to pjDispatcher.

- [ ] **Step 1: Add CoroutineScope to PjsipAccount and PjsipCall**

Both classes need a scope to launch onto pjDispatcher. They already receive their manager references — add a `CoroutineScope` parameter.

`PjsipAccount.kt`:
```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import uz.yalla.sipphone.domain.SipConstants

private val logger = KotlinLogging.logger {}

class PjsipAccount(
    private val accountId: String,
    private val accountManager: PjsipAccountManager,
    private val pjScope: CoroutineScope,
) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        if (accountManager.isAccountDestroyed()) return

        // Capture values before dispatching — prm may be invalidated after callback returns
        val code = prm.code
        val reason = prm.reason
        val expiration = prm.expiration

        pjScope.launch {
            try {
                val info = this@PjsipAccount.getInfo()
                try {
                    val regIsActive = info.regIsActive

                    val state = when {
                        code / 100 == 2 && regIsActive -> {
                            val server = SipConstants.extractHostFromUri(info.uri)
                            PjsipRegistrationState.Registered(server = server, expiresSeconds = expiration)
                        }
                        code / 100 == 2 && !regIsActive -> PjsipRegistrationState.Idle
                        code == SipConstants.STATUS_REQUEST_TIMEOUT || code == SipConstants.STATUS_SERVICE_UNAVAILABLE ->
                            PjsipRegistrationState.Error(code, reason, retryable = true)
                        else -> PjsipRegistrationState.Error(code, reason, retryable = false)
                    }

                    logger.info { "[$accountId] Registration: code=$code ($reason), active=$regIsActive, exp=$expiration" }
                    accountManager.updateRegistrationState(accountId, state)
                } finally {
                    info.delete()
                }
            } catch (e: Exception) {
                logger.error(e) { "[$accountId] Error in onRegState" }
            }
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        if (accountManager.isAccountDestroyed()) return

        val callId = prm.callId

        pjScope.launch {
            accountManager.incomingCallListener?.onIncomingCall(accountId, callId)
        }
    }
}
```

`PjsipCall.kt`:
```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjsip_inv_state
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

class PjsipCall : Call {
    private val callManager: PjsipCallManager
    private val deleted = AtomicBoolean(false)
    private val pjScope: CoroutineScope

    constructor(callManager: PjsipCallManager, account: Account, pjScope: CoroutineScope) : super(account) {
        this.callManager = callManager
        this.pjScope = pjScope
    }

    constructor(callManager: PjsipCallManager, account: Account, callId: Int, pjScope: CoroutineScope) : super(account, callId) {
        this.callManager = callManager
        this.pjScope = pjScope
    }

    override fun onCallState(prm: OnCallStateParam) {
        if (callManager.isCallManagerDestroyed()) return

        pjScope.launch {
            try {
                val info = this@PjsipCall.getInfo()
                try {
                    val state = info.state
                    when (state) {
                        pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> callManager.onCallConfirmed(this@PjsipCall)
                        pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> callManager.onCallDisconnected(this@PjsipCall)
                        else -> {}
                    }
                } finally {
                    info.delete()
                }
            } catch (e: Exception) {
                logger.error(e) { "Error in onCallState" }
            }
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        if (callManager.isCallManagerDestroyed()) return

        pjScope.launch {
            callManager.connectCallAudio(this@PjsipCall)
        }
    }

    fun safeDelete() {
        if (deleted.compareAndSet(false, true)) {
            try {
                delete()
            } catch (e: Exception) {
                logger.warn(e) { "PjsipCall.delete() failed" }
            }
        }
    }
}
```

- [ ] **Step 2: Update PjsipCallManager to pass pjScope when creating calls**

In `PjsipCallManager.kt`, everywhere `PjsipCall(...)` is constructed, pass the scope:

Replace constructor calls:
- `PjsipCall(this, acc, callId)` → `PjsipCall(this, acc, callId, scope)`
- `PjsipCall(this, acc)` → `PjsipCall(this, acc, scope)`

Lines to change: ~99 (makeCall), ~293, ~300 (onIncomingCall)

- [ ] **Step 3: Update PjsipAccountManager to pass pjScope when creating accounts**

In `PjsipAccountManager.kt`, the `register()` method creates `PjsipAccount`. Add a `pjScope` parameter:

```kotlin
class PjsipAccountManager(
    private val isDestroyed: () -> Boolean,
    private val pjScope: CoroutineScope,
) : AccountProvider {
```

In `register()`, change:
```kotlin
val account = PjsipAccount(accountId, this, pjScope)
```

- [ ] **Step 4: Update PjsipEngine to provide pjScope to managers**

In `PjsipEngine.kt`, create a scope from the pjDispatcher and pass it:

```kotlin
private val pjScope = CoroutineScope(SupervisorJob() + closeableDispatcher)

private val accountManager = PjsipAccountManager(
    isDestroyed = { destroyed.get() },
    pjScope = pjScope,
)
```

- [ ] **Step 5: Make PjsipAccountManager collections private**

In `PjsipAccountManager.kt`:
```kotlin
private val accounts = mutableMapOf<String, PjsipAccount>()
```

Add accessor methods needed by external code:
```kotlin
fun getAccount(accountId: String): PjsipAccount? = accounts[accountId]

override fun getFirstConnectedAccount(): PjsipAccount? {
    return accounts.entries.firstOrNull { (id, _) ->
        _accountStates[id]?.value is PjsipRegistrationState.Registered
    }?.value
}
```

- [ ] **Step 6: Add `@Volatile` to PjsipCallManager.holdInProgress**

```kotlin
@Volatile
private var holdInProgress = false
```

- [ ] **Step 7: Add holdInProgress timeout**

In `PjsipCallManager.kt`, after setting `holdInProgress = true` in `applyHoldState()`:

```kotlin
private fun applyHoldState(call: PjsipCall, onHold: Boolean) {
    holdInProgress = true
    holdTimeoutJob?.cancel()
    holdTimeoutJob = scope.launch {
        delay(15_000)
        if (holdInProgress) {
            logger.warn { "Hold operation timed out — resetting flag" }
            holdInProgress = false
        }
    }
    // ... existing hold/unhold logic ...
}
```

- [ ] **Step 8: Fix PjsipEndpointManager.destroy() — remove Thread.sleep**

In `PjsipEndpointManager.kt`, replace:
```kotlin
System.gc()
Thread.sleep(100)
```
with just:
```kotlin
System.gc()
```

The sleep was hoping GC would complete, but `System.gc()` is best-effort anyway. The real safety comes from properly deleting PJSIP objects in `callManager.destroy()` and `accountManager.destroy()` before reaching this point.

- [ ] **Step 9: Compile and verify**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/data/pjsip/
git commit -m "fix(pjsip): marshal native callbacks to pjDispatcher

All PJSIP native callbacks (onRegState, onCallState, onCallMediaState,
onIncomingCall) now dispatch work to the single-thread pjDispatcher.
This eliminates data races on shared state (currentCall, accounts map,
callState, holdInProgress) between native and pjDispatcher threads.

Also: make accounts map private, add holdInProgress timeout,
remove Thread.sleep from shutdown, add @Volatile annotations.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2: Quick Safety Fixes

### Task 2: LogoutOrchestrator — AtomicBoolean + finally

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/auth/LogoutOrchestrator.kt`

- [ ] **Step 1: Replace @Volatile boolean with AtomicBoolean**

```kotlin
package uz.yalla.sipphone.data.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.domain.SipAccountManager
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

class LogoutOrchestrator(
    private val sipAccountManager: SipAccountManager,
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
) {
    private val logoutInProgress = AtomicBoolean(false)

    suspend fun logout() {
        if (!logoutInProgress.compareAndSet(false, true)) return
        try {
            logger.info { "Logout sequence starting..." }

            runCatching { authApi.logout() }
                .onFailure { logger.warn { "Server logout failed: ${it.message}" } }

            tokenProvider.clearToken()

            runCatching { sipAccountManager.unregisterAll() }
                .onFailure { logger.warn { "SIP unregisterAll failed: ${it.message}" } }

            logger.info { "Logout sequence complete" }
        } finally {
            logoutInProgress.set(false)
        }
    }

    fun reset() {
        logoutInProgress.set(false)
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/data/auth/LogoutOrchestrator.kt
git commit -m "fix(auth): use AtomicBoolean in LogoutOrchestrator

Replaces @Volatile check-then-act with atomic compareAndSet.
Adds finally block to reset flag on failure.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: BridgeEventEmitter — @Volatile fields

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeEventEmitter.kt`

- [ ] **Step 1: Add @Volatile to cross-thread fields**

Change line 17-19:
```kotlin
@Volatile
private var currentBrowser: CefBrowser? = null

@Volatile
var agentInfo: AgentInfo = AgentInfo("", "")
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeEventEmitter.kt
git commit -m "fix(bridge): add @Volatile to BridgeEventEmitter cross-thread fields

currentBrowser is set from EDT, read from Dispatchers.Main.
agentInfo is set from MainComponent.init, read from JCEF callback thread.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: LoginComponent — SIP registration timeout

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/login/LoginComponent.kt`

- [ ] **Step 1: Add timeout to registerAndNavigate**

Replace lines 96-115 `registerAndNavigate()`:
```kotlin
private suspend fun registerAndNavigate(authResult: AuthResult) {
    try {
        logger.info { "Registering ${authResult.sipAccounts.size} SIP account(s)" }
        sipAccountManager.registerAll(authResult.sipAccounts)

        val connected = withTimeoutOrNull(15_000) {
            sipAccountManager.accounts.first { accs ->
                accs.any { it.state is SipAccountState.Connected }
            }
        }

        if (connected == null) {
            logger.warn { "SIP registration timed out after 15s" }
            _loginState.value = LoginState.Error("SIP registration timed out. Check server settings.")
            return
        }

        logger.info { "SIP connected, navigating to main" }
        onLoginSuccess(authResult)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "SIP registration failed" }
        _loginState.value = LoginState.Error(e.message ?: "Registration failed")
    }
}
```

Add import at top:
```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/feature/login/LoginComponent.kt
git commit -m "fix(login): add 15s timeout to SIP registration

Prevents infinite loading if SIP server is unreachable.
Shows error message on timeout instead of hanging forever.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: BridgeSecurity — atomic computeIfAbsent

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeSecurity.kt`

- [ ] **Step 1: Replace getOrPut with computeIfAbsent**

Line 24, change:
```kotlin
val timestamps = commandTimestamps.getOrPut(command) { ArrayDeque() }
```
to:
```kotlin
val timestamps = commandTimestamps.computeIfAbsent(command) { ArrayDeque() }
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeSecurity.kt
git commit -m "fix(bridge): use atomic computeIfAbsent in BridgeSecurity

ConcurrentHashMap.getOrPut is NOT atomic in Kotlin stdlib.
computeIfAbsent is atomic and prevents duplicate ArrayDeque creation.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: ApiResponse — crash-safe errorMessage

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/network/ApiResponse.kt`

- [ ] **Step 1: Fix errorMessage to handle non-primitive JSON**

Replace lines 19-25:
```kotlin
fun ApiResponse<*>.errorMessage(): String {
    return when (val e = errors) {
        is JsonPrimitive -> e.content
        is JsonObject -> e.entries.joinToString { "${it.key}: ${it.value}" }
        else -> message ?: "Unknown error"
    }
}
```

Change `it.value.jsonPrimitive.content` → `it.value` (calls `toString()`, handles nested objects).

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/data/network/ApiResponse.kt
git commit -m "fix(network): prevent crash on nested JSON error responses

ApiResponse.errorMessage() crashed with IllegalArgumentException
when server returned non-primitive values in error object.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: DTMF/transfer callId validation + CallEngine interface

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCallManager.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/domain/CallEngine.kt`

- [ ] **Step 1: Add callId validation to sendDtmf and transferCall**

In `PjsipCallManager.kt`, add at start of `sendDtmf()`:
```kotlin
suspend fun sendDtmf(callId: String, digits: String): Result<Unit> {
    val call = currentCall ?: return Result.failure(IllegalStateException("No active call"))
    if (currentCallId != callId) return Result.failure(IllegalStateException("Call ID mismatch"))
    if (!digits.matches(Regex("[0-9*#A-Da-d]+"))) return Result.failure(IllegalArgumentException("Invalid DTMF digits"))
    // ... rest unchanged
}
```

Same for `transferCall()`:
```kotlin
suspend fun transferCall(callId: String, destination: String): Result<Unit> {
    val call = currentCall ?: return Result.failure(IllegalStateException("No active call"))
    if (currentCallId != callId) return Result.failure(IllegalStateException("Call ID mismatch"))
    // ... rest unchanged
}
```

- [ ] **Step 2: Make CallEngine methods return Result consistently**

In `CallEngine.kt`:
```kotlin
interface CallEngine {
    val callState: StateFlow<CallState>
    suspend fun makeCall(number: String): Result<Unit>
    suspend fun answerCall(): Result<Unit>
    suspend fun hangupCall(): Result<Unit>
    suspend fun toggleMute(): Result<Unit>
    suspend fun toggleHold(): Result<Unit>
    suspend fun setMute(callId: String, muted: Boolean): Result<Unit>
    suspend fun setHold(callId: String, onHold: Boolean): Result<Unit>
    suspend fun sendDtmf(callId: String, digits: String): Result<Unit>
    suspend fun transferCall(callId: String, destination: String): Result<Unit>
}
```

Update `PjsipEngine.kt` delegates to wrap in `Result.success(Unit)` where needed:
```kotlin
override suspend fun answerCall(): Result<Unit> = withContext(closeableDispatcher) {
    callManager.answerCall()
    Result.success(Unit)
}
```

Update callers in `ToolbarComponent.kt` and `BridgeRouter.kt` to handle `Result` where they currently call these methods.

- [ ] **Step 3: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/domain/CallEngine.kt \
        src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCallManager.kt \
        src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipEngine.kt \
        src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponent.kt \
        src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeRouter.kt
git commit -m "fix(call): validate callId in DTMF/transfer, unify CallEngine to Result

All CallEngine methods now return Result<Unit> consistently.
sendDtmf and transferCall validate callId matches active call.
DTMF digits validated against [0-9*#A-D] pattern.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: HTTP Authorization header sanitization

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/network/HttpClientFactory.kt`

- [ ] **Step 1: Filter Authorization header from logs**

In the Logging config, add header sanitizer:
```kotlin
install(Logging) {
    logger = object : Logger {
        override fun log(message: String) {
            httpLogger.debug { message }
        }
    }
    level = LogLevel.HEADERS
    sanitizeHeader { header -> header == "Authorization" }
}
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/data/network/HttpClientFactory.kt
git commit -m "fix(network): sanitize Authorization header in HTTP logs

Bearer tokens were logged in plaintext at HEADERS log level.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: TokenProvider — remove misleading Mutex

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/auth/TokenProvider.kt`

- [ ] **Step 1: Simplify InMemoryTokenProvider**

```kotlin
class InMemoryTokenProvider : TokenProvider {
    @Volatile
    private var token: String? = null

    override suspend fun getToken(): String? = token
    override suspend fun setToken(token: String) { this.token = token }
    override suspend fun clearToken() { this.token = null }
}
```

Remove unused `Mutex` import and field.

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/data/auth/TokenProvider.kt
git commit -m "refactor(auth): remove misleading Mutex from TokenProvider

@Volatile already provides visibility guarantees for simple
reference reads/writes. Mutex was only used in set/clear but
not in get, making it both inconsistent and unnecessary.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3: Architecture Cleanup

### Task 10: ToolbarComponent — lifecycle-aware scope

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponent.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainComponent.kt`

- [ ] **Step 1: Accept CoroutineScope in ToolbarComponent**

Replace unmanaged scope with parent-provided scope:

```kotlin
class ToolbarComponent(
    private val callEngine: CallEngine,
    private val sipAccountManager: SipAccountManager,
    private val scope: CoroutineScope,
) {
    val callState: StateFlow<CallState> = callEngine.callState
    val accounts: StateFlow<List<SipAccount>> = sipAccountManager.accounts
    // ... remove private scope creation
    // ... remove destroy() method's scope.cancel() — parent handles it
```

Make `callEngine` and `sipAccountManager` private.

- [ ] **Step 2: Pass MainComponent's scope to ToolbarComponent**

In `MainComponent.kt`:
```kotlin
val toolbar = ToolbarComponent(
    callEngine = callEngine,
    sipAccountManager = sipAccountManager,
    scope = scope,
)
```

Remove `toolbar.destroy()` from `doOnDestroy` — scope cancellation is handled by Decompose lifecycle.

Keep `toolbar.closeRingtone()` (renamed from destroy) for audio resource cleanup:
```kotlin
lifecycle.doOnDestroy {
    toolbar.releaseAudioResources()
    eventEmitter.detach()
    bridgeRouter?.dispose()
    jcefManager.teardownBridge()
}
```

- [ ] **Step 3: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarComponent.kt \
        src/main/kotlin/uz/yalla/sipphone/feature/main/MainComponent.kt
git commit -m "refactor(toolbar): bind ToolbarComponent to parent lifecycle scope

ToolbarComponent no longer creates its own unmanaged CoroutineScope.
It receives the parent's scope, ensuring automatic cancellation when
MainComponent is destroyed. Also makes callEngine/sipAccountManager private.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Move MockAuthRepository to test sources

**Files:**
- Move: `src/main/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt` → `src/test/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt`

- [ ] **Step 1: Move file**

```bash
mkdir -p src/test/kotlin/uz/yalla/sipphone/data/auth
mv src/main/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt \
   src/test/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt
```

- [ ] **Step 2: Verify no production code imports it**

```bash
grep -r "MockAuthRepository" src/main/ || echo "No production references"
```

- [ ] **Step 3: Compile and commit**

```bash
./gradlew compileKotlin
git add -A src/main/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt \
           src/test/kotlin/uz/yalla/sipphone/data/auth/MockAuthRepository.kt
git commit -m "chore(auth): move MockAuthRepository to test sources

Contains hardcoded SIP credentials that should not ship in production.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Fix ComponentFactoryImpl — remove service locator

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/navigation/ComponentFactoryImpl.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/di/FeatureModule.kt`

- [ ] **Step 1: Inject dependencies directly**

```kotlin
package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import uz.yalla.sipphone.data.auth.AuthRepository
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent

class ComponentFactoryImpl(
    private val authRepository: AuthRepository,
    private val sipAccountManager: SipAccountManager,
    private val callEngine: CallEngine,
    private val jcefManager: JcefManager,
    private val eventEmitter: BridgeEventEmitter,
    private val security: BridgeSecurity,
    private val auditLog: BridgeAuditLog,
) : ComponentFactory {
    override fun createLogin(
        componentContext: ComponentContext,
        onLoginSuccess: (AuthResult) -> Unit,
    ): LoginComponent = LoginComponent(
        componentContext = componentContext,
        authRepository = authRepository,
        sipAccountManager = sipAccountManager,
        onLoginSuccess = onLoginSuccess,
    )

    override fun createMain(
        componentContext: ComponentContext,
        authResult: AuthResult,
        onLogout: () -> Unit,
    ): MainComponent = MainComponent(
        componentContext = componentContext,
        authResult = authResult,
        callEngine = callEngine,
        sipAccountManager = sipAccountManager,
        jcefManager = jcefManager,
        eventEmitter = eventEmitter,
        security = security,
        auditLog = auditLog,
        onLogout = onLogout,
    )
}
```

- [ ] **Step 2: Update FeatureModule**

```kotlin
val featureModule = module {
    single<ComponentFactory> {
        ComponentFactoryImpl(
            authRepository = get(),
            sipAccountManager = get(),
            callEngine = get(),
            jcefManager = get(),
            eventEmitter = get(),
            security = get(),
            auditLog = get(),
        )
    }
}
```

- [ ] **Step 3: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/navigation/ComponentFactoryImpl.kt \
        src/main/kotlin/uz/yalla/sipphone/di/FeatureModule.kt
git commit -m "refactor(di): replace service locator with constructor injection

ComponentFactoryImpl no longer receives raw Koin instance.
All dependencies are explicitly declared in the constructor,
making them testable and visible.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Phase 4: UI & Polish

### Task 13: Fix AWT event listener leak

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt`

- [ ] **Step 1: Switch to DisposableEffect**

Replace lines 134-139:
```kotlin
DisposableEffect(Unit) {
    val listener = java.awt.event.AWTEventListener { event ->
        if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED) {
            handleKeyboardShortcut(event, rootComponent)
        }
    }
    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK)
    onDispose {
        java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/Main.kt
git commit -m "fix(ui): remove AWT event listener on dispose

LaunchedEffect(Unit) never cleaned up the listener.
Switch to DisposableEffect with proper removeAWTEventListener.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: Fix AppTokens recomposition

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt`

- [ ] **Step 1: Hoist AppTokens to avoid recreation**

Replace `LocalAppTokens provides AppTokens()` with a remembered instance:
```kotlin
val tokens = remember { AppTokens() }
CompositionLocalProvider(
    LocalAppTokens provides tokens,
    // ...
)
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt
git commit -m "perf(theme): remember AppTokens to avoid recomposition allocations

AppTokens was recreated on every recomposition, triggering
unnecessary downstream recompositions.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: Fix SipConstants USER_AGENT version mismatch

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/domain/SipConstants.kt`

- [ ] **Step 1: Derive USER_AGENT from APP_VERSION**

```kotlin
val USER_AGENT = "YallaSipPhone/$APP_VERSION"
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/uz/yalla/sipphone/domain/SipConstants.kt
git commit -m "fix(sip): sync USER_AGENT with APP_VERSION

USER_AGENT was hardcoded to 1.0 while APP_VERSION was 1.2.0.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 16: Remove unused AgentStatus display properties

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/domain/AgentStatus.kt`

- [ ] **Step 1: Remove displayName and colorHex**

These are never used — UI uses `DisplayAgentStatus` and `YallaColors` instead.

```kotlin
enum class AgentStatus {
    READY,
    AWAY,
    BUSY,
    OFFLINE,
}
```

- [ ] **Step 2: Compile and verify no references broke**

```bash
./gradlew compileKotlin
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/domain/AgentStatus.kt
git commit -m "refactor(domain): remove unused displayName/colorHex from AgentStatus

UI layer uses DisplayAgentStatus and YallaColors for all display logic.
These enum properties were dead code.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **Full build**: `./gradlew clean build`
- [ ] **Run app**: `./gradlew run` — test login/logout cycle
- [ ] **Package DMG**: `./gradlew packageDmg`
