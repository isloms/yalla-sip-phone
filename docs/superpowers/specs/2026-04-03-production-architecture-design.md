# Production Architecture - Phase 1 Design Spec

## Goal

Migrate Yalla SIP Phone from PoC (raw UDP SIP) to production architecture: Decompose navigation, Koin DI, pjsua2/JNI for real VoIP, MaterialKolor design system. After Phase 1, the app registers with Oktell using pjsip and is architecturally ready for calling features.

## Success Criteria

- App launches with Decompose-managed navigation (Registration -> Dialer)
- pjsip handles SIP REGISTER via pjsua2 JNI (not raw UDP)
- Koin provides all dependencies via `SipEngine` interface (not concrete PjsipBridge)
- MaterialKolor generates dynamic color scheme from seed color
- Registration screen works identically to PoC (same Oktell server)
- Old raw SIP layer (SipClient, SipTransport, SipMessage, DigestAuth) fully removed
- Navigation to placeholder Dialer screen on successful registration
- Disconnect from Dialer navigates back to Registration
- All pjsip JNI interactions wrapped in error handling
- SWIG objects explicitly `delete()`d after use (no native memory leaks)

## Non-Goals (Phase 2+)

- Audio/media (RTP), making/receiving calls
- Dial pad UI
- Incoming call overlay (Child Slot reserved but not populated)
- Call history, Settings screen, Dark theme, System tray
- Haze glassmorphism, Compottie/Lottie (Phase 3)
- App packaging/distribution (Conveyor)
- Re-registration timer (pjsip handles internally)

## Test Environment

