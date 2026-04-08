# Architecture Refactor — Professional Grade

**Date**: 2026-04-04
**Scope**: Full codebase refactor for scalable call center client
**Target**: Oktell, Asterisk, FreeSWITCH — DTMF, transfer, conference, recording, settings, call history

---

## 1. Domain Interfaces

### Problem
`init()`/`destroy()` live on `RegistrationEngine` — wrong concern. Adding DTMF/transfer requires modifying `CallEngine` (OCP violation).

### Design

```kotlin
// Lifecycle — application-scoped, Main.kt only
interface SipStackLifecycle {
    suspend fun initialize(): Result<Unit>
    suspend fun shutdown()
}

// Registration — clean, no lifecycle
interface RegistrationEngine {
    val registrationState: StateFlow<RegistrationState>
    suspend fun register(credentials: SipCredentials): Result<Unit>
    suspend fun unregister()
}

// Call control — core ops only
interface CallEngine {
    val callState: StateFlow<CallState>
    suspend fun makeCall(number: String): Result<Unit>
    suspend fun answerCall()
    suspend fun hangupCall()
    suspend fun toggleMute()
    suspend fun toggleHold()
}
```

Future engines added without modifying existing interfaces:
- `DtmfEngine` — `sendDtmf(digit: Char)`
- `CallTransferEngine` — `blindTransfer()`, `attendedTransfer()`
- `ConferenceEngine` — `mergeIntoConference()`, state flow
- `CallRecordingEngine` — start/stop/pause, state flow
- `AudioDeviceEngine` — device enumeration, selection

Each gets its own file, own pjsip implementation, own DI binding. Zero changes to existing code.

---

## 2. PjsipBridge Decomposition

### Problem
400-line god class with 9 responsibilities. Untestable, unscalable.

### Design

```
data/pjsip/
├── PjsipEngine.kt          — Thin facade (80 lines)
├── PjsipEndpointManager.kt — Endpoint lifecycle, transport, polling (90 lines)
├── PjsipAccountManager.kt  — Account lifecycle, registration (120 lines)
├── PjsipCallManager.kt     — Call lifecycle, audio routing (160 lines)
├── PjsipAccount.kt         — SWIG Account wrapper (45 lines)
├── PjsipCall.kt            — SWIG Call wrapper (40 lines)
├── PjsipLogWriter.kt       — Log bridge (25 lines, unchanged)
└── NativeLibraryLoader.kt  — OS-specific native lib loading (30 lines)
```

### PjsipEngine — Facade

```kotlin
class PjsipEngine : SipStackLifecycle, RegistrationEngine, CallEngine {
    private val destroyed = AtomicBoolean(false)
    private val pjDispatcher = newSingleThreadContext("pjsip-event-loop")
    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)

    private val endpointManager = PjsipEndpointManager(pjDispatcher)
    private val accountManager = PjsipAccountManager(endpointManager, pjDispatcher, ::isDestroyed)
    private val callManager = PjsipCallManager(...)

    init { accountManager.setIncomingCallListener(callManager) }

    // Every method: withContext(pjDispatcher) { manager.method() }
    // Zero business logic — pure delegation
}
```

### Inter-Component Communication

```
AccountManager ──[IncomingCallListener]──▶ CallManager
CallManager ──[AccountProvider]──▶ AccountManager (read-only: currentAccount, lastServer)
CallManager ──[AudioMediaProvider]──▶ EndpointManager (playback/capture media)
```

No circular dependencies. Each manager talks through interfaces.

### SWIG delete() Ownership

| Object | Owner | When deleted |
|--------|-------|-------------|
| Endpoint | EndpointManager | Process exit (not explicit) |
| EpConfig, TransportConfig | Creator method | finally block, same method |
| PjsipAccount | AccountManager | re-register, unregister, destroy |
| PjsipCall | CallManager | onCallDisconnected, destroy |
| CallOpParam, CallInfo, AccountInfo | Caller | finally block, same scope |

Rule: transient objects → finally in same scope. Long-lived objects → owning manager on lifecycle transitions.

