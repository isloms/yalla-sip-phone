---
paths:
  - "**/data/pjsip/**/*.kt"
---

# SWIG Interop Rules

SWIG-generated Kotlin bindings ship as a prebuilt jar: `libs/pjsua2.jar` (package `org.pjsip.pjsua2.*`). There is no SWIG source under `src/main/kotlin/` — the bindings are binary-imported. This rule file applies to the Kotlin wrappers in `data/pjsip/` that consume those bindings.

The generated code is NOT idiomatic Kotlin, and it leaks C semantics into the Kotlin world. These rules keep the leak contained.

## Lifecycle

Every SWIG-generated object backs a C resource. Failure to dispose correctly leaks C memory.

- **Every SWIG object gets a scoped wrapper** in `data/pjsip/` — never pass raw SWIG objects to feature code or domain code
- **Wrappers hold `AtomicBoolean deleted`** (for account/call) or reference `isDestroyed()` (for sub-managers that read the engine's flag) — see `rules/pjsip-threading.md`
- **`destroy()` must be idempotent**: `compareAndSet(false, true)` before doing C cleanup
- **Engine teardown is `shutdown()` / `close()`**, not `destroy()`
- **Finalizers are not enough**: rely on explicit `destroy()` / `shutdown()` called from `pjDispatcher`

## Callback Safety

SWIG callbacks (overridden `onXxx` functions inheriting from `org.pjsip.pjsua2.*` base classes) are called from C code on the `pjDispatcher` thread.

### Rules

1. **Never block inside a callback**. C is waiting for you. Long work → snapshot state, dispatch to Kotlin layer via a Channel or MutableStateFlow
2. **Snapshot before escape**: copy every parameter field to Kotlin primitives before any suspension point
3. **Never call back into the same SWIG object synchronously from a callback** — reentry can corrupt state
4. **Never throw exceptions from a callback** — C cannot unwind. Catch everything, log, fail safely

### Safe Callback Template

```kotlin
override fun onIncomingCall(prm: OnIncomingCallParam) {
    try {
        // 1. synchronous snapshot
        val callId = prm.callId
        val srcUri = prm.srcAddress.toString()
        val timestamp = System.currentTimeMillis()

        // 2. dispatch to higher-level handler via a channel
        incomingCallChannel.trySend(IncomingCall(callId, srcUri, timestamp))
    } catch (t: Throwable) {
        // 3. never let C see an exception
        logger.error(t) { "Crash in onIncomingCall" }
    }
}
```

## Memory Ownership

- Objects returned from SWIG functions: check per-call pjsip docs. Often you own them and must dispose
- Objects passed to SWIG functions: ownership may transfer (check docs)
- Strings from SWIG: ALWAYS copy via `.toString()` before escape. `pj_str_t`-backed strings invalidate with the underlying C buffer

## Logging

- Logging from callbacks is allowed but must be cheap (no formatting of large structures)
- Use `Level.ERROR` sparingly — callback errors often mean the C side already succeeded, and you'll spam logs

## Testing

SWIG types are hard to mock. Prefer fakes:
- Wrap SWIG objects in an interface (e.g., `CallEngine` in `domain/`)
- Implement `FakeCallEngine` for tests using pure Kotlin state
- Use real SWIG only in integration tests that run the full PJSIP event loop

## When You Modify pjsip Source

`~/Ildam/pjproject/` is the PJSIP source. We compile it and package the bindings into `libs/pjsua2.jar`. Do NOT modify pjproject from here — it's upstream code. If a behavior change is needed:
1. Open an issue upstream (or in our fork)
2. Build a workaround in the Kotlin wrapper layer in `data/pjsip/`
3. Only touch `pjproject/` if absolutely unavoidable, and document why in an ADR

## Review Checklist

- [ ] Every SWIG object has a Kotlin wrapper in `data/pjsip/`
- [ ] Wrapper has lifecycle discipline (`deleted` or `isDestroyed()` gate, idempotent `destroy()`/`shutdown()`)
- [ ] Callbacks snapshot all SWIG params before suspension
- [ ] Callbacks never throw
- [ ] Tests use fakes, not real SWIG, where possible
