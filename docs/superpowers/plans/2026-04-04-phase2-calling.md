# Phase 2: Calling Functionality — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add inbound/outbound calling with mute, hold, and call timer to Yalla SIP Phone.

**Architecture:** Extend PjsipBridge to implement new CallEngine interface alongside existing registration. New PjsipCall class wraps pjsua2.Call. DialerComponent manages call state + timer. DialerScreen renders state-driven UI with hidden/visible controls per UX spec.

**Tech Stack:** Kotlin, Compose Desktop, pjsua2 JNI, Decompose, Koin, Coroutines/StateFlow

**Spec:** `docs/superpowers/specs/2026-04-04-phase2-calling-design.md`

---

## File Structure

### New Files
- `src/main/kotlin/uz/yalla/sipphone/domain/CallState.kt` — call state sealed interface
- `src/main/kotlin/uz/yalla/sipphone/domain/RegistrationEngine.kt` — registration interface (extracted from SipEngine)
- `src/main/kotlin/uz/yalla/sipphone/domain/CallEngine.kt` — call interface
- `src/main/kotlin/uz/yalla/sipphone/domain/RemoteUriParser.kt` — SIP URI parser utility
- `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCall.kt` — pjsua2 Call wrapper
- `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngine.kt` — call test double
- `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngineTest.kt` — test double validation
- `src/test/kotlin/uz/yalla/sipphone/domain/RemoteUriParserTest.kt` — parser tests
- `src/test/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponentTest.kt` — dialer tests

### Modified Files
- `src/main/kotlin/uz/yalla/sipphone/domain/SipEngine.kt` — delete (replaced by RegistrationEngine + CallEngine)
- `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipBridge.kt` — implement CallEngine, add call methods
- `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccount.kt` — add onIncomingCall callback
- `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt` — multi-bind PjsipBridge
- `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponent.kt` — rewrite with call logic
- `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerScreen.kt` — rewrite with state-driven UI
- `src/main/kotlin/uz/yalla/sipphone/feature/registration/RegistrationComponent.kt` — update import SipEngine → RegistrationEngine
- `src/main/kotlin/uz/yalla/sipphone/navigation/RootComponent.kt` — update dialer factory signature
- `src/main/kotlin/uz/yalla/sipphone/Main.kt` — update Koin gets and factory wiring
- `src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngine.kt` — rename to FakeRegistrationEngine
- `src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngineTest.kt` — rename to FakeRegistrationEngineTest
- `src/test/kotlin/uz/yalla/sipphone/feature/registration/RegistrationComponentTest.kt` — update import

### Unchanged Files
- `src/main/kotlin/uz/yalla/sipphone/domain/RegistrationState.kt`
- `src/main/kotlin/uz/yalla/sipphone/domain/SipCredentials.kt`
- `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipLogWriter.kt`
- `src/main/kotlin/uz/yalla/sipphone/data/settings/AppSettings.kt`
- `src/main/kotlin/uz/yalla/sipphone/feature/registration/RegistrationModel.kt`
- `src/main/kotlin/uz/yalla/sipphone/feature/registration/RegistrationScreen.kt`
- `src/main/kotlin/uz/yalla/sipphone/navigation/Screen.kt`
- `src/main/kotlin/uz/yalla/sipphone/navigation/RootContent.kt`
- `src/main/kotlin/uz/yalla/sipphone/ui/**`
- `src/test/kotlin/uz/yalla/sipphone/data/settings/AppSettingsTest.kt`
- `src/test/kotlin/uz/yalla/sipphone/feature/registration/RegistrationModelTest.kt`

---

### Task 1: Domain — CallState sealed interface

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/CallState.kt`

- [ ] **Step 1: Create CallState.kt**

```kotlin
package uz.yalla.sipphone.domain