### Destroy Ordering

```kotlin
override suspend fun shutdown() {
    callManager.destroy()       // 1. Hangup + delete call
    accountManager.destroy()    // 2. Unregister + delete account (event loop still running!)
    endpointManager.destroy()   // 3. Stop polling
}
```

### Eliminated Redundant State

| Before | After |
|--------|-------|
| `isOutboundCall: Boolean` | Derived from `CallState.Ringing.isOutbound` |
| `lastRegisteredServer: String?` | Owned by AccountManager, exposed read-only via AccountProvider |

---

## 3. Constants & Tokens

### SipConstants

```kotlin
// domain/SipConstants.kt
object SipConstants {
    const val DEFAULT_PORT = 5060
    const val USER_AGENT = "YallaSipPhone/1.0"
    const val AUTH_SCHEME_DIGEST = "digest"
    const val AUTH_REALM_ANY = "*"
    const val AUTH_DATA_TYPE_PLAINTEXT = 0
    const val STATUS_OK = 200
    const val STATUS_BUSY_HERE = 486
    const val STATUS_CLASS_SUCCESS = 2
    const val POLL_INTERVAL_MS = 50
    const val AUDIO_LEVEL_UNMUTED = 1.0f
    const val AUDIO_LEVEL_MUTED = 0.0f

    object Timeout {
        const val UNREGISTER_BEFORE_REREGISTER_MS = 3000L
        const val UNREGISTER_MS = 5000L
        const val DESTROY_MS = 3000L
    }

    object NativeLib {
        const val MAC = "libpjsua2.jnilib"
        const val WINDOWS = "pjsua2.dll"
        const val LINUX = "libpjsua2.so"
        const val FALLBACK = "pjsua2"
    }

    fun buildUserUri(user: String, server: String) = "sip:$user@$server"
    fun buildRegistrarUri(server: String, port: Int) = "sip:$server:$port"
    fun buildCallUri(number: String, host: String) = "sip:$number@$host"
}
```

### AppTokens — Complete

```kotlin
data class AppTokens(
    // Spacing
    val spacingXs: Dp = 4.dp,
    val spacingSm: Dp = 8.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val spacingXl: Dp = 32.dp,
    // Corners + Shapes
    val cornerSmall: Dp = 8.dp,
    val cornerMedium: Dp = 12.dp,
    val shapeSmall: Shape = RoundedCornerShape(8.dp),
    val shapeMedium: Shape = RoundedCornerShape(12.dp),
    // Windows
    val registrationWindowSize: DpSize = DpSize(420.dp, 520.dp),
    val dialerWindowSize: DpSize = DpSize(800.dp, 180.dp),
    val windowMinWidth: Dp = 380.dp,
    val windowMinHeight: Dp = 180.dp,
    // Icons
    val iconSmall: Dp = 16.dp,
    val iconMedium: Dp = 24.dp,
    // Indicators
    val indicatorDot: Dp = 8.dp,
    val dividerThickness: Dp = 1.dp,
    val dividerHeight: Dp = 32.dp,
    // Progress
    val progressSmall: Dp = 18.dp,
    val progressStrokeSmall: Dp = 2.dp,
    // Alpha
    val alphaDisabled: Float = 0.6f,
    val alphaHint: Float = 0.7f,
    // Animation
    val animFast: Int = 200,
    val animMedium: Int = 300,
    val animSlow: Int = 350,
) {
    fun minimumAwtDimension() = java.awt.Dimension(windowMinWidth.value.toInt(), windowMinHeight.value.toInt())
}
```

~85 hard-coded values across 10 files replaced by SipConstants + AppTokens references.

---

## 4. UI Layer

### DialerScreen.kt (467 lines)

**Keep as single file** — composables are tightly coupled call-state variants. Splitting would scatter related UI. Extract only when adding transfer/DTMF UI.

