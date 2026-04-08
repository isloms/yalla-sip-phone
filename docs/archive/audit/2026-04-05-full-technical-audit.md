# Yalla SIP Phone — Full Technical Audit Report
**Sana:** 2026-04-05
**Audit team:** Architect (Opus) + Developer (Sonnet) + Critic (Sonnet)
**Scope:** 96 fayl, 7,431 qator, barcha layerlar

---

## Executive Summary

Arxitektura fundamenti **YAXSHI** — domain abstraction, pjsip thread confinement, bridge protocol, sealed state modeling. Lekin **3 ta CRITICAL crash bug**, **12 ta HIGH severity muammo**, va **10 ta production-da yo'q bo'lgan feature** topildi.

| Severity | Soni | Tavsif |
|----------|------|--------|
| CRITICAL | 9 | Production crash, data loss, security breach |
| HIGH | 12 | Reliability, correctness, resource leak |
| MEDIUM | 10+ | Code quality, maintainability |
| LOW | 8+ | Style, conventions, dead code |
| Test gaps | 7 | Untested critical paths |

---

## 1. CRITICAL Issues (Bugun fix qilish kerak)

### C1. `endpoint.libDestroy()` CHAQIRILMAYDI — 9 ta SIGSEGV sababi
**File:** `PjsipEndpointManager.kt:94-97`
**Impact:** 9 ta crash dump (`hs_err_pid*.log`) project root da
**Muammo:** `destroy()` faqat scope cancel qiladi. GC finalizer SWIG destructor ni unregistered thread dan chaqiradi → SIGSEGV.
**Fix:** `pjDispatcher` da `Runtime.gc()` → `endpoint.libDestroy(PJSUA_DESTROY_NO_RX_MSG)` → `endpoint.delete()`

### C2. `MockAuthRepository` production da
**File:** `AuthModule.kt`, `MockAuthRepository.kt`
**Impact:** Hardcoded credentials (`test123` / `1234qwerQQ`), real auth yo'q
**Fix:** Real `AuthRepository` (Ktor-based) implement qilish

### C3. JCEF debug port 9222 ochiq
**File:** `JcefManager.kt`
**Impact:** LAN dagi har kim DevTools orqali browser ni boshqaradi
**Fix:** `debugPort = 0` yoki production da disable

### C4. `currentAuthResult!!` force unwrap — NPE crash
**File:** `RootComponent.kt:43`
**Impact:** Decompose stack restore da `null` bo'lishi mumkin → app crash
**Fix:** Null check + Login ga redirect

### C5. `holdInProgress` success da reset qilinmaydi
**File:** `PjsipCallManager.kt:141-167`
**Impact:** Media callback kelmasa hold button abadiy blocked
**Fix:** try/finally + timeout fallback

### C6. `enumDev2()` loop — SWIG native leak
**File:** `PjsipEndpointManager.kt:99-106`
**Impact:** Har iteratsiyada yangi native collection, hech biri delete qilinmaydi
**Fix:** `val devices = adm.enumDev2()` bir marta, `try/finally { devices.delete() }`

### C7. `BridgeRouter.scope` cancel qilinmaydi
**File:** `BridgeRouter.kt:35`
**Impact:** Login/logout cycle da coroutine leak, events yo'qolishi
**Fix:** `dispose()` da `scope.cancel()` qo'shish

### C8. `ToolbarComponent.scope` lifecycle leak
**File:** `ToolbarComponent.kt:35`
**Impact:** Component destroy bo'lganda scope to'xtamaydi
**Fix:** Decompose `coroutineScope()` ishlatish yoki explicit cancel

### C9. `SipCredentials` data class — `toString()` parolni expose qiladi
**File:** `SipCredentials.kt`
**Impact:** Logger ga tushsa password ko'rinadi
**Fix:** `toString()` override, password mask

---

## 2. HIGH Issues

### H1. `Ending` state da `onCallConfirmed` kelsa → Active ga o'tadi
**File:** `PjsipCallManager.kt`
**State machine bug:** hangup dan keyin server CONFIRMED yuborsa, call Active ga qaytadi

