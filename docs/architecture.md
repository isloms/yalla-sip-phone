# Architecture

## Overview

Yalla SIP Phone follows **Clean Architecture** with an **MVI** pattern using StateFlow. The domain layer defines pure Kotlin interfaces for all SIP operations. The data layer provides concrete implementations backed by pjsip (JNI) and JCEF. Features consume domain interfaces through Koin, keeping UI decoupled from SIP internals.

The app supports **multi-account SIP registration**: an operator can be logged in with multiple SIP extensions simultaneously (e.g., 1001 and 1002). Each account has its own registration lifecycle managed by `SipAccountManager`.

## Module Map

```
Main.kt
│  Koin.startKoin() → SipStackLifecycle.initialize() → Compose Window
│
├── di/                              Koin dependency modules
│   ├── AppModule.kt                 Root module, aggregates all sub-modules
│   ├── SipModule.kt                 PjsipEngine → SipStackLifecycle + SipAccountManager + CallEngine
│   ├── AuthModule.kt                AuthRepository binding
│   ├── SettingsModule.kt            AppSettings (multiplatform-settings)
│   ├── WebviewModule.kt             JcefManager, BridgeRouter, BridgeEventEmitter
│   └── FeatureModule.kt             ComponentFactory for screen creation
│
├── domain/                          Pure interfaces and models (no dependencies)
│   ├── SipStackLifecycle.kt         initialize(), shutdown() — app-level SIP lifecycle
│   ├── SipAccountManager.kt         Multi-account: registerAll(), unregisterAll(), accounts: StateFlow
│   ├── SipAccountInfo.kt            Account metadata: id, name, extension, server, credentials
│   ├── SipAccountState.kt           Sealed: Idle → Registering → Connected / Failed
│   ├── SipAccount.kt                Composite: SipAccountInfo + SipAccountState (per-account)
│   ├── CallEngine.kt                makeCall, answer, hangup, mute, hold, sendDtmf, transfer
│   ├── AuthRepository.kt            login(password) → AuthResult
│   ├── AuthResult.kt                Login result with agent info + List<SipAccountInfo>
│   ├── CallState.kt                 Sealed: Idle → Ringing → Active → Ending → Idle (with accountId)
│   ├── SipCredentials.kt            Server, port, username, password, transport
│   ├── SipError.kt                  Sealed: AuthFailed, NetworkError, ServerError, InternalError
│   ├── SipConstants.kt              URI builders, validation, magic values
│   ├── CallerInfo.kt                Parse remote SIP URI → number + name
│   ├── PhoneNumberValidator.kt      Phone number format validation
│   ├── AgentInfo.kt                 Agent id + name
│   └── AgentStatus.kt               Enum: Ready, Away, Break, WrapUp, Offline
│
├── data/
│   ├── pjsip/                       pjsip JNI implementation
│   │   ├── PjsipEngine.kt           Thin facade (delegates to 3 managers)
│   │   ├── PjsipEndpointManager.kt  Endpoint lifecycle, transports, polling loop
│   │   ├── PjsipAccountManager.kt   Multi-account registration, rate limiting, incoming call listener
│   │   ├── PjsipSipAccountManager.kt Domain SipAccountManager impl — maps PjsipRegistrationState → SipAccountState
│   │   ├── PjsipRegistrationState.kt Internal pjsip registration state (Idle/Registering/Registered/Failed)
│   │   ├── PjsipCallManager.kt      Call ops, mute, hold, DTMF, transfer, audio routing
│   │   ├── PjsipAccount.kt          SWIG Account wrapper → AccountManager callbacks
│   │   ├── PjsipCall.kt             SWIG Call wrapper → CallManager callbacks
│   │   ├── PjsipLogWriter.kt        pjsip native logs → SLF4J bridge
│   │   └── NativeLibraryLoader.kt   OS-specific native library loading
│   │
│   ├── network/                     HTTP infrastructure
│   │   ├── HttpClientFactory.kt     Ktor CIO client factory
│   │   ├── ApiResponse.kt           Generic {status,code,message,result,errors} envelope
│   │   ├── NetworkError.kt          Sealed: Unauthorized, ClientError, ServerError, NoConnection, ParseError
│   │   └── SafeRequest.kt           safeRequest<T> inline reified — unified HTTP error handling
│   │
│   ├── jcef/                        Chromium embedded browser
│   │   ├── JcefManager.kt           JCEF lifecycle (init, browser creation, shutdown)
│   │   ├── BridgeRouter.kt          JS → Kotlin command dispatch (window.YallaSIP)
│   │   ├── BridgeEventEmitter.kt    Kotlin → JS event push
│   │   ├── BridgeProtocol.kt        Typed request/response serialization (incl. BridgeAccountState)
│   │   ├── BridgeSecurity.kt        Rate limiting, origin validation
│   │   └── BridgeAuditLog.kt        Command/event audit trail
│   │
│   ├── auth/
│   │   ├── AuthApi.kt               Raw HTTP calls (login, me, logout)
│   │   ├── AuthRepositoryImpl.kt    Login flow orchestration (login → token → me → SIP accounts)
│   │   ├── TokenProvider.kt         In-memory JWT storage
│   │   ├── AuthEventBus.kt          Session expiry event bus
│   │   ├── LogoutOrchestrator.kt    Full logout: SIP unregisterAll → API logout → clear token
│   │   ├── MockAuthRepository.kt    Hardcoded test credentials (dev only)
│   │   └── dto/                     API DTOs (LoginRequest, LoginResult, MeResult, SipConnection)
│   │
│   └── settings/
│       └── AppSettings.kt           Persistent settings via multiplatform-settings
│
├── feature/
│   ├── login/
│   │   ├── LoginComponent.kt        Login logic — calls SipAccountManager.registerAll on success
│   │   └── LoginScreen.kt           Compose UI — Yalla gradient login form
│   │
│   └── main/
│       ├── MainComponent.kt         Main screen logic, bridge ↔ call/account state sync
│       ├── MainScreen.kt            Compose layout — toolbar + webview
│       ├── toolbar/
│       │   ├── ToolbarComponent.kt   Call controls logic, ringtone, notifications, account state
│       │   ├── ToolbarContent.kt     Compose toolbar layout (fixed-height, M3 compliant)
│       │   ├── CallActions.kt        Answer/reject/hangup/mute/hold buttons
│       │   ├── CallTimer.kt          Active call duration display
│       │   ├── PhoneField.kt         Phone number input with dial button
│       │   ├── SipChipRow.kt         Multi-account status chips (per SIP extension)
│       │   ├── AgentStatusButton.kt  Agent status selector dropdown
│       │   └── SettingsDialog.kt     Settings dialog
│       └── webview/
│           └── WebviewPanel.kt       JCEF browser Swing interop
│
├── navigation/
│   ├── Screen.kt                    Sealed screen definitions
│   ├── RootComponent.kt             Navigation host (ComponentFactory pattern)
│   ├── RootContent.kt               Root Compose content with window sizing
│   ├── ComponentFactory.kt          Interface — screen creation contract
│   └── ComponentFactoryImpl.kt      Koin-backed screen factory
│
├── ui/
│   ├── theme/
│   │   ├── Theme.kt                 MaterialKolor theme setup
│   │   ├── YallaColors.kt           Yalla Design System semantic colors (WCAG AA compliant)
│   │   └── AppTokens.kt             Design tokens (sizes, shapes, spacing)
│   └── strings/
│       ├── StringResources.kt       i18n interface + locale resolver
│       ├── Strings.kt               Default strings (Uzbek)
│       ├── UzStrings.kt             Uzbek translations
│       └── RuStrings.kt             Russian translations
│
└── util/
    ├── PhoneNumberMasker.kt         PII-safe phone number display
    └── TimeFormat.kt                Call duration formatting
```

