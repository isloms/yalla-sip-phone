# Phase 2: Calling Functionality — Design Spec

## Overview

Add calling functionality to Yalla SIP Phone: inbound call handling (primary), outbound calls (secondary), mute, hold, call timer. Single call model — one call at a time.

**Constraints:**
- Inbound dominant: 90-95% of calls
- Single call model: Oktell queue manages distribution
- Manual answer: Space hotkey (only during ringing, only when focus not in text field)
- Call controls via mouse: hangup, mute, hold
- Call timer visible during active call
- No dial pad, no search, no notes, no transfer, no DTMF

## Domain Model

### Interface Segregation

Split `SipEngine` into two interfaces. `PjsipBridge` implements both.

```kotlin
interface RegistrationEngine {
    val registrationState: StateFlow<RegistrationState>
    suspend fun init(): Result<Unit>
    suspend fun register(credentials: SipCredentials): Result<Unit>
    suspend fun unregister()
    suspend fun destroy()
}

interface CallEngine {
    val callState: StateFlow<CallState>
    suspend fun makeCall(number: String): Result<Unit>
    suspend fun answerCall()
    suspend fun hangupCall()
    suspend fun toggleMute()
    suspend fun toggleHold()
}
```

### CallState

```kotlin
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

- `Ringing` — incoming call, not yet answered
- `Active` — call in progress, `isMuted`/`isOnHold` flags embedded
- `Ending` — hangup sent, waiting for DISCONNECTED callback
- Call timer computed in DialerComponent (seconds since Active), not in domain

### RegistrationState

Unchanged from Phase 1.

## pjsip Implementation

### PjsipBridge Changes

`PjsipBridge` implements `RegistrationEngine + CallEngine`. All new methods follow the existing `withContext(pjDispatcher)` pattern.

```
PjsipBridge
  ├── (existing) endpoint, account, pollJob, pjDispatcher, _registrationState
  ├── (new) currentCall: PjsipCall?
  ├── (new) _callState: MutableStateFlow<CallState>(Idle)
  │
  ├── makeCall(number) → withContext(pjDispatcher)
  │     Create PjsipCall → call.makeCall("sip:$number@$server", CallOpParam(true))
  │     currentCall = call, state = Active(outbound)
  │
  ├── answerCall() → withContext(pjDispatcher)
  │     currentCall?.answer(CallOpParam(statusCode=200))
  │     state = Active(inbound)
  │
  ├── hangupCall() → withContext(pjDispatcher)
  │     state = Ending
  │     currentCall?.hangup(CallOpParam())
  │
  ├── toggleMute() → withContext(pjDispatcher)
  │     AudDevManager.getCaptureDevMedia().adjustRxLevel(0 or 1)
  │     state = Active(isMuted = !current)
  │
  ├── toggleHold() → withContext(pjDispatcher)
  │     call.setHold() or call.reinvite()
  │     state = Active(isOnHold = !current)
  │
  └── destroy() updated: hangupCall() before account.shutdown()
```

### PjsipCall (New Class)

Extends `pjsua2.Call`. Handles call lifecycle callbacks.

```
PjsipCall(bridge: PjsipBridge) : Call()
  │
  ├── onCallState(prm)
  │     info = getInfo()
  │     when (info.state):
  │       CONFIRMED → bridge.updateCallState(Active)
  │       DISCONNECTED → bridge.updateCallState(Idle), bridge.clearCurrentCall(), this.delete()
  │     info.delete()  // SWIG cleanup
  │
  └── onCallMediaState(prm)
        Connect audio: call.getAudioMedia(0) → AudDevManager playback
        Standard pjsua2 audio routing
```

**SWIG/JNI rules (from Phase 1 learnings):**
- `PjsipCall` stored as field reference — prevent GC collection
- `call.delete()` only in DISCONNECTED state — no pending transactions
- `CallInfo.delete()` after every `getInfo()` call
- All callbacks wrapped in try-catch — uncaught JNI exception = native crash
- `isDestroyed()` guard in callbacks — prevent processing during shutdown

### PjsipAccount Changes

Add `onIncomingCall` override:

```
onIncomingCall(prm: OnIncomingCallParam)
    if (bridge.isDestroyed()) return
    val call = PjsipCall(bridge)
    call.init(account, prm.callId)  // attach to incoming SIP dialog
    val info = call.getInfo()
    val (name, number) = parseRemoteUri(info.remoteUri)
    bridge.setCurrentCall(call)
    bridge.updateCallState(Ringing(number, name))
    info.delete()