### H2. `hangupCall` timeout yo'q — eternal Ending state
**File:** `PjsipCallManager.kt`
**Impact:** Network muammosida operator stuck bo'ladi, yangi call qila olmaydi

### H3. Re-registration da use-after-free
**File:** `PjsipAccountManager.kt:81-92`
**Impact:** `withTimeoutOrNull` expire bo'lsa, `prevAccount.delete()` native thread hali ishlatayotganda chaqiriladi

### H4. Shutdown hook EDT deadlock
**File:** `Main.kt:63-68`
**Impact:** `jcefManager.shutdown()` da `invokeAndWait` + EDT blocked = hang

### H5. macOS microphone permission so'ralmaydi
**Impact:** Yangi Mac da micro ishlamaydi, sabab ko'rsatilmaydi

### H6. SIP URI injection — server field validatsiya yo'q
**File:** `SipConstants.kt:31-35`
**Impact:** `"evil.com\r\nVia: SIP/2.0/UDP attacker.com"` → SIP header injection

### H7. `lastAuthResult!!` race condition
**File:** `LoginComponent.kt:49`
**Impact:** `@Volatile` yo'q, boshqa thread dan yozilishi mumkin

### H8. `osascript` process leak
**File:** `ToolbarComponent.kt:119-129`
**Impact:** `.waitFor()` yo'q, har incoming call da zombie process

### H9. `PjsipCall.delete()` double-free xavfi
**File:** `PjsipCallManager.kt:253-260`
**Impact:** Reject + disconnect callback = double delete → heap corruption

### H10. Browser cleanup yo'q
**File:** `WebviewPanel.kt:15-17`
**Impact:** `DisposableEffect` yo'q, URL o'zgarganda eski browser leak

### H11. JCEF + Compose init order
**Impact:** Compose window dan OLDIN JCEF init = resize crash potential

### H12. Double shutdown race
**Impact:** `onCloseRequest` + shutdown hook ikkalasi `lifecycle.shutdown()` chaqiradi

---

## 3. Industry Gap Analysis

### Nimani TO'G'RI qilyapmiz (10 ta)
1. pjsip single-thread dispatcher (industry best practice)
2. Domain layer abstraction (CallEngine, RegistrationEngine)
3. SWIG object lifecycle management (try/finally delete)
4. Bridge protocol (typed events, audit log, rate limiting, handshake)
5. Phone number masking (security)
6. Sealed class state modeling (compile-time safety)
7. Shutdown hook (SIP UNREGISTER on kill)
8. Design system (YallaColors, AppTokens, Strings)
9. Decompose navigation (latest stable)
10. Test foundation (23 test files, fake engines)

### Nimani NOTO'G'RI qilyapmiz (7 ta)
1. `libDestroy()` chaqirmaslik → native crash
2. JCEF init order → potential crash
3. ToolbarComponent lifecycle → memory leak
4. Koin DI bypass → testability buzildi
5. MVI framework yo'q (manual StateFlow) → boilerplate
6. Auto-reconnect yo'q → production unacceptable
7. Error states too coarse (`SipError.ServerError` catches 400-599)

### Production uchun BUTUNLAY YO'Q (10 ta critical feature)

| Feature | Priority | Effort | Nima uchun kerak |
|---------|----------|--------|------------------|
| Auto-reconnect | P0 | 1 day | Network blip da agent disconnect bo'ladi |
| DTMF | P0 | 4h | IVR, bank, automated systems |
| Blind transfer | P0 | 4h | Supervisor ga qo'ng'iroq o'tkazish |
| Audio device selection | P0 | 1 day | Headset tanlash |
| TLS/SRTP | P0 | 1 day | Signaling + audio encryption |
| Auto-answer | P1 | 4h | Agent Ready da avtomatik javob |
| Multiple concurrent calls | P1 | 2 days | Hold/switch between calls |
| Attended transfer | P1 | 1 day | Professional transfer |
| Call quality monitoring (real MOS) | P1 | 1 day | Quality visibility |
| Call history / CDR | P1 | 1 day | Agent accountability |

---

## 4. Test Coverage