## Key Patterns

### Domain-Driven Interfaces

All SIP operations go through domain interfaces. The `data/pjsip/` layer is the only code that touches JNI. If we ever switch from pjsip to another SIP stack, only the data layer changes.

```
UI → Feature (Decompose Component) → Domain Interface → Data Implementation
                                          ↑                      ↓
                                      CallEngine         PjsipCallManager
                                  SipAccountManager      PjsipSipAccountManager
                                  SipStackLifecycle      PjsipEngine
```

### Multi-Account SIP Registration

The app supports registering multiple SIP accounts simultaneously. After login, the backend returns a list of `SipAccountInfo` entries (one per extension). `SipAccountManager.registerAll()` registers all of them in parallel.

```
AuthRepository.login() → AuthResult { agent, List<SipAccountInfo> }
                                              ↓
SipAccountManager.registerAll(accounts) → per-account PjsipAccount → PjsipAccountManager
                                              ↓
accounts: StateFlow<List<SipAccount>>  ← each account tracked independently
                                              ↓
SipAccountState: Idle → Registering → Connected / Failed
```

Each `SipAccount` combines `SipAccountInfo` (static metadata) with `SipAccountState` (live status). The toolbar shows per-account status chips via `SipChipRow`.

### State Machine

Call state follows a strict sealed class progression:

