# Yalla SIP Phone

Desktop VoIP softphone for [Ildam](https://github.com/RoyalTaxi) call center operators. Connects to Oktell and Asterisk PBX via SIP. Built with Kotlin, Compose Desktop, and pjsip.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.1.20 |
| UI | Compose Desktop 1.8.2 (Material 3) |
| SIP engine | pjsip 2.16 via SWIG/JNI |
| Navigation | Decompose 3.4.0 |
| DI | Koin 4.1.1 |
| Theming | MaterialKolor 2.0.0 |
| Embedded browser | JCEF (Chromium — dispatcher web UI) |
| Logging | Logback + kotlin-logging |
| Settings | multiplatform-settings |
| Testing | JUnit 5, Turbine, Compose UI Test |

## Quick Start

### Prerequisites

- JDK 21+
- pjsip native library for your platform:
  - macOS: `libs/libpjsua2.jnilib`
  - Windows: `libs/pjsua2.dll`
  - Linux: `libs/libpjsua2.so`

### Run

```bash
./gradlew run                # Run with real SIP engine
./gradlew runDemo            # Run with fake engines (no pjsip needed)
./gradlew test               # Run tests
./gradlew build              # Full build
```

### Package

```bash
./gradlew packageDmg         # macOS
./gradlew packageMsi         # Windows (requires WiX Toolset v3)
./gradlew packageDeb         # Linux
```

## Architecture

Clean Architecture with MVI pattern. Domain layer defines pure Kotlin interfaces, data layer provides pjsip/JCEF implementations, features consume domain through Koin.

```
uz.yalla.sipphone/
├── domain/          Pure interfaces and models (15 files)
├── data/
│   ├── pjsip/       SIP implementation via pjsip JNI (8 files)
│   ├── jcef/        Chromium browser + JS bridge (5 files)
│   ├── auth/        Authentication (mock, real backend planned)
│   └── settings/    Persistent settings
├── feature/
│   ├── login/       SIP login screen
│   └── main/        Main screen (toolbar + webview)
├── navigation/      Decompose type-safe navigation
├── di/              Koin modules
├── ui/              Theme, design tokens, strings
└── util/            Phone masking, time formatting
```

See [docs/architecture.md](docs/architecture.md) for detailed architecture documentation.

## Features

### Implemented

- SIP registration (UDP/TCP) with credential persistence
- Outbound/inbound calls with answer, reject, hangup
- Mute and hold with proper pjsip media routing
- DTMF tone sending (IVR navigation)
- Blind call transfer (SIP REFER)
- Auto-reconnect with exponential backoff
- JS Bridge API for dispatcher web panel integration
- Call event simulator for frontend testing
- Native packaging (DMG, MSI, DEB)
- Material 3 UI with dark/light themes
- Keyboard shortcuts (Space to answer, Ctrl+L for phone input)
- Real backend authentication (PIN login → JWT → auto SIP registration)
- Session expiry handling (HTTP 401 + JS bridge requestLogout)

### Not Yet Implemented

| Feature | Priority | Notes |
|---------|----------|-------|
| Audio device selection | P0 | |
| TLS/SRTP | P0 | Signaling + media encryption |
| Auto-answer | P1 | Configurable for Ready agents |
| Multiple concurrent calls | P1 | Hold/switch between calls |
| Attended transfer | P1 | Consult-then-transfer |
| Call quality monitoring | P1 | Real MOS/jitter/loss metrics |
| Call history/CDR | P1 | Agent accountability |
| Auto-update | P1 | Design ready, see [docs/planned/auto-update.md](docs/planned/auto-update.md) |
| System tray + notifications | P2 | |
| i18n (uz/ru/en) | P2 | String extraction done |

## Codebase Stats

| Metric | Count |
|--------|-------|
| Source files | ~75 |
| Test files | ~23 |
| Source lines | ~5,400 |
| Test lines | ~3,600 |
| Test methods | ~149 |

## Platform Support

| Platform | Status |
|----------|--------|
| macOS (arm64) | Primary development platform |
| Windows (x64) | Tested, packaged as MSI |
| Linux (x64) | DEB packaging available |

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/architecture.md) | Code structure, patterns, module descriptions |
| [pjsip Guide](docs/pjsip-guide.md) | Critical pjsip/SWIG rules and gotchas |
| [JS Bridge API](docs/js-bridge-api.md) | Frontend integration guide for dispatcher panel |
| [Testing](docs/testing.md) | Test framework, demo mode, writing tests |
| [Windows Build](docs/windows-build.md) | Windows MSI build guide with pjsip compilation |
| [Backend Auth Spec](docs/superpowers/specs/2026-04-08-backend-integration-design.md) | Backend integration design spec |
| [Auto-Update](docs/planned/auto-update.md) | Auto-update mechanism design |

## License

Proprietary. Internal tool developed by [Ildam](https://github.com/RoyalTaxi) for call center operations.