```

### remoteUri Parser

Parse `"Display Name" <sip:user@host>` format:

```kotlin
fun parseRemoteUri(uri: String): Pair<String?, String> {
    // Pattern 1: "Name" <sip:number@host>
    // Pattern 2: <sip:number@host>
    // Returns: (displayName?, number)
}
```

Tested with: display name present, absent, special characters, empty string.

## DialerComponent

```kotlin
class DialerComponent(
    componentContext: ComponentContext,
    private val registrationEngine: RegistrationEngine,
    private val callEngine: CallEngine,
    private val onDisconnected: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComponentContext by componentContext {

    val registrationState = registrationEngine.registrationState
    val callState = callEngine.callState
    val callDuration: StateFlow<Long>  // seconds since Active

    // Actions — delegation to CallEngine
    fun makeCall(number: String)
    fun answerCall()
    fun hangupCall()
    fun toggleMute()
    fun toggleHold()

    // Registration drop policy:
    // Navigate back ONLY when (Idle or Failed) AND no active call
    // If active call exists, wait until call ends, then check registration
}
```

**Call timer:** Component launches coroutine on `Active` state that increments every second. Resets to 0 on `Idle`. UI formats as `mm:ss`.

## DialerScreen UI

### Layout: Compact Horizontal Panel

Desktop-optimized single-row toolbar layout. Wide screen — use horizontal space, not vertical.

**Structure:** Left pane = info (number, name, status+timer). Right pane = action buttons in a horizontal row. Each state ~60px tall.

**Text Hierarchy (highest to lowest priority/size):**
1. Phone number — 18px bold. Operator looks up number in CRM first.
2. Caller name — 13px regular. Secondary, may not exist for external calls.
3. Status label — 10px caps badge (READY / INCOMING / ACTIVE / ON HOLD). Color-coded dot.
4. Timer — 13px monospace, inline with status label. Not a hero element.

### UX Principles

Designed for call center operators working long shifts under pressure:

1. **Hidden > Disabled** — each state shows ONLY relevant controls. Max 3 buttons visible at once (Hick's Law).
2. **Warm off-white base** — reduces eye strain for 8+ hour shifts.
3. **Amber for ringing signal** — not red. Red raises heart rate (Yerkes-Dodson Law).
4. **End Call separated** — vertical divider before End button. Prevents accidental hangup (Fitts's Law).
5. **End Call outlined, not filled red** — deliberate action, not impulse.
6. **Icon + label on every button** — icon for fast pattern recognition, label for certainty.
7. **Muted state visual** — Unmute button gets tinted background to show active mute.
8. **Hold timer greyed** — visual "paused" cue.

### Four UI States

**Idle:** Single row — green dot + "READY" label | phone input field | Call button (icon + label) | Disconnect link

**Ringing:** Single row with amber left border — "INCOMING CALL" label, phone number (bold), name | Answer button (green, icon + label + Space hint) + Reject button (outlined, X icon + label)

**Active Call:** Single row — green dot + "ACTIVE" + timer (inline) | phone number (bold), name | Mute + Hold buttons (outlined, icon + label) | vertical divider | End button (outlined red, icon + label)

**On Hold:** Same as Active but — amber dot + "ON HOLD" + timer (greyed) | Mute + Resume (filled primary, play icon) | divider | End

### Space Hotkey

- Active ONLY when `callState` is `Ringing`
- Handled in DialerScreen via `onKeyEvent`
- Ignored when focus is in text field (phone number input)
- Maps to `component.answerCall()`

## Koin DI Changes

```kotlin
val appModule = module {
    single { PjsipBridge() } bind RegistrationEngine::class bind CallEngine::class
    singleOf(::AppSettings)
}
```

Single `PjsipBridge` instance bound to both interfaces via multi-binding. Using `single { }` with chained `bind` ensures one instance, not two.

## RootComponent Changes

Minimal — update `dialerFactory` to inject both `RegistrationEngine` and `CallEngine`:

```kotlin
private val dialerFactory: (ComponentContext, onDisconnected: () -> Unit) -> DialerComponent
```

Factory in `Main.kt` passes both from Koin.

## Testing

### New Test Doubles

- **FakeCallEngine** — implements `CallEngine`, in-memory state management
  - `simulateRinging(number, name)` → sets callState to Ringing
  - `simulateActive()` → sets callState to Active
  - `simulateIdle()` → sets callState to Idle
  - Tracks last call number, answer/hangup/mute/hold call counts

### New Test Files

- **FakeCallEngineTest** — validates the test double
- **DialerComponentTest** (rewrite) — tests with FakeCallEngine + FakeRegistrationEngine:
  - `makeCall` delegates to engine
  - `answerCall` delegates to engine
  - `hangupCall` delegates to engine
  - `toggleMute` / `toggleHold` delegate to engine
  - Call timer: starts on Active, resets on Idle
  - Registration drop + no active call → navigate back
  - Registration drop + active call → DON'T navigate back
  - Registration drop + active call ends → THEN navigate back
- **RemoteUriParserTest** — regex edge cases:
  - With display name: `"Alex" <sip:102@host>` → ("Alex", "102")
  - Without display name: `<sip:+998901234567@host>` → (null, "+998901234567")
  - Empty/malformed URI → graceful fallback

### Existing Tests

- **FakeSipEngine** → rename to **FakeRegistrationEngine**, update to implement `RegistrationEngine`
- **RegistrationComponentTest** — update import, no logic changes
- Other tests unchanged

## Error Handling

- `makeCall` returns `Result<Unit>` — fails if not registered or invalid number
- `answerCall` / `hangupCall` — no-op if wrong state (defensive, no crash)
- pjsip callback exceptions — try-catch, log error, reset to Idle
- Network drop mid-call — pjsip sends DISCONNECTED callback, auto-reset to Idle
- `PjsipCall.delete()` — only in DISCONNECTED state (pending transaction crash prevention)
- `destroy()` sequence: `hangupCall()` → `account.shutdown()` → cleanup (no `libDestroy`)

## Out of Scope

- DTMF sending
- Call transfer
- Auto-answer
- Dark theme
- Call history / logging
- Audio device selection
- System tray / background operation
- Conference calling
- Ringtone / sound notifications