```
Idle → Ringing(inbound/outbound) → Active(mute/hold) → Ending → Idle
```

Each state carries its own data including `accountId` to identify which SIP account the call belongs to. UI collects `CallEngine.callState: StateFlow<CallState>` and renders accordingly.

### PjsipEngine Facade

`PjsipEngine` is a thin facade (~95 lines) that delegates to three focused managers:

| Manager | Responsibility |
|---------|---------------|
| `PjsipEndpointManager` | Endpoint lifecycle, transport creation, polling loop |
| `PjsipAccountManager` | Multi-account SIP registration, rate limiting, incoming call dispatch |
| `PjsipCallManager` | Call operations, media routing, DTMF, transfer |

`PjsipSipAccountManager` sits on top as the domain-facing adapter: it maps low-level `PjsipRegistrationState` to domain `SipAccountState` and manages the `accounts` StateFlow.

All pjsip operations run on a single-thread `pjDispatcher` to satisfy pjsip's threading requirements.

### JS Bridge

The dispatcher web panel runs inside JCEF (embedded Chromium). Communication flows through a typed bridge:

```
React App (window.YallaSIP) → BridgeRouter → CallEngine/SipAccountManager
                             ← BridgeEventEmitter ← CallState/AccountState changes
```

The bridge handles: commands (makeCall, hangup, setMute, sendDtmf, transferCall), queries (getState with accounts array, getVersion), and events (incomingCall, callEnded, connectionChanged, accountStatusChanged).

See [js-bridge-api.md](js-bridge-api.md) for the full API reference.

### i18n

All user-facing strings go through `StringResources` — a simple interface with Uzbek and Russian implementations. `Strings.kt` acts as the default (Uzbek). The locale is resolved from system settings at startup.

### Navigation

Decompose-based with a `ComponentFactory` pattern:

```
RootComponent → ComponentFactory.createLoginComponent()
              → ComponentFactory.createMainComponent()
```

`ComponentFactoryImpl` uses Koin to resolve dependencies. This avoids passing N lambdas through the navigation stack and scales to any number of screens.

### Dependency Injection

Koin modules are split by concern:

| Module | Provides |
|--------|----------|
| `SipModule` | PjsipEngine (as SipStackLifecycle, CallEngine), SipAccountManager (PjsipSipAccountManager) |
| `NetworkModule` | HttpClient (Ktor CIO), TokenProvider, AuthEventBus |
| `AuthModule` | AuthApi, AuthRepository (AuthRepositoryImpl), LogoutOrchestrator |
| `WebviewModule` | JcefManager, BridgeRouter, BridgeEventEmitter |
| `SettingsModule` | AppSettings |
| `FeatureModule` | ComponentFactory |
| `AppModule` | Aggregates all modules |

### App Lifecycle

```
Main.kt:
1. Koin.startKoin(appModule)
2. SipStackLifecycle.initialize()     → pjsip endpoint + transports created
3. Compose Window → RootComponent     → navigation starts
4. onCloseRequest / shutdown hook:
   a. SipAccountManager.unregisterAll()
   b. JcefManager.shutdown()
   c. SipStackLifecycle.shutdown()    → calls hangup → account unregister → endpoint destroy
```

Shutdown hook ensures SIP UNREGISTER is sent even on Ctrl+C / force kill.