- Server: `192.168.0.22`, Port: `5060`, Transport: `UDP`
- User: `102`, Password: `1234qwerQQ`
- Platform: Oktell PBX (same LAN - 192.168.0.x subnet)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│  Entry Point                                            │
│  Main.kt: Koin → Decompose lifecycle → pjsip init → UI │
├─────────────────────────────────────────────────────────┤
│  Navigation (Decompose 3.4.0)                           │
│  RootComponent (factories injected via Koin)            │
│  ├── Child Stack: Registration ↔ Dialer                 │
│  └── Child Slot: (reserved for incoming call)           │
├─────────────────────────────────────────────────────────┤
│  Features                                               │
│  ├── registration/                                      │
│  │   ├── RegistrationComponent (→ SipEngine interface)  │
│  │   ├── RegistrationScreen.kt (Compose UI)             │
│  │   └── RegistrationModel.kt (FormState, validation)   │
│  └── dialer/                                            │
│      ├── DialerComponent (→ SipEngine interface)        │
│      └── DialerScreen.kt (three-zone layout skeleton)   │
├─────────────────────────────────────────────────────────┤
│  Domain (interfaces + models — NO concrete deps)        │
│  ├── SipEngine (interface: register, unregister, state) │
│  ├── RegistrationState (sealed interface)               │
│  ├── CallState (sealed interface, Phase 3)              │
│  ├── SipCredentials                                     │
│  └── SipEvent (sealed interface)                        │
├─────────────────────────────────────────────────────────┤
│  Data / pjsip                                           │
│  ├── PjsipBridge (implements SipEngine)                 │
│  ├── PjsipAccount (extends Account, callbacks)          │
│  ├── PjsipLogWriter (routes native logs to logback)     │
│  └── AppSettings (credential persistence)               │
├─────────────────────────────────────────────────────────┤
│  UI / Design System                                     │
│  ├── Theme.kt (MaterialKolor + ExtendedColors + Typo)   │
│  ├── AppTokens.kt (CompositionLocal, spacing/elevation) │
│  └── component/ (form, status card, button)             │
├─────────────────────────────────────────────────────────┤
│  Native                                                 │
│  └── libpjsua2.jnilib (3.7MB, ARM64, static)           │
└─────────────────────────────────────────────────────────┘
```

**Dependency direction:** UI → Feature → Domain ← Data. Features depend on `SipEngine` interface, never on `PjsipBridge` directly.

---

## Dependencies

| Library | Artifact | Version | Purpose |
|---------|----------|---------|---------|
| Compose Desktop | `compose.desktop.currentOs` | 1.7.3 | UI framework (existing) |
| Material 3 | `compose.material3` | bundled | UI components (existing) |
| Material Icons | `compose.materialIconsExtended` | bundled | Icons (existing) |
| Coroutines | `kotlinx-coroutines-core` | 1.10.1 | Async (existing) |
| **Decompose** | `com.arkivanov.decompose:decompose` | 3.4.0 | Navigation + lifecycle |
| **Decompose Compose** | `com.arkivanov.decompose:extensions-compose` | 3.4.0 | Compose integration |
| **Essenty Coroutines** | `com.arkivanov.essenty:lifecycle-coroutines` | 2.5.0 | coroutineScope() in components |
| **Koin Core** | `io.insert-koin:koin-core` | 4.1.1 | Dependency injection |
| **MaterialKolor** | `com.materialkolor:material-kolor` | 2.0.0 | Dynamic M3 color scheme |
| **kotlin-logging** | `io.github.oshai:kotlin-logging-jvm` | 7.0.3 | Structured logging |
| **Logback** | `ch.qos.logback:logback-classic` | 1.5.16 | Logging backend |
| **multiplatform-settings** | `com.russhwolf:multiplatform-settings-no-arg` | 1.3.0 | Credential persistence |
| **pjsua2** | `files("libs/pjsua2.jar")` | local | SIP/VoIP JNI bindings |

**Note:** Verify Koin 4.1.1 exists on Maven Central before implementation. If not, use latest stable.

### pjsua2 Packaging

310 SWIG-generated Java classes pre-compiled into a JAR:

```bash
cd /Users/macbookpro/Ildam/pjproject/pjsip-apps/src/swig/java/output
javac -d classes org/pjsip/pjsua2/*.java
jar cf pjsua2.jar -C classes .
```

```
libs/
├── pjsua2.jar            # 310 Java wrapper classes
└── libpjsua2.jnilib      # Native library (macOS ARM64)
```

JVM arg: `-Djava.library.path=libs`. If `UnsatisfiedLinkError` on JDK 17+, create symlink: `ln -s libpjsua2.jnilib libpjsua2.dylib`.

---

## Project Structure

```
yalla-sip-phone/
├── build.gradle.kts
├── settings.gradle.kts
├── libs/
│   ├── pjsua2.jar
│   └── libpjsua2.jnilib
├── src/main/kotlin/uz/yalla/sipphone/
│   ├── Main.kt                              # Koin + Decompose + Window
│   │
│   ├── di/
│   │   └── AppModule.kt                     # Koin module + component factories
│   │
│   ├── domain/
│   │   ├── SipEngine.kt                     # Interface: register, unregister, state
│   │   ├── RegistrationState.kt             # Sealed interface: Idle, Registering, etc.
│   │   ├── CallState.kt                     # Sealed interface (Phase 3, empty for now)
│   │   ├── SipCredentials.kt                # Kept from PoC
│   │   └── SipEvent.kt                      # Sealed interface: events
│   │
│   ├── data/
│   │   ├── pjsip/
│   │   │   ├── PjsipBridge.kt              # Implements SipEngine
│   │   │   ├── PjsipAccount.kt             # Extends pjsua2.Account with callbacks
│   │   │   └── PjsipLogWriter.kt           # Routes pjsip native logs to logback
│   │   └── settings/
│   │       └── AppSettings.kt              # Last-used credentials persistence
│   │
│   ├── navigation/
│   │   ├── RootComponent.kt                # Child Stack + factories
│   │   ├── RootContent.kt                  # Composable mapping
│   │   └── Screen.kt                       # Sealed interface for nav targets
│   │
│   ├── feature/
│   │   ├── registration/
│   │   │   ├── RegistrationComponent.kt    # Business logic + state
│   │   │   ├── RegistrationScreen.kt       # Compose UI (migrated)
│   │   │   └── RegistrationModel.kt        # FormState, FormErrors, validateForm()
│   │   └── dialer/
│   │       ├── DialerComponent.kt          # Placeholder: shows registered state
│   │       └── DialerScreen.kt             # Three-zone layout skeleton
│   │
│   └── ui/
│       ├── theme/
│       │   ├── Theme.kt                    # MaterialKolor + ExtendedColors + Typography
│       │   └── AppTokens.kt               # CompositionLocal-based design tokens
│       └── component/
│           ├── SipCredentialsForm.kt       # Migrated, a11y fixed
│           ├── ConnectionStatusCard.kt     # Migrated, liveRegion, contentDescription
│           └── ConnectButton.kt            # Migrated, dead AnimatedContent removed
│
├── src/main/resources/
│   └── logback.xml                         # Logging configuration
│
└── src/test/kotlin/uz/yalla/sipphone/
    ├── data/pjsip/
    │   └── PjsipBridgeTest.kt              # Integration test (requires Oktell)
    ├── domain/
    │   └── FakeSipEngine.kt                # Test double for SipEngine
    ├── navigation/
    │   └── RootComponentTest.kt            # Navigation flow test
    └── feature/registration/
        └── RegistrationComponentTest.kt    # Component logic + state transitions
```

**File count:** 23 source files + 4 test files.

---

## 1. Domain Layer (Interfaces + Models)

### SipEngine Interface

The core abstraction. All features depend on this, never on PjsipBridge.

```kotlin
// domain/SipEngine.kt
interface SipEngine {
    val registrationState: StateFlow<RegistrationState>
    val events: SharedFlow<SipEvent>

    suspend fun init(): Result<Unit>
    suspend fun register(credentials: SipCredentials): Result<Unit>
    suspend fun unregister()
    suspend fun destroy()
}
```

### RegistrationState

Split from single PhoneState. Registration and Call states are independent — you can be Registered AND InCall simultaneously (Phase 3).

```kotlin
// domain/RegistrationState.kt
sealed interface RegistrationState {
    data object Idle : RegistrationState
    data object Registering : RegistrationState
    data class Registered(val server: String) : RegistrationState
    data class Failed(val message: String) : RegistrationState
}
```

### CallState (Phase 3, defined now for architecture awareness)

```kotlin
// domain/CallState.kt
sealed interface CallState {
    data object Idle : CallState
    // Phase 3: Dialing, Ringing, Active, Held, Ended
}
```

### SipCredentials (unchanged)

```kotlin
// domain/SipCredentials.kt
data class SipCredentials(
    val server: String,
    val port: Int = 5060,
    val username: String,
    val password: String,
)
```

### SipEvent

```kotlin
// domain/SipEvent.kt
sealed interface SipEvent {
    data class Error(val message: String) : SipEvent
    // Phase 3: IncomingCall, CallEnded, etc.
}
```

---

## 2. Decompose Navigation

### Screen Definitions

```kotlin
// navigation/Screen.kt
import kotlinx.serialization.Serializable

@Serializable
sealed interface Screen {
    @Serializable data object Registration : Screen
    @Serializable data object Dialer : Screen
}
```

### RootComponent (Factory Pattern)

RootComponent receives component factories, not raw dependencies. When Phase 3 adds CallComponent, SettingsComponent — RootComponent does not change.

```kotlin
// navigation/RootComponent.kt
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

    // Reserved for Phase 3: incoming call overlay via childSlot()

    private fun createChild(screen: Screen, context: ComponentContext): Child =
        when (screen) {
            is Screen.Registration -> Child.Registration(
                registrationFactory(context) { navigation.push(Screen.Dialer) }
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

### RootContent

```kotlin
// navigation/RootContent.kt
@Composable
fun RootContent(root: RootComponent) {
    val childStack by root.childStack.subscribeAsState()

    Children(
        stack = childStack,
        animation = stackAnimation { _ ->
            slide(
                orientation = StackAnimator.Orientation.Horizontal,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            ) + fade(tween(250))
        },
    ) { child ->
        when (val instance = child.instance) {
            is RootComponent.Child.Registration ->
                RegistrationScreen(instance.component)
            is RootComponent.Child.Dialer ->
                DialerScreen(instance.component)
        }
    }
}
```

Slide+fade communicates forward/backward navigation progression (not ambient fade+scale).

---

## 3. Koin DI

### Module Definitions with Factories

```kotlin
// di/AppModule.kt
val appModule = module {
    // SipEngine interface → PjsipBridge implementation
    single<SipEngine> { PjsipBridge() }

    // Settings
    single { AppSettings() }

    // Component factories for RootComponent
    factory { params ->
        RootComponent(
            componentContext = params.get(),
            registrationFactory = { ctx, onRegistered ->
                RegistrationComponent(ctx, get(), get(), onRegistered)
            },
            dialerFactory = { ctx, onDisconnected ->
                DialerComponent(ctx, get(), onDisconnected)
            },
        )
    }
}
```

Components receive `SipEngine` (interface), not `PjsipBridge` (concrete). Swapping SIP implementation = change one Koin binding.

---

## 4. PjsipBridge

Implements `SipEngine`. Wraps pjsua2 Java/JNI API with proper error handling, SWIG object lifecycle, and defensive threading.

### Threading Model

```kotlin
private val pjDispatcher = newSingleThreadContext("pjsip-event-loop")
```

- `threadCnt = 0` — no internal pjsip worker threads (JNI/GC compatible)
- `mainThreadOnly = false` — not needed with threadCnt=0, avoids 50ms callback latency
- All pjsua2 calls dispatched to `pjDispatcher` via `withContext`
- Continuous polling via `Endpoint.libHandleEvents(50)` (~20 polls/sec, sufficient for SIP signaling)

### State Management

```kotlin
class PjsipBridge : SipEngine {
    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _events = MutableSharedFlow<SipEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<SipEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)
    private lateinit var endpoint: Endpoint
    private var account: PjsipAccount? = null
    private var pollJob: Job? = null
    private var logWriter: PjsipLogWriter? = null  // must keep reference alive!

    internal fun updateRegistrationState(state: RegistrationState) {
        _registrationState.value = state
    }

    internal fun emitEvent(event: SipEvent) {
        _events.tryEmit(event)
    }
}
```

### Init Flow (with error handling + SWIG cleanup)

```kotlin
override suspend fun init(): Result<Unit> = withContext(pjDispatcher) {
    try {
        // 1. Load native library
        try {
            System.loadLibrary("pjsua2")
        } catch (e: UnsatisfiedLinkError) {
            logger.error(e) { "Failed to load pjsua2 native library" }
            return@withContext Result.failure(e)
        }

        // 2. Create and init endpoint
        endpoint = Endpoint()
        endpoint.libCreate()

        val epConfig = EpConfig()
        epConfig.uaConfig.threadCnt = 0
        epConfig.uaConfig.mainThreadOnly = false  // not needed with threadCnt=0
        epConfig.uaConfig.userAgent = "YallaSipPhone/1.0"

        // Route pjsip native logs to logback
        logWriter = PjsipLogWriter()
        epConfig.logConfig.writer = logWriter
        epConfig.logConfig.level = 4          // debug
        epConfig.logConfig.consoleLevel = 0   // disable console, use writer

        endpoint.libInit(epConfig)
        epConfig.delete()  // SWIG cleanup: pjsip copied config data

        // 3. Create UDP transport
        val transportConfig = TransportConfig()
        transportConfig.port = 0  // auto-assign
        endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig)
        transportConfig.delete()  // SWIG cleanup

        // 4. Start library
        endpoint.libStart()

        // 5. Register polling thread + start polling
        startPolling()

        val version = endpoint.libVersion()
        logger.info { "pjsip initialized, version: ${version.full}" }
        version.delete()  // SWIG cleanup

        Result.success(Unit)
    } catch (e: Exception) {
        logger.error(e) { "pjsip init failed" }
        Result.failure(e)
    }
}
```

### Polling Loop (with thread registration)

```kotlin
private fun startPolling() {
    pollJob = scope.launch(pjDispatcher) {
        // Register this thread with pjsip (defense-in-depth)
        if (!endpoint.libIsThreadRegistered()) {
            endpoint.libRegisterThread("pjsip-poll")
        }
        while (isActive) {
            endpoint.libHandleEvents(50) // 50ms timeout = ~20 polls/sec
        }
    }
}
```

### Register Flow

```kotlin
override suspend fun register(credentials: SipCredentials): Result<Unit> = withContext(pjDispatcher) {
    try {
        _registrationState.value = RegistrationState.Registering

        // Cleanup previous account
        account?.shutdown()
        account = null

        val accountConfig = AccountConfig()
        val sipUri = "sip:${credentials.username}@${credentials.server}"

        accountConfig.idUri = sipUri
        accountConfig.regConfig.registrarUri = "sip:${credentials.server}:${credentials.port}"

        val authCred = AuthCredInfo("digest", "*", credentials.username, 0, credentials.password)
        accountConfig.sipConfig.authCreds.add(authCred)

        // NAT: disabled for LAN deployment
        accountConfig.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
        accountConfig.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED

        // Create account — onRegState callback handles state transitions
        account = PjsipAccount(this@PjsipBridge).apply {
            create(accountConfig, true)
        }

        accountConfig.delete()  // SWIG cleanup
        authCred.delete()       // SWIG cleanup

        Result.success(Unit)
    } catch (e: Exception) {
        logger.error(e) { "Registration failed" }
        _registrationState.value = RegistrationState.Failed("Registration error: ${e.message}")
        Result.failure(e)
    }
}
```

### Unregister Flow (clean unregistration)

```kotlin
override suspend fun unregister() = withContext(pjDispatcher) {
    val acc = account ?: return@withContext
    try {
        // Send REGISTER Expires:0, wait for server confirmation
        acc.setRegistration(false)
        withTimeout(5000) {
            _registrationState.first { it is RegistrationState.Idle }
        }
    } catch (e: TimeoutCancellationException) {
        logger.warn { "Unregistration timed out, forcing shutdown" }
    } catch (e: Exception) {
        logger.error(e) { "Unregister error" }
    } finally {
        acc.shutdown()
        account = null
        _registrationState.value = RegistrationState.Idle
    }
}
```

### Destroy Flow (app exit, with join + cleanup)

```kotlin
override suspend fun destroy() {
    withContext(pjDispatcher) {
        pollJob?.cancel()
        pollJob?.join()  // wait for poll loop to fully exit

        account?.shutdown()
        account = null

        try {
            endpoint.libDestroy()
            endpoint.delete()  // release SWIG pointer
        } catch (e: Exception) {
            logger.error(e) { "Error during pjsip destroy" }
        }

        _registrationState.value = RegistrationState.Idle
    }
    scope.cancel()
    (pjDispatcher as CloseableCoroutineDispatcher).close()
}
```

### PjsipAccount (Callback Bridge, with error handling)

```kotlin
// data/pjsip/PjsipAccount.kt
class PjsipAccount(private val bridge: PjsipBridge) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        try {
            val info = getInfo()
            val code = prm.code

            when {
                code / 100 == 2 && info.regIsActive -> {
                    bridge.updateRegistrationState(
                        RegistrationState.Registered(server = info.uri)
                    )
                    logger.info { "Registered: ${info.uri}, expires: ${info.regExpiresSec}s" }
                }
                code / 100 == 2 && !info.regIsActive -> {
                    // Successful unregistration (REGISTER Expires:0 got 200 OK)
                    bridge.updateRegistrationState(RegistrationState.Idle)
                    logger.info { "Unregistered" }
                }
                else -> {
                    val reason = "${prm.code} ${prm.reason}"
                    bridge.updateRegistrationState(
                        RegistrationState.Failed(message = reason)
                    )
                    logger.warn { "Registration failed: $reason (lastErr=${info.regLastErr})" }
                }
            }

            info.delete()  // SWIG cleanup
        } catch (e: Exception) {
            logger.error(e) { "Error in onRegState callback" }
            bridge.updateRegistrationState(
                RegistrationState.Failed(message = "Internal error: ${e.message}")
            )
        }
    }

    // Phase 3: override onIncomingCall(prm: OnIncomingCallParam)
}
```

### PjsipLogWriter (routes native logs to logback)

```kotlin
// data/pjsip/PjsipLogWriter.kt
class PjsipLogWriter : LogWriter() {
    private val logger = KotlinLogging.logger("pjsip.native")

    override fun write(entry: LogEntry) {
        val msg = entry.msg.trimEnd()
        when (entry.level) {
            0, 1 -> logger.error { msg }
            2 -> logger.warn { msg }
            3 -> logger.info { msg }
            4 -> logger.debug { msg }
            else -> logger.trace { msg }
        }
    }
}
```

IMPORTANT: `PjsipLogWriter` instance must be kept as a field reference in PjsipBridge. If GC'd, native code crashes calling dead Java object.

### JNI Loading Strategy

**Development:** `-Djava.library.path=${projectDir}/libs`
**Distribution (Phase 4):** Conveyor handles native lib bundling.
**Fallback:** If JDK 17+ prefers `.dylib`, create symlink `libpjsua2.dylib → libpjsua2.jnilib`.

---

## 5. Feature Components

### FormState + Validation (moved to feature layer)

```kotlin
// feature/registration/RegistrationModel.kt
data class FormState(
    val server: String = "",
    val port: String = "5060",
    val username: String = "",
    val password: String = "",
)

data class FormErrors(
    val server: String? = null,
    val port: String? = null,
    val username: String? = null,
    val password: String? = null,
)

fun validateForm(form: FormState): FormErrors = FormErrors(
    server = if (form.server.isBlank()) "Server required" else null,
    port = form.port.toIntOrNull()?.let {
        if (it !in 1..65535) "Port must be 1-65535" else null
    } ?: "Invalid port",
    username = if (form.username.isBlank()) "Username required" else null,
    password = if (form.password.isBlank()) "Password required" else null,
)
```

### RegistrationComponent

```kotlin
// feature/registration/RegistrationComponent.kt
class RegistrationComponent(
    componentContext: ComponentContext,
    private val sipEngine: SipEngine,      // interface, not PjsipBridge
    private val appSettings: AppSettings,
    private val onRegistered: () -> Unit,
) : ComponentContext by componentContext {

    private val _formState = MutableStateFlow(FormState())
    val formState: StateFlow<FormState> = _formState.asStateFlow()

    val registrationState: StateFlow<RegistrationState> = sipEngine.registrationState

    // Essenty lifecycle-scoped scope. No args = Main.immediate + lifecycle-managed Job
    private val scope = coroutineScope()

    init {
        // Load last-used credentials
        scope.launch(Dispatchers.IO) {
            appSettings.loadCredentials()?.let { creds ->
                _formState.value = FormState(
                    server = creds.server,
                    port = creds.port.toString(),
                    username = creds.username,
                    password = creds.password,
                )
            }
        }

        // Navigate once on successful registration — .first {} completes after one match
        scope.launch {
            sipEngine.registrationState.first { it is RegistrationState.Registered }
            onRegistered()
        }
    }

    fun onConnect(credentials: SipCredentials) {
        scope.launch {
            withContext(Dispatchers.IO) { appSettings.saveCredentials(credentials) }
            sipEngine.register(credentials)
        }
    }

    fun onCancel() {
        scope.launch { sipEngine.unregister() }
    }

    fun updateFormState(formState: FormState) {
        _formState.value = formState
    }
}
```

Key fixes from review:
- `coroutineScope()` no-arg — lifecycle-managed, Main dispatcher (safe for navigation callbacks)
- `.first {}` instead of `.collect` — fires once, no duplicate navigation push
- `sipEngine` is interface, not concrete class
- `saveCredentials` on `Dispatchers.IO` (java.util.prefs does I/O)

### RegistrationScreen

```kotlin
// feature/registration/RegistrationScreen.kt
@Composable
fun RegistrationScreen(component: RegistrationComponent) {
    val formState by component.formState.collectAsState()
    val registrationState by component.registrationState.collectAsState()

    // Same layout as PoC MainScreen:
    // SipCredentialsForm + ConnectionStatusCard + ConnectButton
    // Form disabled during Registering, animations, etc.
    // Wrapped in VerticalScroll for small window overflow
}
```

### DialerComponent (Placeholder)

```kotlin
// feature/dialer/DialerComponent.kt
class DialerComponent(
    componentContext: ComponentContext,
    private val sipEngine: SipEngine,
    private val onDisconnected: () -> Unit,
) : ComponentContext by componentContext {

    val registrationState: StateFlow<RegistrationState> = sipEngine.registrationState

    private val scope = coroutineScope()

    init {
        // Navigate back once on disconnect — .first {} fires once
        scope.launch {
            sipEngine.registrationState
                .drop(1)  // skip current value (Registered)
                .first { it is RegistrationState.Idle }
            onDisconnected()
        }
    }

    fun onDisconnect() {
        scope.launch { sipEngine.unregister() }
    }
}
```

### DialerScreen (Three-Zone Layout Skeleton)

```kotlin
// feature/dialer/DialerScreen.kt
@Composable
fun DialerScreen(component: DialerComponent) {
    val tokens = LocalAppTokens.current

    when (val state = component.registrationState.collectAsState().value) {
        is RegistrationState.Registered -> {
            Column(Modifier.fillMaxSize()) {
                // TOP: Status bar
                Surface(tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Connected",
                            tint = LocalExtendedColors.current.success,
                        )
                        Spacer(Modifier.width(tokens.spacingSm))
                        Text("Registered - ${state.server}", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // CENTER: Future dial pad area
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Dialpad,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(tokens.spacingSm))
                        Text(
                            "Dial pad - Phase 3",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // BOTTOM: Actions
                FilledTonalButton(
                    onClick = component::onDisconnect,
                    modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = null)
                    Spacer(Modifier.width(tokens.spacingSm))
                    Text("Disconnect")
                }
            }
        }
        else -> { /* loading/error handled by navigation */ }
    }
}
```

---

## 6. Design System

### MaterialKolor + ExtendedColors + Typography

```kotlin
// ui/theme/Theme.kt

