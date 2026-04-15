# yalla-sip-phone — Project Instructions

## What This Is

Desktop VoIP softphone for Ildam call center operators. Kotlin Compose Desktop + PJSIP (via SWIG-generated Kotlin/JNI bindings shipped as `libs/pjsua2.jar`). Connects to Oktell and Asterisk PBX.

**Location**: `~/Ildam/yalla/yalla-sip-phone/`
**pjsip source**: `~/Ildam/pjproject/` (read-only — we compile it, not modify it)
**Remote**: `isloms/yalla-sip-phone` (personal fork)
**Active branch**: `feature/manual-multi-sip-testing`

This is a **high-activity project** (249 commits in 9 days around Apr 4-13, 2026). Discipline matters more than speed here — PJSIP is unforgiving.

## Critical Context

### 1. PJSIP Threading Model (most common source of bugs)

`PjsipEngine.kt` (in `data/pjsip/`) owns `newSingleThreadContext("pjsip-event-loop")`, exposed as `val pjDispatcher: CoroutineDispatcher`. **All public API** must wrap calls in `withContext(pjDispatcher)`. Callbacks (`PjsipAccount.onRegState`, `PjsipCall.onCallState`, etc.) run **synchronously** on that same thread because `libHandleEvents` runs there.

**SWIG pointer invalidation**: SWIG-generated Kotlin pointers become invalid after the C callback returns. You CANNOT `launch` a coroutine from inside a callback and use the SWIG pointer later.

