---
name: test-sip
description: Run the SIP test suite for yalla-sip-phone. Use when Islom wants to run tests. Triggers include phrases like "run tests", "run sip tests", "test suite", "integration tests", "test pjsip".
allowed-tools: Read, Bash, Glob, Grep
---

# Test SIP

Run the yalla-sip-phone test suite. Current state: **259 @Test methods across 38 files (~5,536 LOC)**.

## Framework (Verified)

- JUnit 4 runtime (via `kotlin("test")` + `compose.desktop.uiTestJUnit4`)
- kotlin.test assertions
- kotlinx.coroutines.test for `runTest` / `TestDispatcher`
- Turbine for Flow testing
- ktor-client-mock for HTTP mocking
- **No** JUnit 5, MockK, or Jacoco

## All Tests

```bash
./gradlew test
```

This runs all 259 tests. There is **no tag-based filtering** in this project — `-PincludeTags=integration` / `-PexcludeTags=integration` are silently ignored.

## Integration Tests

Integration tests live in `src/test/kotlin/uz/yalla/sipphone/integration/`. They include:
- `BridgeIntegrationTest`
- `BusyOperatorIntegrationTest`
- `CallFlowIntegrationTest`

They use **fakes**, not real PJSIP — no PBX required. Safe to run in the default suite. To run them alone:

```bash
./gradlew test --tests "*IntegrationTest"
```

## Single Test or Pattern

```bash
./gradlew test --tests "SipAccountManagerTest"                   # single class
./gradlew test --tests "*RegistrationFailure*"                   # pattern
./gradlew test --tests "SipAccountManagerTest.register_onSuccess_transitionsToRegistered"  # specific method
```

## With Verbose Output

```bash
./gradlew test --info                 # Gradle + test output
./gradlew test --debug                # very noisy, rarely needed
```

Note: `-PtestLogging=full` and `-PciMode=true` are **not wired up** — ignore any references to them in older docs.

## Coverage

Jacoco is **not configured** in this project. Add the plugin in a dedicated commit if you want `jacocoTestReport`.

## When Tests Fail

1. **Read the failure message first.** Don't jump to log scrolling
2. **Check if it's flaky**: re-run the single failing test. If it passes, file a flake report in the session log
3. **Check if it's a concurrency issue**: tests that use `Dispatchers.Default` instead of a `TestDispatcher` are a smell
4. **Check PJSIP lifecycle**: if you see "failed to shutdown" errors, a previous test left state. Check teardown / `@After` cleanup (reminder: engine teardown is `shutdown()` / `close()`, not `destroy()`)

## Reference

- `docs/testing.md` — full test guide
- `rules/testing.md` — testing discipline rules (JUnit 4, Turbine, ktor-client-mock, no MockK)
- Actual test count verified against real source: 259 @Test / 38 files / ~5,536 LOC
