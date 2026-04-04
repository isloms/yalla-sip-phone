# Yalla SIP Phone — Project Vision & Knowledge Base

## What This Is

Desktop VoIP softphone for Ildam (RoyalTaxi) call center operators. Connects to Oktell and Asterisk PBX via SIP. Built with Kotlin Compose Desktop + pjsip JNI. Target: thousands of operators at a billion-dollar company. Not a prototype.

## Current State: Phase 3 Complete

**Phase 1** (Registration) ✅ — SIP registration, credential persistence, navigation
**Phase 2** (Calling) ✅ — Outbound/inbound calls, answer, hangup, call timer, Space hotkey
**Phase 3** (Architecture Refactor) ✅ — Professional-grade decomposition, bug fixes, enterprise foundations

**Branch:** `feature/phase3-architecture` (based on `feature/sip-registration`)
**45 source files, 14 test files, ~3,756 lines total.**

## Test Environment

- Asterisk/Issabel PBX: `192.168.30.103:5060` (UDP/TCP)
- Public IP in SDP: `87.237.239.18` — **localnet fix needed on server**
- Test users: `101`, `102`, `103`
- Oktell PBX: `192.168.0.22:5060` (tested in Phase 1-2)
- pjsip source: `/Users/macbookpro/Ildam/pjproject/` (compiled without SDL2/FFmpeg/OpenSSL)

---

## Architecture (Phase 3)

```
Main.kt (Koin → SipStackLifecycle.initialize() → ComponentFactory → Compose Window)
├── di/
│   ├── SipModule.kt         (PjsipEngine → SipStackLifecycle + RegistrationEngine + CallEngine)
│   ├── SettingsModule.kt    (AppSettings)
│   └── FeatureModule.kt     (ComponentFactory)
├── domain/
│   ├── SipStackLifecycle.kt (initialize, shutdown)
│   ├── RegistrationEngine.kt (register, unregister, registrationState)
│   ├── CallEngine.kt        (makeCall, answerCall, hangupCall, toggleMute, toggleHold)
│   ├── CallState.kt         (Idle, Ringing{isOutbound}, Active{isMuted,isOnHold}, Ending)
│   ├── RegistrationState.kt (Idle, Registering, Registered, Failed{SipError})
│   ├── SipError.kt          (AuthFailed, NetworkError, ServerError, InternalError)
│   ├── SipConstants.kt      (all magic values, URI builders)
│   ├── SipCredentials.kt
│   └── CallerInfo.kt        (parseRemoteUri)
├── data/pjsip/
│   ├── PjsipEngine.kt       (thin facade — 95 lines, delegates to 3 managers)
│   ├── PjsipEndpointManager.kt (endpoint lifecycle, UDP+TCP transport, polling)
│   ├── PjsipAccountManager.kt  (registration, EBUSY fix, rate limiting, IncomingCallListener)
│   ├── PjsipCallManager.kt     (calls, mute fix, hold fix, audio routing, AccountProvider)
│   ├── PjsipAccount.kt      (SWIG Account wrapper → AccountManager)
│   ├── PjsipCall.kt         (SWIG Call wrapper → CallManager)
│   ├── PjsipLogWriter.kt    (pjsip → SLF4J bridge)
│   └── NativeLibraryLoader.kt (OS-specific native lib loading)
├── navigation/
│   ├── ComponentFactory.kt   (interface — scales to N screens)
│   ├── ComponentFactoryImpl.kt (Koin-backed)
│   ├── RootComponent.kt     (ComponentFactory, not N lambdas)
│   ├── RootContent.kt       (window resize from AppTokens)
│   └── Screen.kt
├── feature/
│   ├── registration/ (RegistrationComponent, RegistrationScreen, FormState)
│   └── dialer/ (DialerComponent, DialerScreen — horizontal panel, all call states)
├── ui/
│   ├── theme/ (MaterialKolor, AppTokens with shapes/windows/icons/animations)
│   ├── component/ (ConnectButton, ConnectionStatusCard, SipCredentialsForm)
│   └── strings/Strings.kt (i18n foundation — all UI strings extracted)
├── util/TimeFormat.kt
└── enterprise/ (stub interfaces)
    ├── ConnectionManager.kt  (auto-reconnect foundation)
    ├── AudioConfigEngine.kt  (device selection foundation)
    ├── CallQualityMonitor.kt (MOS/jitter/loss foundation)
    ├── TransportConfig.kt    (TLS/SRTP foundation)
    └── DesktopIntegration.kt (tray/notifications foundation)
```