Safety gates:
- `PjsipEngine` uses `private val destroyed = AtomicBoolean(false)` + `private fun isDestroyed()` (passed as `() -> Boolean` lambda to sub-managers)
- `PjsipAccount` and `PjsipCall` use `private val deleted = AtomicBoolean(false)` for per-object lifecycle
- Engine teardown is `shutdown()` / `close()`, NOT `destroy()` (that's per-object). Full details in `rules/pjsip-threading.md` and `docs/pjsip-guide.md`.

### 2. No Code Signing Policy

This product ships without Authenticode, Apple notarization, or any paid signing certs. Product-level decision across all Ildam products. Do NOT include code signing as "must-have" in design docs.

Cert-free mitigations instead:
- Mark-of-the-Web stripping after SHA256 verify
- Download-host allow-lists pinned in source
- Hash-verified payloads
- LAN-only distribution for internal operators
- Documented SmartScreen "Unknown publisher" friction on first install

See `memory/product_stance_no_code_signing.md`.

### 3. Current Blocker

**Audio not working due to Asterisk NAT config, not our code.** Asterisk SDP advertises public IP (87.237.239.18) instead of LAN (192.168.30.103). Fix is server-side: Asterisk admin adds `localnet=192.168.30.0/24` to pjsip config. Our conference-bridge routing is verified correct — do not "fix" it.

## SDLC

Full pipeline per global CLAUDE.md. Project-specific VERIFY for this repo:

```bash
./gradlew build                           # full build for current OS
./gradlew test                            # all tests (259 @Test methods across 38 files, ~5,536 LOC)
./gradlew packageDistributionForCurrentOS # package check (dmg/msi/deb)
```

**Note**: `ktlint`, `detekt`, and `jacoco` are **NOT currently wired up** in `build.gradle.kts`. Running `./gradlew ktlintCheck` fails with "task not found". The `.claude/settings.json` PostToolUse hook calls `ktlint --format <file>` against a standalone ktlint binary — it's a no-op if the binary isn't installed globally. Add the plugins before relying on lint as a VERIFY step.

**Manual smoke test** (cannot be automated):
- Launch via `./gradlew run` or `./gradlew runDemo`
- Login to a test account (test extensions: 101, 102, 103)
- Register one SIP account → verify registration state
- Make a test call to another extension

## Quick Commands

```bash
# Run in dev mode
./gradlew run                            # production-like run (real PJSIP)
./gradlew runDemo                        # visual demo with fake SIP engines
./gradlew run --info                     # verbose output

# Build distribution for current OS
./gradlew packageDistributionForCurrentOS

# Platform-specific distributions
./gradlew packageDmg                     # macOS
./gradlew packageMsi                     # Windows (from Windows host)
./gradlew packageDeb                     # Linux

# Test
./gradlew test
./gradlew test --tests "<pattern>"
./gradlew test --tests "*IntegrationTest"   # integration tests (use fakes, not real PBX)

# Lint / static analysis — NOT CONFIGURED in this project yet
# If/when you add ktlint or detekt, uncomment:
# ./gradlew ktlintFormat
# ./gradlew ktlintCheck
# ./gradlew detekt

# PJSIP source (read-only from here)
cd ~/Ildam/pjproject && make -j3         # rebuild pjsip if source changed
```

## Servers

| Server | Host | Port | Purpose |
|--------|------|------|---------|
| Asterisk/Issabel | 192.168.30.103 | 5060 | Primary test PBX (public IP: 87.237.239.18) |
| Oktell | 192.168.0.22 | 5060 | Production PBX |

Test extensions: `101`, `102`, `103`.

## Directory Layout

Verified against the real repo:

```
yalla-sip-phone/
├── src/main/kotlin/uz/yalla/sipphone/
│   ├── Main.kt                         # app entry, Compose Desktop window
│   ├── di/                             # 8 files — Koin modules + appModules aggregator
│   │   ├── AppModule.kt                # val appModules = listOf(...) — aggregator, NOT a Koin module
│   │   ├── AuthModule.kt
│   │   ├── FeatureModule.kt
│   │   ├── NetworkModule.kt            # Ktor CIO
│   │   ├── SettingsModule.kt
│   │   ├── SipModule.kt
│   │   ├── UpdateModule.kt             # auto-update wiring
│   │   └── WebviewModule.kt            # JCEF browser
│   ├── domain/                         # Pure-Kotlin domain types + interfaces
│   │   ├── SipAccountManager.kt        # INTERFACE (impl in data/pjsip/PjsipSipAccountManager)
│   │   ├── CallEngine.kt, SipStackLifecycle.kt, ...
│   │   └── update/                     # update manifest, semver domain types
│   ├── data/
│   │   ├── pjsip/                      # PJSIP integration layer — all wrappers live here
│   │   │   ├── PjsipEngine.kt          # owns pjDispatcher, lifecycle, AtomicBoolean destroyed
│   │   │   ├── PjsipAccount.kt         # per-account wrapper, AtomicBoolean deleted
│   │   │   ├── PjsipCall.kt            # per-call wrapper, AtomicBoolean deleted
│   │   │   ├── PjsipAccountManager.kt  # per-endpoint account manager (isDestroyed lambda gate)
│   │   │   ├── PjsipSipAccountManager.kt # multi-account orchestrator; impl of SipAccountManager interface
│   │   │   ├── PjsipCallManager.kt
│   │   │   └── PjsipEndpointManager.kt
│   │   ├── auth/                       # AuthApi, AuthRepositoryImpl, LogoutOrchestrator
│   │   ├── network/                    # Ktor CIO + TokenProvider
│   │   ├── jcef/                       # BridgeRouter, BridgeProtocol, BridgeSecurity
│   │   ├── settings/                   # AppSettings persistence
│   │   └── update/                     # UpdateManager, UpdateDownloader, Sha256Verifier
│   ├── ui/                             # Compose Desktop design system
│   │   ├── component/                  # shared composables
│   │   ├── strings/                    # StringResources interface, LocalStrings, UzStrings, RuStrings
│   │   └── theme/                      # YallaColors data class, AppTokens, LocalYallaColors, LocalAppTokens, Theme
│   ├── feature/                        # One package per feature (login, main, ...)
│   ├── navigation/                     # Decompose RootComponent + ComponentFactoryImpl
│   └── util/
├── src/test/kotlin/                    # 38 files, 259 @Test methods, ~5,536 LOC
├── libs/
│   ├── libpjsua2.dylib / .dll / .jnilib  # native PJSIP binaries
│   └── pjsua2.jar                        # SWIG-generated bindings (org.pjsip.pjsua2.*)
├── docs/
│   ├── architecture.md, pjsip-guide.md, testing.md, js-bridge-api.md, windows-build.md
│   ├── planned/auto-update.md
│   ├── archive/{audit,superpowers}/   # audit logs + retired superpowers plans
│   ├── backend-integration/, releasing/, superpowers/
│   └── obsidian/                       # Second brain vault
```

**No `sip/` directory.** All PJSIP code lives in `data/pjsip/`. Entry point is `Main.kt`, not `App.kt`.

### DI Structure

Seven Koin modules + one aggregator. Know which is which before editing:

- **`appModules`** (in `AppModule.kt`) — NOT a `module { }` block. It's `val appModules = listOf(networkModule, sipModule, settingsModule, authModule, featureModule, webviewModule, updateModule)` that `startKoin { modules(appModules) }` consumes.
- **`sipModule`** — `PjsipEngine`, `PjsipSipAccountManager` (bound to `SipAccountManager` domain interface), call/endpoint/account managers
- **`authModule`** — `AuthApi`, `AuthRepositoryImpl` (bound to `AuthRepository`), `LogoutOrchestrator`. `MockAuthRepository` is TEST-ONLY, lives in `src/test/kotlin/.../data/auth/`
- **`networkModule`** — Ktor CIO client, `TokenProvider`
- **`webviewModule`** — JCEF browser, JS bridge
- **`settingsModule`** — persisted preferences (multiplatform-settings)
- **`featureModule`** — feature-specific DI (Decompose component factories)
- **`updateModule`** — `UpdateManager`, `UpdateApi`, update downloader deps for the auto-update feature

## Key Libraries

| Library | Purpose |
|---------|---------|
| Kotlin | Language (see `gradle.properties`) |
| Compose Multiplatform (Desktop) | UI framework |
| Ktor CIO | HTTP client for backend API |
| Koin | Dependency injection |
| PJSIP (via SWIG → `libs/pjsua2.jar`) | SIP stack |
| JCEF | Chromium-based embedded browser for auth flow |
| Kotlinx.coroutines | Async |
| Decompose | Navigation + component lifecycle |
| Turbine | Flow testing |
| multiplatform-settings | Persisted preferences |

**Not used**: Arrow/Either, MockK, JUnit 5. Error handling uses `Result<T>` (Kotlin stdlib) or sealed classes. Testing uses kotlin.test assertions on a JUnit 4 runtime.

## Code Style

- Kotlin official style, max line length 120
- `val` > `var`, sealed classes for state, `Result<T>` or sealed classes for business errors
- **PJSIP-specific**: never touch SWIG handles from outside `pjDispatcher`. Use `withContext(pjDispatcher)`.
- **Compose Desktop-specific**: prefer `Popup` and `DropdownMenu` over anchored `DialogWindow`. See `rules/compose-desktop.md`.
- Auto-format-on-save hook exists in `.claude/settings.json` but requires a global `ktlint` binary to be installed, or a gradle plugin to be added. No-op if neither is present.

## Outstanding Work

1. Audio end-to-end testing (blocked on server-side Asterisk `localnet` fix)
2. Auto-update mechanism — spec at `docs/planned/auto-update.md`, implementation in progress under `data/update/` + `domain/update/`
3. `YallaDropdownWindow` anchored DialogWindow wrapper (ui-layer-rewrite leftover)
4. `feature/manual-multi-sip-testing` branch needs merge to main
5. Add `ktlint` and `detekt` gradle plugins (claimed in this doc but not yet configured)

## Second Brain

Project-specific Obsidian vault: `docs/obsidian/` (symlinked into `~/Ildam-Brain/projects/yalla-sip-phone`). At session CLOSE, invoke the `update-obsidian-vault` skill. The existing `docs/architecture.md`, `docs/pjsip-guide.md`, etc. are the stable long-form reference; the vault is for new decisions and session logs.

## Skills Available

Defined in `.claude/skills/`:
- `build-desktop` — Build distributions (dmg/msi/deb)
- `publish-release` — Release flow without code signing (cert-free mitigations)
- `debug-audio` — Audio routing debugging (PJSIP, Asterisk, codec, conference bridge)
- `test-sip` — Run SIP test suite
- `add-sip-account` — Scaffold a new multi-SIP account
- `commit` — Conventional commit workflow
- `pr` — PR preparation
- `branch` — Branch creation with naming conventions

## Subagents Available

Defined in `.claude/agents/`:
- `pjsip-expert` — C/SWIG interop specialist, PJSIP API, threading rules
- `compose-desktop-expert` — Compose Desktop UI specialist
- `audio-debugger` — Audio routing, codec issues, sound device, conference bridge

## Path-Scoped Rules

Defined in `.claude/rules/` (auto-loaded when editing matching paths):
- `pjsip-threading.md` — `**/data/pjsip/**/*.kt` — pjDispatcher, SWIG invalidation, AtomicBoolean gates
- `swig-interop.md` — `**/data/pjsip/**/*.kt` — SWIG callback safety, lifecycle (SWIG types come from `libs/pjsua2.jar`, not a source dir)
- `compose-desktop.md` — `**/ui/**/*.kt`, `**/feature/**/*.kt`, `**/Main.kt` — Compose Desktop specifics, Popup vs DialogWindow, LocalStrings, LocalYallaColors
- `testing.md` — `**/src/test/**/*.kt` — JUnit 4 runtime, kotlin.test assertions, Turbine, ktor-client-mock patterns
