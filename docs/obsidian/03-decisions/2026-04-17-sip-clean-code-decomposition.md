---
title: "Decompose SIP-layer god-classes into focused single-responsibility components"
date: 2026-04-17
status: accepted
last_verified_sha: 4bff1ba
tags: [decision, adr, pjsip, clean-code, refactor]
---

## Context

After the SIP multi-account work landed (~Apr 4–13, 2026, 249 commits), three classes in `data/pjsip/` had drifted into god-class territory:

- `PjsipCallManager` (~368 LoC) — call lifecycle + hold re-entrancy + mute toggles + DTMF + transfer + state machine + timeout loops + audio media wiring
- `PjsipAccountManager` (~179 LoC) — per-endpoint account lifecycle + registration + rate limiting + config construction
- `PjsipSipAccountManager` (~195 LoC) — multi-account orchestration + reconnect backoff + flow fan-out + session map

Each class mixed unrelated policies into its main flow. In particular:

- **Hold re-entrancy** was an ad-hoc `@Volatile` flag + `launch { delay() }` block embedded in `toggleHold`
- **Reconnect backoff** computed `BASE * (1L shl min(attempt-1, 20))` inline inside `scheduleReconnect`
- **Register rate-limiting** compared `System.currentTimeMillis()` against a mutable map directly inside `register`
- **Account config construction** was a private helper that built an `AccountConfig` with seven separate SWIG calls
- **Call state** transitions were scattered as `_callState.value = CallState.X(...)` writes across 10+ sites, each with its own `as? CallState.Y ?: return` guard

This style makes it impossible to unit-test the policies in isolation: every test needs a full `PjsipCallManager` + fake engine to exercise a single backoff cap.

## Decision

Extract each non-trivial policy into its own focused class and test it with a `TestDispatcher`. The god-classes become orchestrators that compose the policies, not implement them.

Six extractions, all TDD (red → green → refactor → commit):

1. **`AudioMediaIterator`** (`forEachActiveAudioMedia`) — inline extension on `CallInfo` that iterates only PJMEDIA_TYPE_AUDIO + PJSUA_CALL_MEDIA_ACTIVE media. Used in three sites that previously duplicated the filter.
2. **`HoldController`** — one hold/unhold op at a time, auto-clear on media-state change, 15s timeout fallback. Takes a lambda (not a `PjsipCall`) so it is SWIG-agnostic and testable without mocks.
3. **`ReconnectController`** — exponential backoff with cap (30s) + jitter (0–500ms), driven by an `attemptBlock: suspend () -> Result<Unit>`. Owns its own Job; `start`/`stop` are idempotent.
4. **`RegisterRateLimiter`** — min-interval gate keyed by account ID. Clock injected as `() -> Long` for virtual-time tests.
5. **`AccountConfigBuilder`** — pure object factory. Given `SipCredentials`, returns a configured `AccountConfig`. Zero state.
6. **`CallStateMachine`** — sealed `CallEvent` interface + pure `transition(current, event) → CallState` table. Exposes `state: StateFlow<CallState>` and `dispatch(event): CallState`. All `_callState.value = ...` writes inside `PjsipCallManager` replaced with `stateMachine.dispatch(CallEvent.X)`.

## Alternatives Considered

- **Leave them as-is.** Rejected: tests of individual policies require the full orchestrator, which slows iteration and makes regression risk invisible when only one policy changes.
- **One big refactor commit.** Rejected: the atomic commits per extraction kept every checkpoint green and each commit's blast radius readable. Easier to revert one extraction than one mega-commit.
- **Inject controllers via DI (Koin).** Rejected for now: the controllers are internal to the PJSIP layer and have no consumers outside `PjsipCallManager` / `PjsipAccountManager` / `PjsipSipAccountManager`. Constructing them directly in the orchestrators is YAGNI-correct. Can promote to DI later if anyone else needs to reuse them.
- **Use MockK to test hold controller with a `PjsipCall` reference.** Rejected: MockK isn't used anywhere in this codebase (verified against `.claude/CLAUDE.md` and `.claude/rules/testing.md`). Redesigned `HoldController` to take a lambda so no mocking is needed.

## Consequences

**Positive:**

- Three god-classes shrank by a combined −86 lines
- +26 new unit tests covering hold re-entrancy, backoff capping, rate-limit timing, and every CallState × CallEvent transition
- Invalid state transitions (e.g. `MuteChanged` during `Ringing`) are now encoded once in the transition table, not scattered as type checks at each dispatch site
- Caught a latent bug in the inline rate-limit logic: first-call wait was `RATE_LIMIT_MS - (now - 0)` which only worked because `currentTimeMillis() ≫ RATE_LIMIT_MS`. Exposed when copying to `RegisterRateLimiter` with virtual time and properly fixed.

**Negative:**

- Six new files in `data/pjsip/` (plus four test files). The package has more files to navigate.
- Developers touching call state need to know the transition table exists, not just mutate `_callState.value`. Mitigated by `_callState` being removed entirely — the compiler now enforces the path through `stateMachine.dispatch`.

**Neutral:**

- Testing philosophy unchanged (hand-written fakes, no mocks, JUnit 4 runtime) — the refactor didn't introduce new frameworks.
- PJSIP threading rules unchanged — all new components are called from within `pjDispatcher`-scoped code.

## Review Date

Revisit after the next significant SIP-layer change (e.g. conference calling, call transfer UI, video support). If any of the extracted controllers becomes awkward to extend, consider folding responsibilities back or splitting further.