### Tested ✅
- CallerInfo parsing
- SipError mapping
- TimeFormat
- PhoneNumber validation/masking
- RegistrationComponent (happy path + double connect)
- DialerComponent (happy path + error)
- LoginComponent
- BridgeSecurity, BridgeProtocol, BridgeAuditLog
- FakeCallEngine, FakeRegistrationEngine
- AgentStatus

### NOT Tested ❌ (critical gaps)
- **PjsipEngine, PjsipCallManager, PjsipAccountManager** — ZERO tests
- **BridgeRouter dispatch** — ZERO tests
- **MainComponent** — ZERO tests (call state → bridge event mapping)
- **Ending state + onCallConfirmed** — state machine bug untested
- **holdInProgress timeout** — untested
- **hangupCall disconnect timeout** — untested
- **Rapid register/unregister/register** — race condition untested
- **MockAuthRepositoryTest** — BROKEN (assertion mismatch: expects 192.168.30.103, actual 192.168.0.22)
- **AppSettingsTest** — empty test body, asserts nothing

---

## 5. Prioritized Action Plan

### Phase A: CRASH FIXES (1-2 kun)
```
A1. endpoint.libDestroy() fix                    [2h]  → 9 SIGSEGV crash to'xtaydi
A2. currentAuthResult!! null safety               [30m] → NPE crash fix
A3. holdInProgress reset fix                      [30m] → Hold button stuck fix
A4. enumDev2() SWIG leak fix                      [30m] → Native memory leak fix
A5. BridgeRouter.scope + ToolbarComponent.scope   [1h]  → Coroutine leak fix
A6. hangupCall timeout + Ending state guard       [1h]  → Eternal stuck fix
A7. JCEF debug port disable                       [15m] → Security fix
A8. SipCredentials.toString() mask                [15m] → Password exposure fix
```
**Total: ~6 soat**

### Phase B: RELIABILITY (1 hafta)
```
B1. Auto-reconnect (ConnectionManager impl)      [1d]
B2. Shutdown race fix (double shutdown guard)     [2h]
B3. Re-registration use-after-free fix            [2h]
B4. Browser lifecycle (DisposableEffect)          [1h]
B5. JCEF init order fix                           [2h]
B6. SIP URI validation                            [2h]
B7. MainComponent DI fix (Koin inject)            [30m]
B8. Broken tests fix + critical test coverage     [1d]
```

### Phase C: PRODUCTION FEATURES (2 hafta)
```
C1. DTMF support                                 [4h]
C2. Blind call transfer                           [4h]
C3. Audio device selection                        [1d]
C4. Auto-answer (configurable)                    [4h]
C5. TLS transport + SRTP                          [1d]
C6. Real call quality monitoring                  [1d]
C7. Multiple concurrent calls                     [2d]
C8. Attended transfer                             [1d]
```

### Phase D: POLISH (2 hafta)
```
D1. Secure credential storage (Keychain/DPAPI)   [1d]
D2. Call history / CDR                            [1d]
D3. System tray + OS notifications                [4h]
D4. i18n (Uzbek, Russian, English)                [1d]
D5. macOS microphone permission handling          [2h]
D6. Dead code cleanup (enterprise stubs, old screens) [2h]
D7. Error classification improvement (SipError)   [2h]
```

---

## Sources
- [PJSIP Thread Safety #2189](https://github.com/pjsip/pjproject/issues/2189)
- [PJSIP Endpoint Documentation](https://docs.pjsip.org/en/latest/pjsua2/using/endpoint.html)
- [pjsua_destroy() Crash #2372](https://github.com/pjsip/pjproject/issues/2372)
- [SwingPanel Always on Top #3739](https://github.com/JetBrains/compose-multiplatform/issues/3739)
- [JCEF + Compose Resize Crash #2939](https://github.com/JetBrains/compose-multiplatform/issues/2939)
- [Linphone Desktop](https://github.com/BelledonneCommunications/linphone-desktop)
- [JxBrowser vs JCEF](https://teamdev.com/jxbrowser/blog/jxbrowser-and-jcef/)
- [PJSIP TCP Keep-Alive](https://trac.pjsip.org/repos/ticket/95)
