---
paths:
  - "**/src/test/**/*.kt"
---

# Testing Rules

This project has **259 @Test methods across 38 files** (~5,536 LOC of test code). Testing is a first-class concern here because PJSIP is unforgiving and manual smoke-testing is expensive (requires a working PBX).

## Framework Stack (Verified from build.gradle.kts)

- **JUnit 4 runtime** — via `kotlin("test")` and `compose.desktop.uiTestJUnit4`. **NOT JUnit 5.** There is no `@Nested`, `@BeforeEach`, `@AfterEach`, or `@Tag` in this codebase.
- **kotlin.test** — assertion library (`assertEquals`, `assertTrue`, `assertFailsWith`, etc.)
- **kotlinx.coroutines.test** — `runTest`, `TestDispatcher`, `advanceUntilIdle`
- **Turbine** (`app.cash.turbine:turbine:1.2.1`) — Flow testing
- **ktor-client-mock** (`io.ktor:ktor-client-mock:3.1.2`) — HTTP client mocking
- **compose.desktop.uiTestJUnit4** — Compose UI testing on JUnit 4

**Not used**: JUnit 5 (`org.junit.jupiter`), MockK, Jacoco, Mockito.

## Test Structure

Standard JUnit 4:

```kotlin
class SipAccountManagerTest {

    private val fakeSipEngine = FakeSipEngine()
    private val accountManager = PjsipSipAccountManager(fakeSipEngine)

    @Test
    fun `register - on success - state transitions to Registered`() = runTest {
        // Arrange
        fakeSipEngine.registerSucceeds()

        // Act
        accountManager.register(testAccount())

        // Assert
        assertEquals(RegistrationState.Registered, accountManager.state.value)
    }
}
```

Use backtick test names for readability. Group related tests by creating separate test classes (e.g., `SipAccountManagerRegistrationTest`, `SipAccountManagerCallTest`) rather than using JUnit 5's `@Nested`.

## Fakes vs Mocks

**This project uses hand-written fakes, not MockK.** There are zero MockK imports. Fakes are hand-written implementations of interfaces that keep explicit state.

```kotlin
class FakeCallEngine : CallEngine {
    private val registeredAccounts = mutableSetOf<AccountId>()
    var shouldSucceed = true

    override suspend fun register(account: Account): Result<Unit> {
        return if (shouldSucceed) {
            registeredAccounts += account.id
            Result.success(Unit)
        } else {
            Result.failure(RegistrationError("simulated failure"))
        }
    }
}
```

For HTTP clients, use **`ktor-client-mock`** (`MockEngine { request -> respond(...) }`).

For Flows / state emissions, use **Turbine**:
```kotlin
@Test
fun `state emits sequence on register`() = runTest {
    accountManager.state.test {
        assertEquals(RegistrationState.Idle, awaitItem())
        accountManager.register(testAccount())
        assertEquals(RegistrationState.Registering, awaitItem())
        assertEquals(RegistrationState.Registered, awaitItem())
    }
}
```

## PJSIP-Specific Testing

- **Never use a real `PjsipEngine` in unit tests.** Use a `FakeCallEngine` / `FakeSipEngine` implementing the same domain interface
- **Clean up**: tests that create real `PjsipEngine` (rare — mostly in `integration/`) must call `engine.shutdown()` in teardown to avoid leaking the dispatcher thread
- **Integration tests** under `src/test/kotlin/uz/yalla/sipphone/integration/` (`BridgeIntegrationTest`, `BusyOperatorIntegrationTest`, `CallFlowIntegrationTest`) use fakes, not real PJSIP — they're safe to run in the default suite
- **Tag-based exclusion is NOT wired up** in this project. `-PincludeTags=integration` / `-PexcludeTags=integration` are silently ignored. Use `--tests "*IntegrationTest"` to run integration tests alone

## Coroutine Testing

- Use `runTest { ... }` for tests involving suspend functions
- Inject a `TestDispatcher` into production code via DI — never hardcode `Dispatchers.Default` or `Dispatchers.IO`
- Use `advanceUntilIdle()` instead of arbitrary `delay()`
- For Flows, use Turbine or collect into a list

## Tests That Must Exist

- **Unit tests**: every public function on every production class
- **State machine tests**: every `ViewModel` / Decompose component / `AccountManager` / `LoginFlow` — test every intent → state transition
- **Error handling**: every `Result.failure` branch must have a test
- **Concurrency**: anything with `AtomicBoolean`, `Mutex`, or `Channel` needs a concurrency test

## Tests That Should NOT Exist

- Tests that verify private implementation details (test through public API)
- Tests that assert on log messages (brittle)
- Tests that depend on real time (use `TestScheduler`)
- Tests that use `Thread.sleep()` — always a bug

## Running Tests

```bash
./gradlew test                                     # all 259 tests
./gradlew test --tests "SipAccountManagerTest"     # single class
./gradlew test --tests "*RegistrationFailure*"     # pattern
./gradlew test --tests "*IntegrationTest"          # only integration tests (they use fakes)
```

**NOT supported** (fabricated in earlier doc revisions — ignore):
- `-PincludeTags=integration` / `-PexcludeTags=integration`
- `-PciMode=true`
- `-PtestLogging=full`
- `./gradlew test jacocoTestReport`

To add coverage: install the Jacoco plugin in a dedicated commit. It's not configured today.

## Reference

- `docs/testing.md` — full test guide with per-area counts
- Framework per `build.gradle.kts`: kotlin.test (JUnit 4), kotlinx-coroutines-test 1.10.1, compose.desktop.uiTestJUnit4, turbine 1.2.1, ktor-client-mock 3.1.2