**Changes:**
- Remove `tokens: AppTokens` parameter from all sub-composables — use `LocalAppTokens.current` (CompositionLocal exists, use it)
- Replace all `RoundedCornerShape(8.dp)` → `tokens.shapeSmall` (12 instances)
- Replace all raw dp values → token references (~40 instances)
- Delete 13 noise comments (`// Status bar`, `// Phone input`, etc.)
- Keep valuable comments (architecture decisions, non-obvious logic)
- Extract `formatDuration()` → `util/TimeFormat.kt`

### Navigation — ComponentFactory for scaling

```kotlin
interface ComponentFactory {
    fun createRegistration(ctx: ComponentContext, onRegistered: () -> Unit): RegistrationComponent
    fun createDialer(ctx: ComponentContext, onDisconnected: () -> Unit): DialerComponent
    // Future: createSettings, createHistory, createContacts...
}

class RootComponent(
    componentContext: ComponentContext,
    private val factory: ComponentFactory,  // single param, scales forever
) : ComponentContext by componentContext
```

RootComponent constructor stays 2 params regardless of screen count.

---

## 5. DI Architecture

### Module Split

```kotlin
// di/SipModule.kt
val sipModule = module {
    single { PjsipEngine() }
    single<SipStackLifecycle> { get<PjsipEngine>() }
    single<RegistrationEngine> { get<PjsipEngine>() }
    single<CallEngine> { get<PjsipEngine>() }
}

// di/SettingsModule.kt
val settingsModule = module {
    singleOf(::AppSettings)
}

// di/FeatureModule.kt
val featureModule = module {
    single<ComponentFactory> { ComponentFactoryImpl(getKoin()) }
}

// di/AppModule.kt
val appModules = listOf(sipModule, settingsModule, featureModule)
```

### Main.kt Changes

```kotlin
fun main() {
    val koin = startKoin { modules(appModules) }.koin
    val lifecycle: SipStackLifecycle = koin.get()
    val initResult = runBlocking { lifecycle.initialize() }
    // ... explicit lifecycle, not registrationEngine.init()
}
```

---

## 6. Error Model

### Problem
Errors are raw strings. Can't distinguish auth failure from network error programmatically.

### Design

```kotlin
sealed interface SipError {
    data class AuthFailed(val code: Int, val reason: String) : SipError
    data class NetworkError(val cause: Throwable) : SipError
    data class ServerError(val code: Int, val reason: String) : SipError
    data class InternalError(val cause: Throwable) : SipError
}

// RegistrationState.Failed uses typed error
data class Failed(val error: SipError) : RegistrationState
```

PjsipAccountManager maps SIP status codes to typed errors:
- 401/403 → `AuthFailed`
- 408/503/504 → `NetworkError`  
- 5xx → `ServerError`
- exceptions → `InternalError`

---

## 7. Naming & Package Fixes

| Before | After | Reason |
|--------|-------|--------|
| `extractHost()` | `SipConstants.extractHostFromUri()` | Descriptive, correct location |
| `clearCurrentCall()` | `resetCallState()` | Clearer intent |
| `RemoteUriParser.kt` | `CallerInfo.kt` | Primary type = file name |
| `RegistrationModel.kt` | `FormState.kt` + extract `validateForm` | SRP |
| `isOutboundCall` field | Eliminated | Derived from CallState |
| `lastRegisteredServer` field | AccountManager property | Single owner |

### New package

```
util/
└── TimeFormat.kt  — formatDuration() and future formatters
```

---

## 8. Testing Gaps to Fill

| Test | Priority | What it covers |
|------|----------|---------------|
| `RootComponentTest` | High | Navigation Registration→Dialer→back |
| `RegistrationComponent` double-register guard | High | 403 flood prevention |
| `DialerComponent` makeCall error handling | High | Result.failure propagation |
| `FakeCallEngine` configurable failure | Medium | makeCallResult parameter |
| `KoinModuleCheckTest` | Medium | DI graph resolves cleanly |
| `AppSettings` behind interface | Medium | Testable without real prefs |

---

## 9. What Does NOT Change

