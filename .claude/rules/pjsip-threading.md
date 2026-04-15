---
paths:
  - "**/data/pjsip/**/*.kt"
  - "**/PjsipEngine.kt"
  - "**/PjsipAccount.kt"
  - "**/PjsipCall.kt"
  - "**/PjsipAccountManager.kt"
  - "**/PjsipCallManager.kt"
  - "**/PjsipEndpointManager.kt"
  - "**/PjsipSipAccountManager.kt"
  - "**/SipAccountManager.kt"
---

# PJSIP Threading Rules

**This is the most dangerous area of the codebase. Read carefully before editing.**

PJSIP code lives in `src/main/kotlin/uz/yalla/sipphone/data/pjsip/`. `SipAccountManager` is a **domain interface** in `domain/`; its production implementation is `PjsipSipAccountManager` in `data/pjsip/`.

## The Core Constraint

PJSIP C library is NOT thread-safe across arbitrary threads. It has a single event loop thread (`libHandleEvents`). All PJSIP API calls and all PJSIP callbacks MUST happen on that same thread, or you get crashes ranging from silent memory corruption to instant SIGSEGV.

## How We Enforce This

`PjsipEngine.kt` owns:

```kotlin
private val closeableDispatcher = newSingleThreadContext("pjsip-event-loop")
val pjDispatcher: CoroutineDispatcher get() = closeableDispatcher
```

This is a single-threaded coroutine dispatcher. All public API on `PjsipEngine`, `PjsipAccount`, `PjsipCall`, `PjsipAccountManager`, `PjsipCallManager`, `PjsipEndpointManager`, and `PjsipSipAccountManager` must wrap their work in `withContext(pjDispatcher) { ... }`.

## SWIG Pointer Invalidation — The Big Trap

SWIG-generated Kotlin bindings (from `libs/pjsua2.jar`, package `org.pjsip.pjsua2.*`) wrap C pointers. These pointers are **valid only within the scope of the callback that delivered them**. Once the C callback returns, the pointer is freed or reused.

### Safe Pattern

Snapshot everything you need from SWIG parameters into plain Kotlin types, synchronously, before the callback returns:

```kotlin
override fun onRegState(prm: OnRegStateParam) {
    val code = prm.code
    val reason = prm.reason.toString()
    val accountId = currentAccountId
    // Now every variable is a plain Kotlin value. Safe to use anywhere.
    scope.launch {
        handleRegState(accountId, code, reason)
    }
}
```

### Dangerous Pattern (causes SIGSEGV)

```kotlin
override fun onRegState(prm: OnRegStateParam) {
    scope.launch {
        delay(100)
        val code = prm.code    // prm is already freed. CRASH.
    }
}
```

### Rule

**Never let a SWIG-typed variable escape the callback synchronously.** If you need it later, copy its fields to Kotlin types first.

## Lifecycle and Gates

Different objects use slightly different gate variable names — respect them:

- **`PjsipEngine`** uses `private val destroyed = AtomicBoolean(false)` + `private fun isDestroyed(): Boolean`. The `isDestroyed` lambda is passed by reference (as `() -> Boolean`) to `PjsipAccountManager`, `PjsipCallManager`, and `PjsipEndpointManager` so sub-managers can check engine liveness without holding a hard reference.
- **`PjsipAccount`** and **`PjsipCall`** each use `private val deleted = AtomicBoolean(false)` for per-object lifecycle.

Example pattern (from the real code):

```kotlin
class PjsipAccount(
    private val isEngineDestroyed: () -> Boolean,
    // ...
) {
    private val deleted = AtomicBoolean(false)

    suspend fun makeCall(...): Result<PjsipCall> {
        if (deleted.get()) return Result.failure(AccountDeleted())
        if (isEngineDestroyed()) return Result.failure(EngineDestroyed())
        return withContext(pjDispatcher) {
            if (deleted.get()) return@withContext Result.failure(AccountDeleted())
            // safe to touch C handles
        }
    }

    suspend fun destroy() = withContext(pjDispatcher) {
        if (deleted.compareAndSet(false, true)) {
            // actual C cleanup
        }
    }
}
```

### Rules

- **Engine teardown is `shutdown()` / `close()`**, NOT `destroy()`. Per-object wrappers (`PjsipAccount`, `PjsipCall`) use `destroy()`
- **Check `deleted` (or `isDestroyed()` for engine) BOTH before and after `withContext` switches.** Another coroutine might have destroyed the object between the check and the dispatcher switch
- **`destroy()` / `shutdown()` must be idempotent** via `compareAndSet(false, true)`

## What Was Abandoned

An earlier plan said "marshal callbacks to pjDispatcher via launch" — abandoned because SWIG pointers invalidate after the callback returns. The current design runs callbacks synchronously on the same thread that dispatches, which works because `libHandleEvents` is dispatched via `pjDispatcher`.

## Testing PJSIP Code

- Use fakes, not mocks, where possible — SWIG types (from `libs/pjsua2.jar`) are awkward to mock
- Any test that creates a real `PjsipEngine` must call `engine.shutdown()` or `engine.close()` in test teardown to avoid leaking the dispatcher thread
- Concurrency tests must use a controllable test dispatcher from `kotlinx.coroutines.test`, never `Dispatchers.Default`

## Review Checklist for PJSIP Changes

Before approving any PJSIP-related PR:

- [ ] Every public API entry point uses `withContext(pjDispatcher)`
- [ ] Every callback snapshots SWIG fields to Kotlin types before any `launch`/`delay`/`yield`
- [ ] Lifecycle objects check their gate (`deleted` for account/call, `isDestroyed()` for engine) before and after dispatcher switches
- [ ] No `Dispatchers.IO` or `Dispatchers.Default` touches SWIG handles
- [ ] `destroy()` / `shutdown()` are idempotent via `compareAndSet(false, true)`
- [ ] Tests don't leak `pjDispatcher` threads

## Reference

Full PJSIP guide: `docs/pjsip-guide.md` (up to date).
PJSIP upstream coding style: https://docs.pjsip.org/en/latest/get-started/coding-style.html
