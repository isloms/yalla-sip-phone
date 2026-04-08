# pjsip/SWIG Guide

Critical rules and hard-won learnings for working with pjsip via SWIG/JNI in this project. **Read this before touching any code in `data/pjsip/`.**

## Threading Model

All pjsip operations MUST run on a single-thread dispatcher (`pjDispatcher`). pjsip is not thread-safe.

```kotlin
// Correct — all pjsip calls on pjDispatcher
private val pjDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

suspend fun makeCall(number: String) = withContext(pjDispatcher) {
    call.makeCall(uri, prm)
}
```

Configuration: `threadCnt = 0`, `mainThreadOnly = false` — we handle all threading ourselves.

## SWIG Object Lifecycle

### Rule 1: NEVER delete() AudioMedia from getAudioMedia/getPlaybackDevMedia/getCaptureDevMedia

These return references managed by Call/AudDevManager. Deleting them destroys audio routing for the entire endpoint.

```kotlin
// WRONG — will break all audio
val media = call.getAudioMedia(0)
media.delete()  // NEVER DO THIS

// Correct — just use it, don't delete
val media = call.getAudioMedia(0)
media.startTransmit(playbackDev)
```

### Rule 2: NEVER call libDestroy() from a random thread

GC finalizers run on unregistered threads. If SWIG destructor calls pjsip from a GC thread → SIGSEGV.

```kotlin
// Correct shutdown sequence on pjDispatcher:
Runtime.getRuntime().gc()  // force GC before destroy
endpoint.libDestroy(pjsua_destroy_flag.PJSUA_DESTROY_NO_RX_MSG)
endpoint.delete()
```

### Rule 3: Transient SWIG objects — delete() in try/finally, same scope

Configs, params, infos — create, use, delete immediately.

```kotlin
val prm = CallOpParam(true)
try {
    call.answer(prm)
} finally {
    prm.delete()
}
```

### Rule 4: Long-lived SWIG objects — delete() by owning manager

Account and Call objects are managed by their respective managers on lifecycle transitions.

### Rule 5: Polling loop MUST yield()

After `libHandleEvents()`, call `yield()` — otherwise the single-thread dispatcher is monopolized and no other coroutine can run.

```kotlin
while (isActive) {
    endpoint.libHandleEvents(POLL_INTERVAL_MS)
    yield()  // CRITICAL
}
```

## Destroy Order

Order matters. Wrong order = SIGSEGV or resource leaks.

```
1. call.hangup(200)          → SIP BYE
2. call.delete()             → release native Call
3. account.setRegistration(false)  → SIP UNREGISTER
4. delay(200ms)              → let server process UNREGISTER
5. account.delete()          → release native Account
6. stopPolling()             → stop libHandleEvents loop
7. endpoint.libDestroy()     → full native cleanup
8. endpoint.delete()         → release native Endpoint
```

**logWriter.delete() AFTER libDestroy()**, not before — pjsip writes logs during shutdown.

## Common Pitfalls

### setRegistration(false) throws PJSIP_EBUSY

If a registration transaction is in progress, `setRegistration(false)` throws. Always catch:

```kotlin
try {
    account.setRegistration(false)
} catch (e: Exception) {
    logger.warn { "Unregister failed (likely mid-transaction): ${e.message}" }
}
```

### Rate-limit registration

Minimum 1 second between registration attempts. Rapid re-registration causes 403 floods from the server.

### PjsipCall.delete() double-free

If both reject and disconnect callbacks fire, `delete()` can be called twice → heap corruption. Use `AtomicBoolean` guard:

```kotlin
private val deleted = AtomicBoolean(false)

fun safeDelete() {
    if (deleted.compareAndSet(false, true)) {
        delete()
    }
}
```

### Ending state + onCallConfirmed race

After hangup, the server may still send CONFIRMED. Guard against transitioning back to Active from Ending state.

## Transport Configuration

Current setup: UDP + TCP transports, no TLS.

```kotlin
fun createTransports() {
    val config = TransportConfig()
    try {
        config.port = 0  // OS-assigned port
        endpoint.transportCreate(PJSIP_TRANSPORT_UDP, config)
        endpoint.transportCreate(PJSIP_TRANSPORT_TCP, config)
    } finally {
        config.delete()
    }
}
```

TLS transport is planned but requires OpenSSL-enabled pjsip build (currently compiled without).

## Audio Routing

Mute and hold use different mechanisms:

| Operation | Implementation | Why |
|-----------|---------------|-----|
| **Mute** | `stopTransmit()` / `startTransmit()` on capture device | Reliable — directly controls media flow |
| **Hold** | `call.setHold()` / `call.reinvite(PJSUA_CALL_UNHOLD)` | SIP standard — sends re-INVITE with `sendonly`/`sendrecv` |

Previous mute via `adjustRxLevel(0/1)` was unreliable and was replaced with `stopTransmit/startTransmit`.

## Native Library Loading

`NativeLibraryLoader` handles OS detection and loads the correct native library:

| OS | Library | Location |
|----|---------|----------|
| macOS | `libpjsua2.jnilib` | `libs/` or app-resources |
| Windows | `pjsua2.dll` | `libs/` or app-resources |
| Linux | `libpjsua2.so` | `libs/` or app-resources |

In dev mode, `pjsip.library.path` system property points to `libs/`. In packaged mode, native libs are in app-resources.

## pjsip Build Notes

Current build: pjsip 2.16, compiled **without** OpenSSL, SDL2, or FFmpeg.

```c
// pjlib/include/pj/config_site.h
#define PJ_HAS_SSL_SOCK 0
#define PJMEDIA_HAS_VIDEO 0
```

Source: `/Users/macbookpro/Ildam/pjproject/`

See [windows-build.md](windows-build.md) for Windows-specific compilation instructions.

## Test Environment

| PBX | Address | Transport | Notes |
|-----|---------|-----------|-------|
| Asterisk/Issabel | `192.168.30.103:5060` | UDP/TCP | localnet fix needed for audio |
| Oktell | `192.168.0.22:5060` | UDP | Production PBX |

Test extensions: `101`, `102`, `103`

### Known Audio Issue (Server-Side)

Asterisk SDP returns public IP (`87.237.239.18`) instead of LAN IP in `c=` line. RTP goes to wrong address. **Fix needed on Asterisk**: add `localnet=192.168.30.0/24` and `localnet=192.168.60.0/24` to pjsip config.