## Known Issues

### Audio not working (SERVER-SIDE)
- **Root cause**: Asterisk SDP has `c=IN IP4 87.237.239.18` (public IP) instead of `192.168.30.103` (local)
- **RTP diagnostic**: `Stream: codec=PCMU/8000Hz, dir=3, remote=87.237.239.18:13486`
- **Fix needed**: Asterisk admin must add `localnet=192.168.30.0/24` and `localnet=192.168.60.0/24`
- **Our code is correct**: conference bridge routing verified (call=1, playback=0, capture=0)

### Mute/Hold — Fixed in Phase 3 but untested (blocked by audio)
- **Mute**: Changed from `adjustRxLevel(0/1)` to `stopTransmit/startTransmit` (reliable)
- **Hold**: Added `holdInProgress` guard (prevents PJ_EINVALIDOP on rapid clicks)

---

## Critical pjsip/SWIG Learnings

1. **NEVER delete() AudioMedia from getAudioMedia/getPlaybackDevMedia/getCaptureDevMedia** — managed by Call/AudDevManager. Deleting destroys audio routing.
2. **NEVER call libDestroy()** — triggers GC finalizers on unregistered thread → SIGSEGV.
3. **SWIG transient objects (configs, params, infos): delete() in try/finally, same scope.**
4. **SWIG long-lived objects (Account, Call): delete() by owning manager on lifecycle transitions.**
5. **Polling loop MUST yield()** after libHandleEvents — otherwise monopolizes single-thread dispatcher.
6. **threadCnt=0, mainThreadOnly=false** — all pjsip operations on our pjDispatcher.
7. **setRegistration(false) throws PJSIP_EBUSY** if mid-transaction — always catch.
8. **Destroy order**: call.hangup → call.delete → account.setRegistration(false) → delay(200ms) → account.delete → stopPolling → endpoint cleanup.
9. **Shutdown hook required** — Ctrl+C kills JVM without onCloseRequest, leaks server registration.
10. **Rate limit registration** — minimum 1s between attempts prevents 403 flood.

## Phased Roadmap

### Phase 4: Enterprise Reliability (NEXT)
- Auto-reconnect with exponential backoff
- TLS transport + SRTP media encryption
- Secure credential storage (macOS Keychain)
- Call quality monitoring (MOS, jitter, packet loss)
- Audio device selection UI
- System tray + desktop notifications

### Phase 5: Call Center Features
- DTMF, blind/attended transfer, 3-way conference
- Call recording, call history
- Multi-server failover, DNS SRV
- i18n (Uzbek, Russian, English)
- Auto-update, remote config
- CRM integration, agent status

### UI Redesign (Separate initiative)
- Professional call center softphone design
- Dark/light themes for long shifts
- Full keyboard-driven workflow
- Reference: Genesys, Five9, 3CX, Zoiper

---

## Specs & Plans

- `docs/superpowers/specs/2026-04-04-architecture-refactor-design.md` — Phase 3 architecture spec (enterprise gap analysis, 23 capabilities mapped)
- `docs/superpowers/plans/2026-04-04-phase3-architecture-refactor.md` — Phase 3 implementation plan (20 tasks, 3426 lines, all executed)
- `docs/superpowers/specs/2026-04-04-phase2-calling-design.md` — Phase 2 calling spec
- `docs/WINDOWS-BUILD.md` — Windows MSI build guide
