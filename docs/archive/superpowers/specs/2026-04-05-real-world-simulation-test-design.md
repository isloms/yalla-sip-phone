# Real-World Operator Simulation Test Framework

**Date:** 2026-04-05
**Status:** Approved

## Overview

Integration test framework that simulates a super-busy taxi dispatch operator's day. Two modes: headless (CI) and visual demo (real window).

## Scenario Mix

| # | Scenario | % | Timing |
|---|----------|---|--------|
| 1 | Normal incoming → answer → talk → agent hangup | 40% | Talk: 20-90s |
| 2 | Normal incoming → answer → talk → caller hangup | 15% | Talk: 15-60s |
| 3 | Incoming → caller abandon (during ring) | 12% | Ring: 2-8s |
| 4 | Incoming → answer → hold → unhold → hangup | 7% | Hold: 5-30s |
| 5 | Incoming → answer → mute → unmute → hangup | 5% | Mute: 3-15s |
| 6 | Incoming → answer → DTMF → hangup | 4% | DTMF: 2-5 digits |
| 7 | Incoming → answer → transfer | 4% | Pre-transfer: 10-30s |
| 8 | Very short call (answer → instant hangup) | 3% | Talk: 1-5s |
| 9 | Ring timeout (no answer) | 3% | Ring: 15-30s |
| 10 | Incoming → reject | 2% | Instant |
| 11 | Network drop mid-call → reconnect | 2% | Drop at random |
| 12 | Very long call (complaint) | 2% | Talk: 3-5min |
| 13 | Hold → caller hangup | 1% | Hold: 10-45s |

## Traffic Pattern

Burst, not uniform:
- BURST (70%): 3-6 calls with 0-2s gap
- BREATHE (20%): 15-30s gap
- LULL (10%): 30-60s gap

Inter-call wrap-up: 2-10s typical.

## Architecture

### New Dependencies
```kotlin
testImplementation(compose.desktop.uiTestJUnit4)
testImplementation("app.cash.turbine:turbine:1.2.1")
```

### Components

1. **CallScenario DSL** — builder for scripting call state sequences
2. **ScriptableCallEngine** — CallEngine impl that plays scripted scenarios
3. **ScriptableRegistrationEngine** — RegistrationEngine with direct state control
4. **ScenarioRunner** — orchestrator that coordinates engines with timing
5. **Scenarios** — pre-built realistic scenarios (shared headless/visual)
6. **DemoMain** — visual mode entry point (`./gradlew runDemo`)

### Timing Strategy

Same scenarios, different time:
- Headless: `runTest {}` + TestDispatcher → delay() is virtual (instant)
- Visual: `Dispatchers.Default` → delay() is real time

No `if (isDemo)` logic needed — Kotlin coroutines handle this naturally.

### Directory Structure
```
src/test/kotlin/uz/yalla/sipphone/
├── testing/
│   ├── engine/
│   │   ├── ScriptableCallEngine.kt
│   │   └── ScriptableRegistrationEngine.kt
│   └── scenario/
│       ├── CallScenario.kt
│       ├── ScenarioRunner.kt
│       ├── TrafficPattern.kt
│       └── Scenarios.kt
├── integration/
│   ├── CallFlowIntegrationTest.kt
│   ├── BusyOperatorIntegrationTest.kt
│   └── ToolbarUiIntegrationTest.kt
└── demo/
    └── DemoMain.kt
```

## Headless Mode

- Runs in CI, no display needed
- `runComposeUiTest {}` uses offscreen Skiko buffer
- 50-100 calls verified in ~3 seconds
- Turbine for Flow assertion
- Every state transition verified

## Visual Demo Mode

- `./gradlew runDemo` opens real window
- Fake engines drive calls — no pjsip, no JCEF, no server
- Real-time delays — watch operator's busy day unfold
- WebView panel shows "about:blank" (no dispatcher needed)
- Console logs each event for monitoring