- `CallState` sealed interface (clean)
- `RegistrationState` sealed interface (structure stays, `Failed.message` → `Failed.error`)
- `SipCredentials` data class
- `Screen` sealed interface
- `PjsipLogWriter`
- `ConnectButton`, `ConnectionStatusCard`, `SipCredentialsForm` (token usage updated)
- `Theme.kt` (unchanged)

---

## 10. File Impact Summary

| Action | Files |
|--------|-------|
| **New** | SipStackLifecycle.kt, PjsipEngine.kt, PjsipEndpointManager.kt, PjsipAccountManager.kt, PjsipCallManager.kt, NativeLibraryLoader.kt, SipConstants.kt, ComponentFactory.kt, ComponentFactoryImpl.kt, SipError.kt, TimeFormat.kt, SipModule.kt, SettingsModule.kt, FeatureModule.kt |
| **Rewrite** | PjsipBridge.kt → deleted (replaced by PjsipEngine), AppModule.kt → split into 3 |
| **Modify** | PjsipAccount.kt, PjsipCall.kt, Main.kt, RootComponent.kt, RegistrationEngine.kt, CallEngine.kt, RegistrationState.kt, DialerScreen.kt, DialerComponent.kt, RegistrationComponent.kt, RegistrationScreen.kt, RootContent.kt, AppTokens.kt, SipCredentialsForm.kt, ConnectButton.kt, ConnectionStatusCard.kt |
| **Rename** | RemoteUriParser.kt → CallerInfo.kt, RegistrationModel.kt → FormState.kt |
| **Delete** | PjsipBridge.kt, AppModule.kt (replaced) |
| **Unchanged** | Theme.kt, PjsipLogWriter.kt, Screen.kt |

**14 new files, 16 modified, 2 renamed, 2 deleted.** Net: ~2400 lines (from ~2030), but each file is focused, testable, independently comprehensible.

---

## 11. Enterprise Gap Analysis

23 enterprise capabilities are COMPLETELY MISSING. Architecture must lay foundation for all of them. Phased roadmap below.

### Current State: 0/23

| # | Capability | Status | Impact |
|---|-----------|--------|--------|
| 1 | Auto-reconnect / call recovery | MISSING | Operators lose calls on network blip |
| 2 | TLS transport (SIPS) | MISSING | Signaling sent plaintext |
| 3 | SRTP media encryption | MISSING | Voice calls unencrypted |
| 4 | Secure credential storage | MISSING | Username in plaintext plist |
| 5 | Call quality metrics (MOS/jitter/loss) | MISSING | Can't detect bad calls |
| 6 | Telemetry / observability | MISSING | Ops team blind |
| 7 | Retry with exponential backoff | MISSING | Only 1s flat rate limit |
| 8 | Crash recovery / watchdog | MISSING | Crash = manual restart |
| 9 | System tray + background operation | MISSING | Must keep window open |
| 10 | Desktop notifications (incoming call) | MISSING | Missed calls when alt-tabbed |
| 11 | Global hotkeys | MISSING | Space only works focused |
| 12 | Audio device selection UI | MISSING | Stuck on default device |
| 13 | Echo cancellation / noise config | MISSING | pjsip defaults only |
| 14 | Codec priority configuration | MISSING | No codec control |
| 15 | Multi-server failover | MISSING | Single server, single point of failure |
| 16 | DNS SRV support | MISSING | Only IP:port |
| 17 | i18n / l10n | MISSING | English only, hardcoded strings |
| 18 | Auto-update mechanism | MISSING | Manual reinstall |
| 19 | Remote configuration | MISSING | All config local |
| 20 | Always-on-top option | MISSING | Window buried under other apps |
| 21 | Uncaught exception handler | MISSING | Silent crashes |
| 22 | Memory scrubbing for credentials | MISSING | Password stays in heap |
| 23 | DTMF / Transfer / Conference | MISSING | Basic call only |

---

## 12. Architecture Foundation for Enterprise (Phase 3 scope)

Phase 3 (this refactor) MUST create extension points so Phase 4+ can add enterprise features without re-architecting. Specific foundations:

### 12.1 Resilience Interface