// Extended semantic colors not in M3 spec
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        success = Color(0xFF2E7D32),
        onSuccess = Color.White,
        successContainer = Color(0xFFD4EDDA),
        onSuccessContainer = Color(0xFF155724),
    )
}

private val SeedColor = Color(0xFF1A5276) // Professional blue

// Custom typography — not default Roboto
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    // Phase 3: displayLarge for dial pad number display (tabular figures font)
)

@Composable
fun YallaSipPhoneTheme(content: @Composable () -> Unit) {
    val colorScheme = rememberDynamicColorScheme(
        seedColor = SeedColor,
        isDark = false,
        // Phase 2: Add isDark parameter, detect system theme
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        // Provide extended colors
        CompositionLocalProvider(
            LocalExtendedColors provides ExtendedColors(
                success = Color(0xFF2E7D32),
                onSuccess = Color.White,
                successContainer = Color(0xFFD4EDDA),
                onSuccessContainer = Color(0xFF155724),
            ),
            content = content,
        )
    }
}
```

### Design Tokens (CompositionLocal)

```kotlin
// ui/theme/AppTokens.kt
data class AppTokens(
    // Spacing
    val spacingXs: Dp = 4.dp,
    val spacingSm: Dp = 8.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val spacingXl: Dp = 32.dp,

    // Elevation
    val elevationNone: Dp = 0.dp,
    val elevationLow: Dp = 2.dp,
    val elevationMedium: Dp = 6.dp,

    // Corner radius
    val cornerSmall: Dp = 8.dp,
    val cornerMedium: Dp = 12.dp,
    val cornerLarge: Dp = 16.dp,

    // Window
    val windowWidth: Dp = 420.dp,
    val windowHeight: Dp = 600.dp,
    val windowMinWidth: Dp = 380.dp,
    val windowMinHeight: Dp = 480.dp,
)