sealed interface CallState {
    data object Idle : CallState
    data class Ringing(
        val callerNumber: String,
        val callerName: String?,
    ) : CallState
    data class Active(
        val remoteNumber: String,
        val remoteName: String?,
        val isOutbound: Boolean,
        val isMuted: Boolean,
        val isOnHold: Boolean,
    ) : CallState
    data object Ending : CallState
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/domain/CallState.kt
git commit -m "feat(domain): add CallState sealed interface for call lifecycle"
```

---

### Task 2: Domain — Split SipEngine into RegistrationEngine + CallEngine

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/RegistrationEngine.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/CallEngine.kt`
- Delete: `src/main/kotlin/uz/yalla/sipphone/domain/SipEngine.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipBridge.kt:29,35`
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/registration/RegistrationComponent.kt` (import)
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponent.kt:10,13`
- Modify: `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt:8,11`
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt:20,31,44,46`
- Modify: `src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngine.kt:6`
- Modify: `src/test/kotlin/uz/yalla/sipphone/feature/registration/RegistrationComponentTest.kt:13`

- [ ] **Step 1: Create RegistrationEngine.kt**

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface RegistrationEngine {
    val registrationState: StateFlow<RegistrationState>

    suspend fun init(): Result<Unit>
    suspend fun register(credentials: SipCredentials): Result<Unit>
    suspend fun unregister()
    suspend fun destroy()
}
```

- [ ] **Step 2: Create CallEngine.kt**

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface CallEngine {
    val callState: StateFlow<CallState>

    suspend fun makeCall(number: String): Result<Unit>
    suspend fun answerCall()
    suspend fun hangupCall()
    suspend fun toggleMute()
    suspend fun toggleHold()
}
```

- [ ] **Step 3: Delete SipEngine.kt**

```bash
git rm src/main/kotlin/uz/yalla/sipphone/domain/SipEngine.kt
```

- [ ] **Step 4: Update PjsipBridge to implement RegistrationEngine (CallEngine added in Task 9)**

In `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipBridge.kt`:

Replace line 29:
```kotlin
import uz.yalla.sipphone.domain.SipEngine
```
with:
```kotlin
import uz.yalla.sipphone.domain.RegistrationEngine
```

Replace line 35:
```kotlin
class PjsipBridge : SipEngine {
```
with:
```kotlin
class PjsipBridge : RegistrationEngine {
```

- [ ] **Step 5: Update FakeSipEngine to implement RegistrationEngine**

In `src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngine.kt`:

Replace line 6:
```kotlin
class FakeSipEngine : SipEngine {
```
with:
```kotlin
class FakeSipEngine : RegistrationEngine {
```

Add import after line 4:
```kotlin
import uz.yalla.sipphone.domain.RegistrationEngine
```

Remove the old SipEngine import (no longer exists).

- [ ] **Step 6: Update DialerComponent import**

In `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponent.kt`:

Replace line 10:
```kotlin
import uz.yalla.sipphone.domain.SipEngine
```
with:
```kotlin
import uz.yalla.sipphone.domain.RegistrationEngine
```

Replace lines 12-14:
```kotlin
class DialerComponent(
    componentContext: ComponentContext,
    private val sipEngine: SipEngine,
    private val onDisconnected: () -> Unit,
```
with:
```kotlin
class DialerComponent(
    componentContext: ComponentContext,
    private val registrationEngine: RegistrationEngine,
    private val onDisconnected: () -> Unit,
```

Replace line 17:
```kotlin
    val registrationState: StateFlow<RegistrationState> = sipEngine.registrationState
```
with:
```kotlin
    val registrationState: StateFlow<RegistrationState> = registrationEngine.registrationState
```

Replace line 24:
```kotlin
            sipEngine.registrationState
```
with:
```kotlin
            registrationEngine.registrationState
```

Replace line 32:
```kotlin
        scope.launch { sipEngine.unregister() }
```
with:
```kotlin
        scope.launch { registrationEngine.unregister() }
```

- [ ] **Step 7: Update RegistrationComponent import**

In `src/main/kotlin/uz/yalla/sipphone/feature/registration/RegistrationComponent.kt`:

Replace import:
```kotlin
import uz.yalla.sipphone.domain.SipEngine
```
with:
```kotlin
import uz.yalla.sipphone.domain.RegistrationEngine
```

Replace the constructor parameter `sipEngine: SipEngine` with `sipEngine: RegistrationEngine`. (The parameter name `sipEngine` can stay — it's used throughout the class.)

- [ ] **Step 8: Update AppModule.kt**

Replace entire file content:
```kotlin
package uz.yalla.sipphone.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import uz.yalla.sipphone.data.pjsip.PjsipBridge
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.RegistrationEngine

val appModule = module {
    singleOf(::PjsipBridge) bind RegistrationEngine::class
    singleOf(::AppSettings)
}
```

- [ ] **Step 9: Update Main.kt**

Replace line 20:
```kotlin
import uz.yalla.sipphone.domain.SipEngine
```
with:
```kotlin
import uz.yalla.sipphone.domain.RegistrationEngine
```

Replace line 31:
```kotlin
    val sipEngine: SipEngine = koin.get()
```
with:
```kotlin
    val sipEngine: RegistrationEngine = koin.get()
```

Replace line 46:
```kotlin
                DialerComponent(ctx, sipEngine, onDisconnected)
```
with:
```kotlin
                DialerComponent(ctx, sipEngine, onDisconnected)
```
(No change needed here yet — `sipEngine` is `RegistrationEngine` which matches the updated DialerComponent constructor.)

- [ ] **Step 10: Update RegistrationComponentTest import**

In `src/test/kotlin/uz/yalla/sipphone/feature/registration/RegistrationComponentTest.kt`:

Line 13 — `FakeSipEngine` import stays (class not renamed yet, that's Task 5).
No changes needed here yet.

- [ ] **Step 11: Verify all tests pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, all 34 tests pass

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor(domain): split SipEngine into RegistrationEngine + CallEngine interfaces"
```

---

### Task 3: Domain — remoteUri parser

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/RemoteUriParser.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/domain/RemoteUriParserTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteUriParserTest {

    @Test
    fun `parses display name and number from standard URI`() {
        val (name, number) = parseRemoteUri(""""Alex Petrov" <sip:102@192.168.0.22>""")
        assertEquals("Alex Petrov", name)
        assertEquals("102", number)
    }

    @Test
    fun `parses URI without display name`() {
        val (name, number) = parseRemoteUri("<sip:+998901234567@192.168.0.22>")
        assertNull(name)
        assertEquals("+998901234567", number)
    }

    @Test
    fun `parses URI with port in host`() {
        val (name, number) = parseRemoteUri(""""Operator" <sip:201@10.0.0.1:5060>""")
        assertEquals("Operator", name)
        assertEquals("201", number)
    }

    @Test
    fun `parses bare sip URI without angle brackets`() {
        val (name, number) = parseRemoteUri("sip:100@server.local")
        assertNull(name)
        assertEquals("100", number)
    }

    @Test
    fun `handles empty string gracefully`() {
        val (name, number) = parseRemoteUri("")
        assertNull(name)
        assertEquals("", number)
    }

    @Test
    fun `handles malformed URI — returns raw input as number`() {
        val (name, number) = parseRemoteUri("not-a-sip-uri")
        assertNull(name)
        assertEquals("not-a-sip-uri", number)
    }

    @Test
    fun `parses display name with special characters`() {
        val (name, number) = parseRemoteUri(""""O'Brien, John" <sip:300@host>""")
        assertEquals("O'Brien, John", name)
        assertEquals("300", number)
    }

    @Test
    fun `parses URI with transport parameter`() {
        val (name, number) = parseRemoteUri(""""Test" <sip:102@host;transport=udp>""")
        assertEquals("Test", name)
        assertEquals("102", number)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.RemoteUriParserTest" 2>&1 | tail -5`
Expected: FAIL — `parseRemoteUri` not found

- [ ] **Step 3: Implement the parser**

```kotlin
package uz.yalla.sipphone.domain

data class CallerInfo(
    val displayName: String?,
    val number: String,
)

private val WITH_NAME = Regex("""^"([^"]+)"\s*<sip:([^@]+)@[^>]+>$""")
private val WITHOUT_NAME = Regex("""^<?sip:([^@]+)@[^>]+>?$""")

fun parseRemoteUri(uri: String): CallerInfo {
    WITH_NAME.find(uri)?.let { match ->
        return CallerInfo(
            displayName = match.groupValues[1],
            number = match.groupValues[2],
        )
    }
    WITHOUT_NAME.find(uri)?.let { match ->
        return CallerInfo(
            displayName = null,
            number = match.groupValues[1],
        )
    }
    return CallerInfo(displayName = null, number = uri)
}
```

- [ ] **Step 4: Update tests to use CallerInfo destructuring**

The tests use `val (name, number) = parseRemoteUri(...)`. Since `CallerInfo` is a data class, destructuring works. Verify the return type matches.

- [ ] **Step 5: Run tests — verify they pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.RemoteUriParserTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 8 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/domain/RemoteUriParser.kt \
        src/test/kotlin/uz/yalla/sipphone/domain/RemoteUriParserTest.kt
git commit -m "feat(domain): add SIP remote URI parser with CallerInfo"
```

---

### Task 4: Test doubles — FakeCallEngine

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngine.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngineTest.kt`

- [ ] **Step 1: Write FakeCallEngineTest**

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeCallEngineTest {

    @Test
    fun `initial state is Idle`() {
        val engine = FakeCallEngine()
        assertIs<CallState.Idle>(engine.callState.value)
    }

    @Test
    fun `makeCall stores last number`() = runTest {
        val engine = FakeCallEngine()
        engine.makeCall("+998901234567")
        assertEquals("+998901234567", engine.lastCallNumber)
    }

    @Test
    fun `answerCall increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateRinging("102", "Alex")
        engine.answerCall()
        assertEquals(1, engine.answerCallCount)
    }

    @Test
    fun `hangupCall increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.hangupCall()
        assertEquals(1, engine.hangupCallCount)
    }

    @Test
    fun `toggleMute increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.toggleMute()
        assertEquals(1, engine.toggleMuteCount)
    }

    @Test
    fun `toggleHold increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.toggleHold()
        assertEquals(1, engine.toggleHoldCount)
    }

    @Test
    fun `simulateRinging sets Ringing state`() {
        val engine = FakeCallEngine()
        engine.simulateRinging("102", "Alex")
        val state = engine.callState.value
        assertIs<CallState.Ringing>(state)
        assertEquals("102", state.callerNumber)
        assertEquals("Alex", state.callerName)
    }

    @Test
    fun `simulateActive sets Active state`() {
        val engine = FakeCallEngine()
        engine.simulateActive(remoteNumber = "102", remoteName = "Alex", isOutbound = false)
        val state = engine.callState.value
        assertIs<CallState.Active>(state)
        assertEquals("102", state.remoteNumber)
        assertEquals(false, state.isOutbound)
    }

    @Test
    fun `simulateIdle resets to Idle`() {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.simulateIdle()
        assertIs<CallState.Idle>(engine.callState.value)
    }

    @Test
    fun `makeCall returns success by default`() = runTest {
        val engine = FakeCallEngine()
        assertTrue(engine.makeCall("102").isSuccess)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.FakeCallEngineTest" 2>&1 | tail -5`
Expected: FAIL — `FakeCallEngine` not found

- [ ] **Step 3: Implement FakeCallEngine**

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCallEngine : CallEngine {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState = _callState.asStateFlow()

    var lastCallNumber: String? = null
    var answerCallCount = 0
    var hangupCallCount = 0
    var toggleMuteCount = 0
    var toggleHoldCount = 0

    override suspend fun makeCall(number: String): Result<Unit> {
        lastCallNumber = number
        return Result.success(Unit)
    }

    override suspend fun answerCall() {
        answerCallCount++
    }

    override suspend fun hangupCall() {
        hangupCallCount++
    }

    override suspend fun toggleMute() {
        toggleMuteCount++
    }

    override suspend fun toggleHold() {
        toggleHoldCount++
    }

    fun simulateRinging(callerNumber: String = "102", callerName: String? = null) {
        _callState.value = CallState.Ringing(callerNumber, callerName)
    }

    fun simulateActive(
        remoteNumber: String = "102",
        remoteName: String? = null,
        isOutbound: Boolean = false,
        isMuted: Boolean = false,
        isOnHold: Boolean = false,
    ) {
        _callState.value = CallState.Active(remoteNumber, remoteName, isOutbound, isMuted, isOnHold)
    }

    fun simulateIdle() {
        _callState.value = CallState.Idle
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.domain.FakeCallEngineTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 10 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngine.kt \
        src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngineTest.kt
git commit -m "test(domain): add FakeCallEngine test double"
```

---

### Task 5: Rename FakeSipEngine → FakeRegistrationEngine

**Files:**
- Rename: `src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngine.kt` → `FakeRegistrationEngine.kt`
- Rename: `src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngineTest.kt` → `FakeRegistrationEngineTest.kt`
- Modify: `src/test/kotlin/uz/yalla/sipphone/feature/registration/RegistrationComponentTest.kt:13`

- [ ] **Step 1: Rename files and update class names**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git mv src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngine.kt \
       src/test/kotlin/uz/yalla/sipphone/domain/FakeRegistrationEngine.kt
git mv src/test/kotlin/uz/yalla/sipphone/domain/FakeSipEngineTest.kt \
       src/test/kotlin/uz/yalla/sipphone/domain/FakeRegistrationEngineTest.kt
```

- [ ] **Step 2: Update FakeRegistrationEngine.kt**

Replace entire content:
```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeRegistrationEngine : RegistrationEngine {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState = _registrationState.asStateFlow()

    var initCalled = false
    var lastCredentials: SipCredentials? = null

    override suspend fun init() = Result.success(Unit).also { initCalled = true }

    override suspend fun register(credentials: SipCredentials): Result<Unit> {
        lastCredentials = credentials
        _registrationState.value = RegistrationState.Registering
        return Result.success(Unit)
    }

    override suspend fun unregister() {
        _registrationState.value = RegistrationState.Idle
    }

    override suspend fun destroy() {
        _registrationState.value = RegistrationState.Idle
    }

    fun simulateRegistered(server: String = "sip:102@192.168.0.22") {
        _registrationState.value = RegistrationState.Registered(server)
    }

    fun simulateFailed(message: String = "403 Forbidden") {
        _registrationState.value = RegistrationState.Failed(message)
    }
}
```

- [ ] **Step 3: Update FakeRegistrationEngineTest.kt**

Replace all occurrences of `FakeSipEngine` with `FakeRegistrationEngine` and update class name to `FakeRegistrationEngineTest`. The test logic stays identical.

- [ ] **Step 4: Update RegistrationComponentTest.kt**

Replace line 13:
```kotlin
import uz.yalla.sipphone.domain.FakeSipEngine
```
with:
```kotlin
import uz.yalla.sipphone.domain.FakeRegistrationEngine
```

Replace all occurrences of `FakeSipEngine` with `FakeRegistrationEngine` in the file (in `createComponent` parameter type and test methods).

- [ ] **Step 5: Run all tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(test): rename FakeSipEngine to FakeRegistrationEngine"
```

---

### Task 6: DialerComponent — rewrite with call logic

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponent.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponentTest.kt`

- [ ] **Step 1: Write DialerComponentTest**

```kotlin
package uz.yalla.sipphone.feature.dialer

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.FakeCallEngine
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DialerComponentTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createComponent(
        registrationEngine: FakeRegistrationEngine = FakeRegistrationEngine().apply { simulateRegistered() },
        callEngine: FakeCallEngine = FakeCallEngine(),
        onDisconnected: () -> Unit = {},
    ): Triple<DialerComponent, FakeRegistrationEngine, FakeCallEngine> {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val component = DialerComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            registrationEngine = registrationEngine,
            callEngine = callEngine,
            onDisconnected = onDisconnected,
            ioDispatcher = testDispatcher,
        )
        return Triple(component, registrationEngine, callEngine)
    }

    @Test
    fun `makeCall delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()

        component.makeCall("+998901234567")
        advanceUntilIdle()

        assertEquals("+998901234567", callEngine.lastCallNumber)
    }

    @Test
    fun `answerCall delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateRinging("102", "Alex")

        component.answerCall()
        advanceUntilIdle()

        assertEquals(1, callEngine.answerCallCount)
    }

    @Test
    fun `hangupCall delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateActive()

        component.hangupCall()
        advanceUntilIdle()

        assertEquals(1, callEngine.hangupCallCount)
    }

    @Test
    fun `toggleMute delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateActive()

        component.toggleMute()
        advanceUntilIdle()

        assertEquals(1, callEngine.toggleMuteCount)
    }

    @Test
    fun `toggleHold delegates to CallEngine`() = runTest {
        val (component, _, callEngine) = createComponent()
        callEngine.simulateActive()

        component.toggleHold()
        advanceUntilIdle()

        assertEquals(1, callEngine.toggleHoldCount)
    }

    @Test
    fun `registration drop with no active call triggers onDisconnected`() = runTest {
        var disconnected = false
        val (_, regEngine, _) = createComponent(onDisconnected = { disconnected = true })

        regEngine.simulateFailed("timeout")
        advanceUntilIdle()

        assertEquals(true, disconnected)
    }

    @Test
    fun `registration drop during active call does NOT trigger onDisconnected`() = runTest {
        var disconnected = false
        val (_, regEngine, callEngine) = createComponent(onDisconnected = { disconnected = true })

        callEngine.simulateActive()
        advanceUntilIdle()
        regEngine.simulateFailed("timeout")
        advanceUntilIdle()

        assertEquals(false, disconnected)
    }

    @Test
    fun `callState exposes CallEngine state`() {
        val (component, _, callEngine) = createComponent()

        callEngine.simulateRinging("102", "Alex")

        val state = component.callState.value
        assertIs<CallState.Ringing>(state)
        assertEquals("102", state.callerNumber)
    }

    @Test
    fun `callDuration resets on Idle`() = runTest {
        val (component, _, callEngine) = createComponent()

        callEngine.simulateActive()
        advanceUntilIdle()
        callEngine.simulateIdle()
        advanceUntilIdle()

        assertEquals(0L, component.callDuration.value)
    }

    @Test
    fun `disconnect delegates to RegistrationEngine`() = runTest {
        val (component, regEngine, _) = createComponent()

        component.disconnect()
        advanceUntilIdle()

        assertIs<RegistrationState.Idle>(regEngine.registrationState.value)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.feature.dialer.DialerComponentTest" 2>&1 | tail -5`
Expected: FAIL — constructor signature mismatch

- [ ] **Step 3: Rewrite DialerComponent**

Replace entire `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponent.kt`:

```kotlin
package uz.yalla.sipphone.feature.dialer

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState

class DialerComponent(
    componentContext: ComponentContext,
    private val registrationEngine: RegistrationEngine,
    private val callEngine: CallEngine,
    private val onDisconnected: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComponentContext by componentContext {

    val registrationState: StateFlow<RegistrationState> = registrationEngine.registrationState
    val callState: StateFlow<CallState> = callEngine.callState

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private val scope = coroutineScope()

    init {
        // Navigate back on registration drop — only when no active call
        scope.launch(ioDispatcher) {
            registrationEngine.registrationState
                .drop(1)
                .first { state ->
                    val isDisconnected = state is RegistrationState.Idle || state is RegistrationState.Failed
                    val noActiveCall = callEngine.callState.value is CallState.Idle
                    isDisconnected && noActiveCall
                }
            onDisconnected()
        }

        // Call timer
        scope.launch {
            callEngine.callState.collectLatest { state ->
                if (state is CallState.Active) {
                    _callDuration.value = 0
                    while (true) {
                        delay(1000)
                        _callDuration.value++
                    }
                } else {
                    _callDuration.value = 0
                }
            }
        }
    }

    fun makeCall(number: String) {
        scope.launch { callEngine.makeCall(number) }
    }

    fun answerCall() {
        scope.launch { callEngine.answerCall() }
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

- [ ] **Step 4: Run tests — verify they pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.feature.dialer.DialerComponentTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 11 tests pass

- [ ] **Step 5: Run ALL tests to check nothing broke**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponent.kt \
        src/test/kotlin/uz/yalla/sipphone/feature/dialer/DialerComponentTest.kt
git commit -m "feat(dialer): rewrite DialerComponent with call engine + timer"
```

---

### Task 7: pjsip — PjsipCall class

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCall.kt`

- [ ] **Step 1: Create PjsipCall.kt**

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status

private val logger = KotlinLogging.logger {}

class PjsipCall(private val bridge: PjsipBridge) : Call() {

    override fun onCallState(prm: OnCallStateParam) {
        if (bridge.isDestroyed()) return
        var info: CallInfo? = null
        try {
            info = getInfo()
            val state = info.state

            logger.info { "Call state: ${info.stateText} (${info.lastStatusCode})" }

            when (state) {
                pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> {
                    bridge.onCallConfirmed(this)
                }
                pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                    bridge.onCallDisconnected(this)
                }
                else -> {
                    // EARLY, CONNECTING, etc. — logged but no state change
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onCallState callback" }
        } finally {
            info?.delete()
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        if (bridge.isDestroyed()) return
        var info: CallInfo? = null
        try {
            info = getInfo()
            for (i in 0 until info.media.size().toInt()) {
                val mediaInfo = info.media[i]
                if (mediaInfo.type == org.pjsip.pjsua2.pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    mediaInfo.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                ) {
                    val audioMedia = getAudioMedia(i)
                    val playbackMedia = bridge.getPlaybackDevMedia()
                    audioMedia.startTransmit(playbackMedia)
                    playbackMedia.delete()

                    val captureMedia = bridge.getCaptureDevMedia()
                    captureMedia.startTransmit(audioMedia)
                    captureMedia.delete()

                    logger.info { "Audio media connected for media index $i" }
                    break
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onCallMediaState callback" }
        } finally {
            info?.delete()
        }
    }
}
```

- [ ] **Step 2: Verify it compiles (will have compile errors until PjsipBridge is updated in Task 9)**

This file references `bridge.onCallConfirmed()`, `bridge.onCallDisconnected()`, `bridge.getPlaybackDevMedia()`, `bridge.getCaptureDevMedia()` which don't exist yet. These are added in Task 9. **Skip compilation check — proceed to Task 8.**

- [ ] **Step 3: Commit (partial — compiles after Task 9)**

```bash
git add src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCall.kt
git commit -m "feat(pjsip): add PjsipCall class — call lifecycle and audio routing"
```

---

### Task 8: pjsip — PjsipAccount onIncomingCall

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccount.kt`

- [ ] **Step 1: Add onIncomingCall override**

In `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccount.kt`, add imports:

```kotlin
import org.pjsip.pjsua2.OnIncomingCallParam
import uz.yalla.sipphone.domain.parseRemoteUri
```

Add method after `onRegState` (after line 38, before closing brace):

```kotlin
    override fun onIncomingCall(prm: OnIncomingCallParam) {
        if (bridge.isDestroyed()) return
        try {
            bridge.onIncomingCall(prm.callId)
        } catch (e: Exception) {
            logger.error(e) { "Error in onIncomingCall callback" }
        }
    }
```

- [ ] **Step 2: Commit (partial — compiles after Task 9)**

```bash
git add src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccount.kt
git commit -m "feat(pjsip): add onIncomingCall callback to PjsipAccount"
```

---

### Task 9: pjsip — PjsipBridge implements CallEngine

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipBridge.kt`

- [ ] **Step 1: Add imports**

Add these imports to PjsipBridge.kt (after existing imports):

```kotlin
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.CallOpParam
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.parseRemoteUri
```

- [ ] **Step 2: Update class declaration**

Replace:
```kotlin
class PjsipBridge : RegistrationEngine {
```
with:
```kotlin
class PjsipBridge : RegistrationEngine, CallEngine {
```

- [ ] **Step 3: Add call state fields after line 42**

After `override val registrationState` line, add:

```kotlin
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var currentCall: PjsipCall? = null
    private var lastRegisteredServer: String? = null
```

- [ ] **Step 4: Track server in register method**

In the `register()` method, after `account = PjsipAccount(this@PjsipBridge).apply {` block (around line 139), the server is already available. Add tracking inside `updateRegistrationState`:

Update `updateRegistrationState` (line 51-53) to:

```kotlin
    internal fun updateRegistrationState(state: RegistrationState) {
        if (state is RegistrationState.Registered) {
            lastRegisteredServer = state.server
        }
        _registrationState.value = state
    }
```

- [ ] **Step 5: Add CallEngine implementation methods**

Add before the `destroy()` method:

```kotlin
    override suspend fun makeCall(number: String): Result<Unit> = withContext(pjDispatcher) {
        if (currentCall != null) return@withContext Result.failure(IllegalStateException("Call already active"))
        val acc = account ?: return@withContext Result.failure(IllegalStateException("Not registered"))

        try {
            val call = PjsipCall(this@PjsipBridge)
            val uri = "sip:$number@${extractHost(lastRegisteredServer)}"
            val prm = CallOpParam(true)
            call.makeCall(uri, prm)
            prm.delete()
            currentCall = call
            _callState.value = CallState.Active(
                remoteNumber = number,
                remoteName = null,
                isOutbound = true,
                isMuted = false,
                isOnHold = false,
            )
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "makeCall failed" }
            _callState.value = CallState.Idle
            Result.failure(e)
        }
    }

    override suspend fun answerCall() = withContext(pjDispatcher) {
        val call = currentCall ?: return@withContext
        val state = _callState.value
        if (state !is CallState.Ringing) return@withContext
        try {
            val prm = CallOpParam()
            prm.statusCode = 200
            call.answer(prm)
            prm.delete()
        } catch (e: Exception) {
            logger.error(e) { "answerCall failed" }
        }
    }

    override suspend fun hangupCall() = withContext(pjDispatcher) {
        val call = currentCall ?: return@withContext
        try {
            _callState.value = CallState.Ending
            val prm = CallOpParam()
            call.hangup(prm)
            prm.delete()
        } catch (e: Exception) {
            logger.error(e) { "hangupCall failed" }
            clearCurrentCall()
        }
    }

    override suspend fun toggleMute() = withContext(pjDispatcher) {
        val state = _callState.value
        if (state !is CallState.Active) return@withContext
        try {
            val captureMedia = endpoint.audDevManager().captureDevMedia
            if (state.isMuted) {
                captureMedia.adjustRxLevel(1.0f)
            } else {
                captureMedia.adjustRxLevel(0.0f)
            }
            captureMedia.delete()
            _callState.value = state.copy(isMuted = !state.isMuted)
        } catch (e: Exception) {
            logger.error(e) { "toggleMute failed" }
        }
    }

    override suspend fun toggleHold() = withContext(pjDispatcher) {
        val state = _callState.value
        if (state !is CallState.Active) return@withContext
        val call = currentCall ?: return@withContext
        try {
            val prm = CallOpParam()
            if (state.isOnHold) {
                prm.opt.flag = 0
                call.reinvite(prm)
            } else {
                call.setHold(prm)
            }
            prm.delete()
            _callState.value = state.copy(isOnHold = !state.isOnHold)
        } catch (e: Exception) {
            logger.error(e) { "toggleHold failed" }
        }
    }
```

- [ ] **Step 6: Add internal callback methods for PjsipCall**

```kotlin
    internal fun onCallConfirmed(call: PjsipCall) {
        val state = _callState.value
        if (state is CallState.Ringing) {
            _callState.value = CallState.Active(
                remoteNumber = state.callerNumber,
                remoteName = state.callerName,
                isOutbound = false,
                isMuted = false,
                isOnHold = false,
            )
        }
        // For outbound calls, state is already Active from makeCall()
    }

    internal fun onCallDisconnected(call: PjsipCall) {
        clearCurrentCall()
        try {
            call.delete()
        } catch (e: Exception) {
            logger.warn(e) { "Error deleting call object" }
        }
    }

    internal fun onIncomingCall(callId: Int) {
        if (currentCall != null) {
            // Already in a call — reject incoming
            logger.warn { "Rejecting incoming call (already in call)" }
            try {
                val call = PjsipCall(this)
                val acc = account ?: return
                call.makeCallFromId(acc, callId)
                val prm = CallOpParam()
                prm.statusCode = 486 // Busy Here
                call.hangup(prm)
                prm.delete()
                call.delete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to reject incoming call" }
            }
            return
        }

        try {
            val call = PjsipCall(this)
            val acc = account ?: return
            call.makeCallFromId(acc, callId)
            currentCall = call

            val info = call.getInfo()
            val callerInfo = parseRemoteUri(info.remoteUri)
            info.delete()

            _callState.value = CallState.Ringing(
                callerNumber = callerInfo.number,
                callerName = callerInfo.displayName,
            )
            logger.info { "Incoming call from: ${callerInfo.displayName ?: callerInfo.number}" }
        } catch (e: Exception) {
            logger.error(e) { "Error handling incoming call" }
            clearCurrentCall()
        }
    }

    private fun clearCurrentCall() {
        currentCall = null
        _callState.value = CallState.Idle
    }

    internal fun getPlaybackDevMedia(): AudioMedia =
        endpoint.audDevManager().playbackDevMedia

    internal fun getCaptureDevMedia(): AudioMedia =
        endpoint.audDevManager().captureDevMedia

    private fun extractHost(serverUri: String?): String {
        // serverUri is like "sip:102@192.168.0.22" — extract host
        val uri = serverUri ?: return ""
        val atIndex = uri.lastIndexOf('@')
        return if (atIndex >= 0) uri.substring(atIndex + 1) else uri
    }
```

- [ ] **Step 7: Update destroy() to hangup active call**

Replace the `destroy()` method:

```kotlin
    override suspend fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        withContext(pjDispatcher) {
            // Hangup active call first
            currentCall?.let { call ->
                try {
                    val prm = CallOpParam()
                    call.hangup(prm)
                    prm.delete()
                } catch (_: Exception) {}
            }
            currentCall = null
            _callState.value = CallState.Idle

            pollJob?.cancel()
            pollJob?.join()

            try { account?.shutdown() } catch (_: Exception) {}
            account = null
            logWriter = null

            _registrationState.value = RegistrationState.Idle
        }
        scope.cancel()
        pjDispatcher.close()
    }
```

- [ ] **Step 8: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Run all tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipBridge.kt \
        src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCall.kt \
        src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccount.kt
git commit -m "feat(pjsip): implement CallEngine in PjsipBridge — call lifecycle, audio, mute, hold"
```

---

### Task 10: DI and wiring

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/navigation/RootComponent.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt`

- [ ] **Step 1: Update AppModule.kt**

Replace entire content:

```kotlin
package uz.yalla.sipphone.di

import org.koin.dsl.bind
import org.koin.dsl.module
import uz.yalla.sipphone.data.pjsip.PjsipBridge
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.RegistrationEngine

val appModule = module {
    single { PjsipBridge() } bind RegistrationEngine::class bind CallEngine::class
    single { AppSettings() }
}
```

- [ ] **Step 2: Update RootComponent.kt — dialerFactory signature**

Replace entire content:

```kotlin
package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent

class RootComponent(
    componentContext: ComponentContext,
    private val registrationFactory: (ComponentContext, onRegistered: () -> Unit) -> RegistrationComponent,
    private val dialerFactory: (ComponentContext, onDisconnected: () -> Unit) -> DialerComponent,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Screen>()

    val childStack: Value<ChildStack<Screen, Child>> = childStack(
        source = navigation,
        serializer = Screen.serializer(),
        initialConfiguration = Screen.Registration,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    private fun createChild(screen: Screen, context: ComponentContext): Child =
        when (screen) {
            is Screen.Registration -> Child.Registration(
                registrationFactory(context) { navigation.pushNew(Screen.Dialer) }
            )
            is Screen.Dialer -> Child.Dialer(
                dialerFactory(context) { navigation.pop() }
            )
        }

    sealed interface Child {
        data class Registration(val component: RegistrationComponent) : Child
        data class Dialer(val component: DialerComponent) : Child
    }
}
```

(No actual changes needed — RootComponent doesn't know about CallEngine. The factory signature stays the same. But verify it still works.)

- [ ] **Step 3: Update Main.kt**

Replace imports and Koin section. Key changes: get both RegistrationEngine and CallEngine from Koin, pass both to DialerComponent factory.

Replace lines 20-21:
```kotlin
import uz.yalla.sipphone.domain.RegistrationEngine
```
with:
```kotlin
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.RegistrationEngine
```

Replace lines 31-32:
```kotlin
    val sipEngine: RegistrationEngine = koin.get()
    val initResult = runBlocking { sipEngine.init() }
```
with:
```kotlin
    val registrationEngine: RegistrationEngine = koin.get()
    val callEngine: CallEngine = koin.get()
    val initResult = runBlocking { registrationEngine.init() }
```

Replace lines 34-39 (error dialog):
```kotlin
    if (initResult.isFailure) {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Failed to initialize SIP engine:\n${initResult.exceptionOrNull()?.message}",
            "Yalla SIP Phone - Error",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        return
    }
```
(No change needed — stays the same.)

Replace lines 44-47:
```kotlin
            registrationFactory = { ctx, onRegistered ->
                RegistrationComponent(ctx, sipEngine, appSettings, onRegistered)
            },
            dialerFactory = { ctx, onDisconnected ->
                DialerComponent(ctx, sipEngine, onDisconnected)
            },
```
with:
```kotlin
            registrationFactory = { ctx, onRegistered ->
                RegistrationComponent(ctx, registrationEngine, appSettings, onRegistered)
            },
            dialerFactory = { ctx, onDisconnected ->
                DialerComponent(ctx, registrationEngine, callEngine, onDisconnected)
            },
```

Replace line 56:
```kotlin
                runBlocking { withTimeoutOrNull(3000) { sipEngine.destroy() } }
```
with:
```kotlin
                runBlocking { withTimeoutOrNull(3000) { registrationEngine.destroy() } }
```

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run all tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt \
        src/main/kotlin/uz/yalla/sipphone/navigation/RootComponent.kt \
        src/main/kotlin/uz/yalla/sipphone/Main.kt
git commit -m "chore(di): wire CallEngine through Koin, RootComponent, and Main"
```

---

### Task 11: DialerScreen — state-driven UI

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerScreen.kt`

- [ ] **Step 1: Rewrite DialerScreen.kt**

```kotlin
package uz.yalla.sipphone.feature.dialer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalExtendedColors

@Composable
fun DialerScreen(component: DialerComponent) {
    val tokens = LocalAppTokens.current
    val extendedColors = LocalExtendedColors.current
    val registrationState by component.registrationState.collectAsState()
    val callState by component.callState.collectAsState()
    val callDuration by component.callDuration.collectAsState()

    var phoneNumber by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }

    when (registrationState) {
        is RegistrationState.Registered -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Spacebar &&
                            callState is CallState.Ringing &&
                            !isInputFocused
                        ) {
                            component.answerCall()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                // Status bar
                StatusBar(
                    server = (registrationState as RegistrationState.Registered).server,
                    successColor = extendedColors.success,
                    tokens = tokens,
                )

                // Call zone + controls
                Column(
                    modifier = Modifier.weight(1f).padding(tokens.spacingMd),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (val state = callState) {
                        is CallState.Idle -> {
                            IdleContent()
                            Spacer(Modifier.height(tokens.spacingLg))
                            PhoneInput(
                                phoneNumber = phoneNumber,
                                onPhoneNumberChange = { phoneNumber = it },
                                onCall = { component.makeCall(phoneNumber) },
                                enabled = true,
                                onFocusChanged = { isInputFocused = it },
                            )
                        }
                        is CallState.Ringing -> {
                            RingingContent(
                                callerName = state.callerName,
                                callerNumber = state.callerNumber,
                                onAnswer = component::answerCall,
                                onReject = component::hangupCall,
                            )
                        }
                        is CallState.Active -> {
                            ActiveCallContent(
                                remoteName = state.remoteName,
                                remoteNumber = state.remoteNumber,
                                duration = callDuration,
                                isMuted = state.isMuted,
                                isOnHold = state.isOnHold,
                                onToggleMute = component::toggleMute,
                                onToggleHold = component::toggleHold,
                                onHangup = component::hangupCall,
                            )
                        }
                        is CallState.Ending -> {
                            EndingContent()
                        }
                    }
                }

                // Disconnect link
                TextButton(
                    onClick = component::disconnect,
                    modifier = Modifier.fillMaxWidth().padding(bottom = tokens.spacingSm),
                    enabled = callState is CallState.Idle,
                ) {
                    Text(
                        "Disconnect",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Connection lost — returning...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusBar(
    server: String,
    successColor: androidx.compose.ui.graphics.Color,
    tokens: uz.yalla.sipphone.ui.theme.AppTokens,
) {
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(tokens.spacingSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(successColor),
            )
            Spacer(Modifier.width(tokens.spacingSm))
            Text(server, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun IdleContent() {
    Text(
        "READY",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun PhoneInput(
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    onCall: () -> Unit,
    enabled: Boolean,
    onFocusChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = onPhoneNumberChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            placeholder = { Text("Phone number") },
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onCall,
            enabled = enabled && phoneNumber.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = "Call")
            Spacer(Modifier.width(4.dp))
            Text("Call")
        }
    }
}

@Composable
private fun RingingContent(
    callerName: String?,
    callerNumber: String,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "INCOMING CALL",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (callerName != null) {
            Text(
                callerName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            callerNumber,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onAnswer,
                modifier = Modifier.weight(2f).height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalExtendedColors.current.success,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Answer")
                Text(
                    " (Space)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                )
            }
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Reject")
            }
        }
    }
}

@Composable
private fun ActiveCallContent(
    remoteName: String?,
    remoteNumber: String,
    duration: Long,
    isMuted: Boolean,
    isOnHold: Boolean,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    onHangup: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // State indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOnHold) MaterialTheme.colorScheme.tertiary
                        else LocalExtendedColors.current.success,
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (isOnHold) "ON HOLD" else "ACTIVE",
                style = MaterialTheme.typography.labelMedium,
                color = if (isOnHold) MaterialTheme.colorScheme.tertiary
                else LocalExtendedColors.current.success,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        // Caller info
        if (remoteName != null) {
            Text(
                remoteName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            remoteNumber,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Timer
        Spacer(Modifier.height(16.dp))
        Text(
            formatDuration(duration),
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            ),
            color = if (isOnHold) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )

        // Controls
        Spacer(Modifier.height(32.dp))

        // Safe actions row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onToggleMute,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (isMuted) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ) else ButtonDefaults.outlinedButtonColors(),
            ) {
                Icon(
                    if (isMuted) Icons.Filled.MicOff else Icons.Filled.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isMuted) "Unmute" else "Mute")
            }
            OutlinedButton(
                onClick = onToggleHold,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (isOnHold) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
            ) {
                Icon(
                    if (isOnHold) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isOnHold) "Resume" else "Hold")
            }
        }

        // Hangup — separate, deliberate
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onHangup,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("End Call")
        }
    }
}

@Composable
private fun EndingContent() {
    Text(
        "Ending call...",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerScreen.kt
git commit -m "feat(ui): rewrite DialerScreen — state-driven UI with call controls"
```

---

### Task 12: Build verification

**Files:** None (verification only)

- [ ] **Step 1: Run full build**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew build 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests explicitly**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test 2>&1 | tail -15`
Expected: All tests pass (34 original + ~29 new = ~63 total)

- [ ] **Step 3: Check for compilation warnings**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin 2>&1 | grep -i "warning" | head -10`
Expected: No critical warnings

- [ ] **Step 4: Update VISION.md — Phase 2 status**

Add to VISION.md after "## Current State: Phase 1 Complete":

Update to reflect Phase 2 implementation is in progress/complete.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "docs: update VISION.md with Phase 2 status"
```

---

## Summary

| Task | What | New Tests |
|------|------|-----------|
| 1 | CallState sealed interface | — |
| 2 | Split SipEngine → RegistrationEngine + CallEngine | — |
| 3 | remoteUri parser | 8 tests |
| 4 | FakeCallEngine test double | 10 tests |
| 5 | Rename FakeSipEngine → FakeRegistrationEngine | — (existing) |
| 6 | DialerComponent rewrite | 11 tests |
| 7 | PjsipCall class | — (JNI, manual) |
| 8 | PjsipAccount onIncomingCall | — (JNI, manual) |
| 9 | PjsipBridge CallEngine impl | — (JNI, manual) |
| 10 | DI + wiring | — |
| 11 | DialerScreen UI | — (Compose, visual) |
| 12 | Build verification | — |

**Total new automated tests: ~29**
**Total estimated tests after Phase 2: ~63**