```kotlin
// domain/ConnectionManager.kt
interface ConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(credentials: SipCredentials)
    suspend fun disconnect()
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val server: String) : ConnectionState
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState
    data class Failed(val error: SipError, val willRetry: Boolean) : ConnectionState
}
```

Phase 3: Define interface + implement simple version (manual connect only).
Phase 4: Implement auto-reconnect with exponential backoff (1s → 2s → 4s → 8s → 30s cap).

### 12.2 Transport Security Interface

```kotlin
// domain/TransportConfig.kt
data class TransportPreference(
    val protocol: TransportProtocol = TransportProtocol.UDP,
    val srtpPolicy: SrtpPolicy = SrtpPolicy.DISABLED,
)

enum class TransportProtocol { UDP, TCP, TLS }
enum class SrtpPolicy { DISABLED, OPTIONAL, MANDATORY }
```

Phase 3: Data model defined. PjsipEndpointManager creates all 3 transports (UDP+TCP+TLS). Registration uses user-selected protocol.
Phase 4: TLS certificate management, SRTP enforcement.

### 12.3 Call Quality Monitor Interface

```kotlin
// domain/CallQualityMonitor.kt
interface CallQualityMonitor {
    val qualityStats: StateFlow<CallQualityStats?>
}

data class CallQualityStats(
    val codec: String,
    val rxJitterMs: Float,
    val txJitterMs: Float,
    val rxPacketLoss: Float,
    val txPacketLoss: Float,
    val rttMs: Float,
    val mosScore: Float,    // 1.0-5.0
    val durationSeconds: Long,
)
```

Phase 3: Interface defined. PjsipCallManager polls `call.getStreamStat()` every 5s during active call.
Phase 4: MOS calculation, quality alerts, telemetry export.

### 12.4 Audio Configuration Interface

```kotlin
// domain/AudioConfig.kt
interface AudioConfigEngine {
    val audioDevices: StateFlow<AudioDeviceList>
    val currentConfig: StateFlow<AudioSettings>
    suspend fun selectInputDevice(deviceId: Int)
    suspend fun selectOutputDevice(deviceId: Int)
    suspend fun setEchoCancellation(enabled: Boolean, tailLengthMs: Int = 200)
    suspend fun setNoiseSuppression(enabled: Boolean)
    suspend fun setCodecPriority(codec: String, priority: Int)
}

data class AudioDeviceList(
    val inputDevices: List<AudioDevice>,
    val outputDevices: List<AudioDevice>,
    val selectedInput: Int,
    val selectedOutput: Int,
)
```

Phase 3: Interface defined. PjsipEndpointManager exposes device list. Basic implementation.
Phase 4: Settings UI, echo cancellation tuning, codec priority.

### 12.5 Desktop Integration Interface

```kotlin
// domain/DesktopIntegration.kt
interface DesktopIntegration {
    fun showNotification(title: String, message: String)
    fun setTrayIcon(state: TrayState)
    fun registerGlobalHotkey(key: KeyCombination, action: () -> Unit)
    fun setAlwaysOnTop(enabled: Boolean)
}

enum class TrayState { IDLE, REGISTERED, IN_CALL, INCOMING_CALL }
```

Phase 3: Interface defined. Stub implementation.
Phase 4: macOS native notification, system tray, global hotkey via JNA.
Phase 5: Windows native notification, platform-specific integration.

### 12.6 Crash Recovery

```kotlin
// In Main.kt during Phase 3:
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    logger.error(throwable) { "Uncaught exception on ${thread.name}" }
    // Write crash log to file
    // Attempt graceful SIP cleanup
    // Show error dialog
}
```

Phase 3: Add handler + crash log file.
Phase 4: Auto-restart, crash report upload.

### 12.7 i18n Foundation

Phase 3: Extract all hardcoded strings to a `Strings` object:
```kotlin
// ui/strings/Strings.kt
object Strings {
    const val REGISTRATION_TITLE = "SIP Registration"
    const val BUTTON_CONNECT = "Connect"
    const val BUTTON_DISCONNECT = "Disconnect"
    const val CALL_INCOMING = "INCOMING CALL"
    const val CALL_ACTIVE = "ACTIVE"
    const val CALL_ON_HOLD = "ON HOLD"
    const val CALL_CALLING = "CALLING\u2026"
    // ... all strings
}
```