val LocalAppTokens = staticCompositionLocalOf { AppTokens() }
```

Usage: `LocalAppTokens.current.spacingMd`. Refactor-proof for future compact mode, responsive tokens.

---

## 7. Credential Persistence

```kotlin
// data/settings/AppSettings.kt
class AppSettings {
    private val settings = Settings() // JVM: java.util.prefs.Preferences

    fun saveCredentials(credentials: SipCredentials) {
        settings.putString("sip_server", credentials.server)
        settings.putInt("sip_port", credentials.port)
        settings.putString("sip_username", credentials.username)
        // Password NOT saved — Phase 4: macOS Keychain
    }

    fun loadCredentials(): SipCredentials? {
        val server = settings.getStringOrNull("sip_server") ?: return null
        val username = settings.getStringOrNull("sip_username") ?: return null
        return SipCredentials(
            server = server,
            port = settings.getInt("sip_port", 5060),
            username = username,
            password = "", // user re-enters each time
        )
    }
}
```

---

## 8. Logging

```xml
<!-- logback.xml -->
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="uz.yalla.sipphone" level="DEBUG"/>
    <logger name="pjsip.native" level="INFO"/>

    <root level="WARN">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

pjsip native logs routed via `PjsipLogWriter` → SLF4J → logback. Logger name: `pjsip.native`.

