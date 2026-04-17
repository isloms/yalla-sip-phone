---
date: 2026-04-17
task: Decompose SIP-layer god-classes per Uncle Bob's Clean Code
type: refactor
scope: data/pjsip
outcome: completed
commits:
  - 603c87a refactor(pjsip): extract forEachActiveAudioMedia helper
  - 37e6e96 refactor(pjsip): extract HoldController
  - ca22bac refactor(pjsip): extract ReconnectController
  - f3c3841 refactor(pjsip): extract RegisterRateLimiter
  - ac60eb4 refactor(pjsip): extract AccountConfigBuilder
  - 4bff1ba refactor(pjsip): introduce CallStateMachine with explicit transitions
files_changed:
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/AudioMediaIterator.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/HoldController.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/ReconnectController.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/RegisterRateLimiter.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/AccountConfigBuilder.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/CallStateMachine.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCallManager.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccountManager.kt
  - src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipSipAccountManager.kt
  - src/test/kotlin/uz/yalla/sipphone/data/pjsip/HoldControllerTest.kt
  - src/test/kotlin/uz/yalla/sipphone/data/pjsip/ReconnectControllerTest.kt
  - src/test/kotlin/uz/yalla/sipphone/data/pjsip/RegisterRateLimiterTest.kt
  - src/test/kotlin/uz/yalla/sipphone/data/pjsip/CallStateMachineTest.kt
pages_updated:
  - 03-decisions/2026-04-17-sip-clean-code-decomposition.md
  - 03-decisions/README.md
last_verified_sha: 4bff1ba
tags: [session, refactor, pjsip, clean-code]
---

## Task

Audit the SIP layer's quality against Uncle Bob's Clean Code principles, then execute the full 6-task TDD decomposition plan. Three god-classes (`PjsipCallManager`, `PjsipAccountManager`, `PjsipSipAccountManager`) each held several unrelated responsibilities (hold state, reconnect backoff, rate-limiting, config building, call-state transitions) mixed into their main flow.

## What Was Done

Six focused components extracted, each with its own unit tests where the SWIG surface allowed:

| # | Component | Responsibility | Tests |
|---|-----------|----------------|-------|
| 1 | `AudioMediaIterator` (inline extension) | Walks active audio media on a `CallInfo` | integration-only (SWIG-heavy) |
| 2 | `HoldController` | One hold/unhold op at a time; auto-clear on media-state change or 15s timeout | 6 |
| 3 | `ReconnectController` | Exponential backoff reconnect loop with cap + jitter | 5 |
| 4 | `RegisterRateLimiter` | Min-interval gate between register attempts per account | 4 |
| 5 | `AccountConfigBuilder` | Pure factory object that turns `SipCredentials` into `AccountConfig` | integration-only (SWIG-heavy) |
| 6 | `CallStateMachine` | Pure `CallState × CallEvent → CallState` transition table | 11 |

**God-class shrinkage:**

| File | Before | After | Δ |
|------|--------|-------|---|
| `PjsipCallManager.kt` | 368 | 328 | −40 |
| `PjsipAccountManager.kt` | 179 | 149 | −30 |
| `PjsipSipAccountManager.kt` | 195 | 179 | −16 |

Total: **−86 lines from god-classes, +249 lines across 6 new focused files, +394 lines of unit tests (26 new tests).**

## Key Decisions

- **HoldController takes a lambda, not a `PjsipCall`.** Plan originally assumed the controller would hold a call reference and call `call.setHold()` / `call.reinvite()` directly. Redesigned to take an `op: () -> Unit` lambda so it is SWIG-agnostic and testable without MockK (which isn't used in this project). Why: kept the controller free of PJSIP types while still testing the single-in-flight gate deterministically on a `TestDispatcher`.
- **CallStateMachine exposes `dispatch(event): CallState` (returns the new state).** Tests read the return value directly rather than always going through the StateFlow. Why: cleaner test assertions; the StateFlow is still the single source of truth for subscribers.
- **Invalid transitions no-op instead of throwing.** `MuteChanged` while in `Ringing`, `Answered` while in `Idle`, etc. all return `current` unchanged. Why: PJSIP callbacks can race (e.g. an `onCallConfirmed` arriving after `LocalHangup` put us in `Ending`). Throwing would crash the event loop thread; the old scattered `as? CallState.X ?: return` pattern already did this implicitly — now it's encoded in one table.
- **Clock injection on `RegisterRateLimiter`**: `clock: () -> Long = System::currentTimeMillis` default, overridable in tests to use virtual time.

## Gotchas / Learnings

- **Latent first-call-delay bug exposed in rate limiter.** Original inline rate-limit code in `PjsipAccountManager` did `wait = RATE_LIMIT_MS - (now - lastAttemptMs)` with `lastAttemptMs = 0L` initial. It worked only because `System.currentTimeMillis()` ≫ `RATE_LIMIT_MS`, so `wait` went negative. Copying that logic verbatim into `RegisterRateLimiter` broke every virtual-time test with a 1-second false wait on the first call. Fixed by treating "no previous attempt" as "skip wait" — a map-absence check, not a sentinel zero.
- **`runCatching` block type inference from last expression is load-bearing.** `stateMachine.dispatch(...)` returns `CallState`, so `return runCatching { ... dispatch(...) }` made the block `Result<CallState>` instead of the declared `Result<Unit>`. Fix: explicit type parameter `runCatching<Unit> { ... }`. Kotlin then discards the trailing expression's value. Worth remembering any time a function body's last expression has a non-Unit return.
- **Clean Code decomposition pays immediately in test surface.** Pre-refactor, the only way to test hold-reentrancy or backoff capping was to run the full `PjsipCallManager` with a fake engine. Post-refactor, each controller has a 10-line test that sets up a `TestScope`, calls a single method, and asserts on virtual time. This kind of surface is why the extractions are worth the files.

## Follow-ups for Next Session

- [ ] Manual smoke test: register on 192.168.30.103, confirm hold/unhold still works end-to-end (blocked on working headset + PBX)
- [ ] Consider extracting `PjsipCall.applyMuteState` → `MuteController` if a similar need arises. Held off for now — one call site, no re-entrancy concern.
- [ ] Audit `PjsipSipAccountManager` (multi-account orchestrator) for further extraction candidates — it's still 179 lines and holds both session lifecycle and flow fan-out.
- [ ] Consider adding `ktlint`/`detekt` gradle plugins so the `.claude/settings.json` format hook actually does something (currently no-op per project CLAUDE.md).

## Verification

- Build: `./gradlew clean build test` — BUILD SUCCESSFUL in 20s
- Tests: all green, including 26 new unit tests for the extracted controllers
- Lint: not wired up in this project (no ktlint/detekt plugin)
- Manual review: deferred to next session with real PBX access