Phase 4: Replace with `stringResource()` / translation system.

---

## 13. Phased Enterprise Roadmap

### Phase 3: Architecture Foundation (THIS REFACTOR)
**Goal**: Clean code, extension points, zero enterprise debt in architecture.

| Item | Description |
|------|------------|
| PjsipBridge decomposition | 4 focused classes |
| Interface segregation | SipStackLifecycle + RegistrationEngine + CallEngine |
| SipConstants + AppTokens | All magic values extracted |
| ComponentFactory | Scalable navigation |
| SipError typed model | Actionable error handling |
| ConnectionManager interface | Foundation for auto-reconnect |
| AudioConfigEngine interface | Foundation for device selection |
| CallQualityMonitor interface | Foundation for MOS/jitter |
| DesktopIntegration interface | Foundation for tray/notifications |
| TransportPreference model | Foundation for TLS/SRTP |
| Strings extraction | Foundation for i18n |
| Crash handler | Uncaught exception → log + cleanup |
| Stream stats polling | `getStreamStat()` every 5s |
| All 3 transports | UDP + TCP + TLS created |
| Proper destroy ordering | Unregister → wait → stop poll |
| Shutdown hook | Ctrl+C cleanup |
| Test gaps filled | RootComponent, error paths, DI check |

### Phase 4: Enterprise Reliability
**Goal**: Production-ready for 100+ operators.

| Item | Description |
|------|------------|
| Auto-reconnect | Exponential backoff (1s→30s cap) |
| SRTP | Optional/mandatory media encryption |
| TLS | Certificate management, SIPS URIs |
| Secure credentials | macOS Keychain, Windows Credential Manager |
| Call quality alerts | MOS < 3.0 → warn operator |
| Telemetry export | Stats → server (Prometheus/OpenTelemetry) |
| Audio device UI | Settings screen with device picker |
| Echo cancellation | Configurable EC, noise suppression |
| System tray | Background operation, incoming call badge |
| Desktop notifications | Native macOS/Windows notifications |
| Global hotkeys | JNA-based system-wide shortcuts |
| Always-on-top | Operator window pinning |
| Crash recovery | Auto-restart, crash report upload |

### Phase 5: Call Center Features
**Goal**: Full operator workflow.

| Item | Description |
|------|------------|
| DTMF | Tone pad during active call |
| Blind transfer | One-click transfer to extension |
| Attended transfer | Consultation + connect |
| Conference (3-way) | Merge calls |
| Call recording | Start/stop/pause |
| Call history | Persistent log with search |
| Multi-server failover | Primary/backup server config |
| DNS SRV | Automatic server discovery |
| i18n | Uzbek, Russian, English |
| Auto-update | Sparkle (macOS), WinSparkle (Windows) |
| Remote config | Server-pushed settings |
| CRM integration | Screen pop, disposition codes |
| Queue monitoring | Real-time queue stats |
| Agent status | Available/Away/Break/Wrap-up |

---

## 14. Architecture Principles

For a system used by thousands of operators:

1. **Crash = operator can't answer calls = lost revenue.** Every code path must have error recovery. No silent failures. No swallowed exceptions without fallback.

2. **Audio quality = operator productivity.** Bad audio → operator asks "can you repeat?" → call duration increases → revenue impact. Monitor MOS, alert on degradation.

3. **One-click everything.** Operators handle 50-100 calls/day under pressure. Every extra click costs seconds × 100 calls × 1000 operators = millions of wasted seconds.

4. **Defensive architecture.** Every interface has a timeout. Every network call has a retry. Every state machine has explicit error transitions. pjsip is C++ — any native crash must be caught and recovered from.

5. **Observable by default.** If you can't measure it, you can't improve it. Call quality, registration uptime, audio device failures, codec distribution — all measurable from day one.