---

## 9. Entry Point (Main.kt)

```kotlin
// Main.kt
private val logger = KotlinLogging.logger {}

fun main() {
    // 1. Start Koin
    val koin = startKoin {
        modules(appModule)
    }.koin

    // 2. Init pjsip (with error handling)
    val sipEngine: SipEngine = koin.get()
    val initResult = runBlocking { sipEngine.init() }

    if (initResult.isFailure) {
        // Show error dialog before any Compose window
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Failed to initialize SIP engine:\n${initResult.exceptionOrNull()?.message}",
            "Yalla SIP Phone - Error",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    // 3. Add shutdown hook (defense against force-kill)
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            withTimeoutOrNull(2000) { sipEngine.destroy() }
        }
    })

    // 4. Create Decompose lifecycle + root component
    val lifecycle = LifecycleRegistry()
    val rootComponent = RootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle),
        registrationFactory = { ctx, onRegistered ->
            RegistrationComponent(ctx, sipEngine, koin.get(), onRegistered)
        },
        dialerFactory = { ctx, onDisconnected ->
            DialerComponent(ctx, sipEngine, onDisconnected)
        },
    )

    // 5. Launch Compose window
    application {
        val windowState = rememberWindowState(
            size = DpSize(420.dp, 600.dp),
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                runBlocking {
                    withTimeoutOrNull(3000) { sipEngine.destroy() }
                }
                exitApplication()
            },
            title = "Yalla SIP Phone",
            state = windowState,
        ) {
            // Enforce minimum window size
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(380, 480)
            }

            LifecycleController(lifecycle, windowState, LocalWindowInfo.current)

            YallaSipPhoneTheme {
                CompositionLocalProvider(LocalAppTokens provides AppTokens()) {
                    RootContent(rootComponent)
                }
            }
        }
    }
}
```

`LifecycleController` import: `com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController`.

---

## 10. Component Migration (Bug Fixes)

During migration from PoC, fix these known issues:

### SipCredentialsForm
- Remove orphan `Row` wrapper around Port field
- Port field: `Modifier.widthIn(min = 120.dp, max = 160.dp)` instead of fixed width
- Enter key: filter `event.type == KeyEventType.KeyDown` (currently fires twice: KeyDown + KeyUp)
- Rename `ConnectionState` references → `RegistrationState`

### ConnectionStatusCard
- Add `contentDescription` to status icons: `"Registration successful"`, `"Registration failed"`, `"Registering"`
- Add `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` for screen reader announcements
- Rename `ConnectionState` references → `RegistrationState`
- Replace `.copy(alpha = 0.8f)` with semantic M3 color tokens

### ConnectButton
- Remove dead `AnimatedContent(targetState = true)` wrapper (never animates)
- Rename `ConnectionState` references → `RegistrationState`

### RegistrationScreen (migrated from MainScreen)
- Wrap content in `Modifier.verticalScroll(rememberScrollState())` for small window overflow
- Use `when (val state = registrationState)` for smart cast (no unsafe `as`)

---

## 11. PoC Code Removal

### Files to Delete

| File | Reason |
|------|--------|
| `sip/SipClient.kt` | Replaced by PjsipBridge |
| `sip/SipTransport.kt` | pjsip handles UDP transport |
| `sip/SipMessage.kt` | pjsip handles SIP messages |
| `sip/DigestAuth.kt` | pjsip handles Digest Auth |
| `App.kt` | Replaced by Decompose RootContent |
| `ui/screen/MainScreen.kt` | Migrated to feature/registration/ |
| `domain/ConnectionState.kt` | Replaced by RegistrationState |
| `test/.../DigestAuthTest.kt` | Testing deleted code |
| `test/.../SipMessageTest.kt` | Testing deleted code |
| `test/.../SipClientTest.kt` | Testing deleted code |

### Files to Keep and Migrate

| File | Action |
|------|--------|
| `domain/SipCredentials.kt` | Keep as-is |
| `ui/component/SipCredentialsForm.kt` | Fix bugs (see section 10), rename state types |
| `ui/component/ConnectionStatusCard.kt` | Fix a11y (see section 10), rename state types |
| `ui/component/ConnectButton.kt` | Remove dead code (see section 10), rename state types |
| `ui/theme/Theme.kt` | Rewrite with MaterialKolor + ExtendedColors + Typography |

---

## 12. build.gradle.kts

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "uz.yalla.sipphone"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Serialization (Decompose screen configs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Navigation
    implementation("com.arkivanov.decompose:decompose:3.4.0")
    implementation("com.arkivanov.decompose:extensions-compose:3.4.0")
    implementation("com.arkivanov.essenty:lifecycle-coroutines:2.5.0")

    // DI
    implementation("io.insert-koin:koin-core:4.1.1")

    // Design system
    implementation("com.materialkolor:material-kolor:2.0.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // Settings persistence
    implementation("com.russhwolf:multiplatform-settings-no-arg:1.3.0")

    // pjsip JNI bindings
    implementation(files("libs/pjsua2.jar"))

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

compose.desktop {
    application {
        mainClass = "uz.yalla.sipphone.MainKt"

        jvmArgs += "-Djava.library.path=${projectDir}/libs"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "YallaSipPhone"
            packageVersion = "1.0.0"
            vendor = "Ildam"
            description = "Yalla SIP Phone - Oktell Operator Softphone"

            macOS {
                bundleID = "uz.yalla.sipphone"
            }
        }
    }
}
```

---

## 13. Testing Strategy

### FakeSipEngine (test double)

```kotlin
// test/.../domain/FakeSipEngine.kt
class FakeSipEngine : SipEngine {
    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState = _registrationState.asStateFlow()

    private val _events = MutableSharedFlow<SipEvent>()
    override val events = _events.asSharedFlow()

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

    // Test helpers
    fun simulateRegistered(server: String = "sip:102@192.168.0.22") {
        _registrationState.value = RegistrationState.Registered(server)
    }

    fun simulateFailed(message: String = "403 Forbidden") {
        _registrationState.value = RegistrationState.Failed(message)
    }
}
```

### Integration Test: PjsipBridge (requires Oktell LAN)

```kotlin
class PjsipBridgeTest {
    private val bridge = PjsipBridge()

    @BeforeTest
    fun setup() { runBlocking { bridge.init() } }

    @AfterTest
    fun teardown() { runBlocking { bridge.destroy() } }

    @Test
    fun `register with valid credentials`() = runTest {
        bridge.register(SipCredentials("192.168.0.22", 5060, "102", "1234qwerQQ"))
        val state = bridge.registrationState.first { it !is RegistrationState.Registering }
        assertTrue(state is RegistrationState.Registered)
    }

    @Test
    fun `register with wrong password fails`() = runTest {
        bridge.register(SipCredentials("192.168.0.22", 5060, "102", "wrongpass"))
        val state = bridge.registrationState.first { it !is RegistrationState.Registering }
        assertTrue(state is RegistrationState.Failed)
    }

    @Test
    fun `unregister returns to Idle`() = runTest {
        bridge.register(SipCredentials("192.168.0.22", 5060, "102", "1234qwerQQ"))
        bridge.registrationState.first { it is RegistrationState.Registered }
        bridge.unregister()
        assertEquals(RegistrationState.Idle, bridge.registrationState.value)
    }
}
```

### Unit Test: RegistrationComponent (with FakeSipEngine)

```kotlin
class RegistrationComponentTest {
    @Test
    fun `empty server shows validation error`() {
        val errors = validateForm(FormState(server = "", port = "5060", username = "102", password = "pass"))
        assertNotNull(errors.server)
    }

    @Test
    fun `valid form produces no errors`() {
        val errors = validateForm(FormState(server = "192.168.0.22", port = "5060", username = "102", password = "pass"))
        assertNull(errors.server)
        assertNull(errors.port)
        assertNull(errors.username)
        assertNull(errors.password)
    }

    @Test
    fun `onConnect calls register on SipEngine`() = runTest {
        val fakeSipEngine = FakeSipEngine()
        val component = createRegistrationComponent(fakeSipEngine)

        component.onConnect(SipCredentials("192.168.0.22", 5060, "102", "pass"))
        advanceUntilIdle()

        assertEquals("102", fakeSipEngine.lastCredentials?.username)
    }

    @Test
    fun `onRegistered fires once on Registered state`() = runTest {
        val fakeSipEngine = FakeSipEngine()
        var registeredCount = 0
        val component = createRegistrationComponent(fakeSipEngine) { registeredCount++ }

        fakeSipEngine.simulateRegistered()
        advanceUntilIdle()

        assertEquals(1, registeredCount)
    }
}
```

---

## 14. Migration Checklist

1. Create `libs/` directory with `pjsua2.jar` + `libpjsua2.jnilib`
2. Update `build.gradle.kts` with new dependencies
3. Create domain layer: `SipEngine`, `RegistrationState`, `CallState`, `SipEvent`
4. Implement `PjsipBridge` + `PjsipAccount` + `PjsipLogWriter`
5. Implement `AppSettings`
6. Create `RegistrationModel.kt` (FormState, FormErrors, validateForm)
7. Create navigation: `Screen`, `RootComponent` (factory pattern), `RootContent`
8. Create `RegistrationComponent` (SipEngine interface, .first {} navigation)
9. Create `RegistrationScreen` (migrate from PoC MainScreen, add scroll)
10. Create `DialerComponent` + `DialerScreen` (three-zone skeleton)
11. Rewrite `Theme.kt`: MaterialKolor + ExtendedColors + Typography
12. Create `AppTokens.kt` with CompositionLocal
13. Rewrite `Main.kt`: Koin + error dialog + shutdown hook + Decompose + min window size
14. Fix `SipCredentialsForm`: remove orphan Row, fix Enter key double-fire, widthIn
15. Fix `ConnectionStatusCard`: add contentDescription, liveRegion, rename state types
16. Fix `ConnectButton`: remove dead AnimatedContent, rename state types
17. Add `logback.xml`
18. Create `FakeSipEngine` test double
19. Write tests (PjsipBridge integration + RegistrationComponent unit)
20. Delete PoC SIP layer (4 files + 3 test files + App.kt + ConnectionState.kt + MainScreen.kt)
21. Verify: `./gradlew build`
22. Verify: Register with Oktell (192.168.0.22, user 102)
23. Verify: Navigate Registration -> Dialer -> Registration

---

## Review Fixes Applied

All findings from 4 expert reviewers incorporated:

| # | Fix | Source |
|---|-----|--------|
| 1 | `SipEngine` interface in domain layer | Architect |
| 2 | `.first {}` for navigation callbacks (no duplicate push) | Architect + Kotlin |
| 3 | `coroutineScope()` no-arg (lifecycle-managed, Main thread) | Kotlin |
| 4 | Error handling + Result return for all pjsip JNI calls | Architect |
| 5 | `prm.code` check instead of `regIsActive` only | pjsip |
| 6 | try-catch in `onRegState` (prevents JNI crash) | pjsip |
| 7 | SWIG `delete()` on EpConfig, TransportConfig, AccountConfig, Version | pjsip |
| 8 | Split PhoneState → RegistrationState + CallState | Architect |
| 9 | Component factory pattern for RootComponent | Architect |
| 10 | `mainThreadOnly = false` (unnecessary with threadCnt=0) | pjsip |
| 11 | `setRegistration(false)` + timeout for clean unregister | pjsip |
| 12 | `pollJob?.join()` before libDestroy | pjsip |
| 13 | `libRegisterThread` in poll loop | pjsip |
| 14 | `sealed interface` for all stateless hierarchies | Kotlin |
| 15 | FormState moved to `feature/registration/RegistrationModel.kt` | Kotlin |
| 16 | Tokens → `AppTokens` data class + `CompositionLocal` | Design |
| 17 | Custom Typography (headlineMedium, headlineSmall, titleMedium) | Design |
| 18 | slide+fade navigation transition | Design |
| 19 | `ExtendedColors` data class (not per-color CompositionLocal) | Design |
| 20 | Accessibility: contentDescription, liveRegion, focus management | Design |
| 21 | Window min size enforcement via `LaunchedEffect` | Design |
| 22 | `runBlocking` shutdown timeout (3s) + JVM shutdown hook | Kotlin + pjsip |
| 23 | PjsipLogWriter routes native logs to logback | pjsip |
| 24 | `FakeSipEngine` test double for real component tests | Architect |
| 25 | `endpoint.delete()` + `scope.cancel()` + dispatcher close in destroy | pjsip + Kotlin |
| 26 | Error dialog (JOptionPane) if pjsip init fails | Architect + Kotlin |
| 27 | Three-zone DialerScreen layout skeleton | Design |
| 28 | PoC bug fixes during migration (AnimatedContent, Enter key, a11y) | Design |
