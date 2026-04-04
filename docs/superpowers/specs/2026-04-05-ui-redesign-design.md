# UI Redesign — Call Center Workstation MVP

**Date**: 2026-04-05
**Scope**: Full UI redesign — login screen, toolbar + webview main screen, JCEF integration, JS bridge, desktop features, Yalla brand theme
**Target**: Production-grade call center workstation for Ildam operators

---

## 1. Product Vision

Transform the SIP phone prototype into a **call center workstation** — a single-window desktop app where operators manage SIP calls via a toolbar while using a dispatcher web panel in an embedded browser. No alt-tab, no separate browser tabs.

**Mental model**: Electron-style app — toolbar (native) + fullscreen webview (dispatcher React app). Like Android Studio's tool windows, but for call center operators.

**Target hardware**: 22-24" monitors, 1920x1080 (HD). Operators work 8-12 hour shifts.

---

## 2. Application Flow

```
┌─────────────────┐
│   Login Screen   │  Password input (Telegram Desktop style)
│   (420x520)      │  Centered, not resizable
└────────┬────────┘
         │ password → Mock Backend API
         │ ← SIP credentials + dispatcher URL + agent info
         │ Auto SIP register
         ▼
┌─────────────────────────────────────────┐
│  Fixed Toolbar (56px)                    │  Always on top
├─────────────────────────────────────────┤  Maximize default
│                                         │  No minimize
│  JCEF Webview — dispatcher URL          │  Close = logout
│  No browser chrome                      │
│  JS Bridge (bidirectional)              │
│                                         │
└─────────────────────────────────────────┘
         │ SIP disconnect?
         │ Auto-reconnect (5 attempts, exponential backoff)
         │ All failed?
         ▼
┌─────────────────┐
│   Login Screen   │  "Connection lost, please re-login"
└─────────────────┘
```

**3 screens**: Login, Main (toolbar + webview), Login (reconnect failure).

---

## 3. Login Screen

### Layout

```
┌─────────────────────────────────────┐
│                                     │
│          [Yalla Logo]               │
│        Yalla SIP Phone              │
│                                     │
│   ┌─────────────────────────────┐   │
│   │ 🔒  Password                │   │
│   └─────────────────────────────┘   │
│                                     │
│   ┌─────────────────────────────┐   │
│   │         Login               │   │
│   └─────────────────────────────┘   │
│                                     │
│     Manual connection ▸             │
│                                     │
│              v1.0.0                 │
└─────────────────────────────────────┘
```

### Specs

| Property | Value |
|----------|-------|
| Window size | 420x520dp, centered, not resizable |
| Password field | Any characters, visibility toggle (eye icon) |
| Submit | Login button + Enter key |
| Loading state | Button shows spinner, field disabled |
| Error state | Red text below field, field border red |
| Success | Auto-transition to main screen |
| Manual connection | Expandable section — SIP form (server, port, user, pass) for backend failure fallback |
| Version | Bottom center, dim text, app version |
| Remember me | None — password entered every session |

### Mock Backend

```kotlin
interface AuthApi {
    suspend fun login(password: String): Result<LoginResponse>
}

data class LoginResponse(
    val sipServer: String,        // "192.168.30.103"
    val sipPort: Int,             // 5060
    val sipUsername: String,       // "101"
    val sipPassword: String,      // "secret"
    val sipTransport: String,     // "UDP"
    val dispatcherUrl: String,    // "https://dispatcher.yalla.uz/panel"
    val agentId: String,          // "agent-042"
    val agentName: String         // "Alisher"
)
```

Mock implementation: password `"test123"` → success, otherwise → `AuthException`.

---

## 4. Main Screen — Toolbar

### Fixed Layout (56px height, never changes)

```
┌────────────────────────────────────────────────────────────────────────┐
│ [A: 48px+] │  [B: flexible]                    │ [C: 200px] │[D]│[E] │
│ 🟢 Ready ▾ │  [ +998 90 123 45 67    ] [📞]    │            │ ⚙ │ 🟢│
└────────────────────────────────────────────────────────────────────────┘
```

### Zone A — Agent Status (min 48px)

| Element | Detail |
|---------|--------|
| Indicator | Colored dot (8dp) |
| Text | Status name |
| Dropdown arrow | ▾ |
| Interaction | Click → dropdown menu |

**Dropdown options:**

| Status | Color | Icon |
|--------|-------|------|
| Ready | `#2E7D32` green | 🟢 |
| Away | `#F59E0B` amber | 🟡 |
| Break | `#F97316` orange | 🟠 |
| Offline | `#98A2B3` gray | ⚪ |

Dropdown closes on selection or outside click.

### Zone B — Phone Input / Call Info (flexible width)

**Per call state:**

| State | Zone B Content |
|-------|----------------|
| Idle | Editable phone input field + `[📞 Call]` primary button |
| Incoming | Number (disabled, bold) + `[✓ Answer]` green filled + `[✕ Reject]` outlined red |
| Active | Number (disabled) + `[00:42]` timer (monospace, replaces Call button position) |
| On Hold | Number (disabled) + `[00:42]` timer (dim/gray, paused) |

- **Idle → Incoming**: phone input becomes disabled, shows caller number, Call button swaps to Answer+Reject
- **Incoming → Active**: Answer+Reject swap to timer
- **Active → Idle**: instant transition, no "ending" state visible
- **Outbound call**: operator types number, presses Call, immediately shows number (disabled) + timer
- If SIP backend is slow (1-2s), Zone C controls show disabled End button until idle

### Zone C — Call Controls (200px)

| State | Controls |
|-------|----------|
| Idle | Invisible (space reserved, layout stable) |
| Incoming | _(controls in Zone B for incoming)_ |
| Active | `[🔇 Mute]` `[⏸ Hold]` `[📞 End]` |
| On Hold | `[🔇 Mute]` `[▶ Resume]` `[📞 End]` |

- **Mute active**: button tinted red (`#F42500`)
- **Hold → Resume**: Hold button icon/label changes, same position
- All buttons: icon + label, fixed size, fixed position
- Controls use `visibility: invisible` pattern — space always reserved, no layout shift

### Zone D — Settings (40px)

- Gear icon ⚙
- Click → settings popover (see Section 8)

### Zone E — Call Quality (32px)

- Visible only during active call
- Colored dot: green (`MOS > 3.5`) / yellow (`2.5-3.5`) / red (`< 2.5`)
- Hover tooltip: "Call Quality: Good"
- Invisible when idle (space reserved)

---

## 5. Toolbar Behavior — Key Principles

1. **Fixed height forever** — 56px, no expand, no shrink, no animation
2. **Zone positions fixed** — operator builds muscle memory
3. **Content swaps, layout doesn't** — `visibility invisible` pattern
4. **No "ending" state UI** — call ends → instant idle transition
5. **Slow disconnect fallback** — if > 1s, End button disabled until idle

---

## 6. JCEF Webview Integration

### Technology

**JCEF** (Java Chromium Embedded Framework) — JetBrains maintained fork, same engine as IntelliJ IDEA. Full Chromium, modern web standards, DevTools support.

### Architecture

```
webviewModule (Koin)
├── JcefManager          — JCEF lifecycle, CefApp/CefClient/CefBrowser creation
├── BridgeRouter         — command dispatch from web, rate limiting
├── BridgeEventEmitter   — native → web event emission (JSON serialized)
├── BridgeSecurity       — origin check, input validation, navigation lock
└── BridgeAuditLog       — every command logged with timestamp, params, origin
```

### Compose Interop

`SwingPanel` composable embeds `CefBrowser` component — standard Compose Desktop approach for AWT/Swing components.

```kotlin
// In MainScreen composable
Column {
    Toolbar(...)  // 56px, Compose native
    SwingPanel(
        modifier = Modifier.fillMaxSize(),
        factory = { jcefManager.createBrowser(dispatcherUrl) }
    )
}
```

### Security Layers

| Layer | What it does |
|-------|-------------|
| **Origin check** | Bridge commands accepted only from allowed domain + main frame only |
| **Navigation lock** | `CefRequestHandler.onBeforeBrowse` — only allowed URLs load |
| **Input validation** | `makeCall` number: regex `^[+]?[0-9*#]{1,20}$`, control char rejection |
| **Rate limiter** | Per-command: `makeCall` 5/min, `getState` 60/min, etc. |
| **JSON serialization** | All native→web data via `kotlinx.serialization`, never string interpolation |
| **HTTPS enforcement** | HTTP dispatcher URLs rejected |
| **Certificate errors** | Always rejected, never bypassed |
| **Audit logging** | Every bridge command: timestamp, command, params, origin, result |

### Handshake Flow

```
1. JCEF browser created, dispatcher URL loads
2. Native buffers all events (incoming calls etc.)
3. Web page loads, calls window.YallaSIP.ready()
4. Native sends _init({ version, capabilities, agent, bufferedEvents })
5. Web processes buffered events, bridge is live
6. If web doesn't call ready() within 10s → retry _init (max 3)
7. After 3 retries → show error in toolbar, log to audit
```

### Page Reload Handling

If web page reloads (F5, navigation, crash recovery):
1. Native detects `onLoadEnd` event from JCEF
2. Resets handshake state
3. Waits for web to call `ready()` again
4. Sends fresh `_init` with current state (active call info if any)
5. Buffered events during reload are delivered

---

## 7. JS Bridge API — `window.YallaSIP`

### Design Principles

- **EventEmitter pattern** — `on(event, handler)` returns unsubscribe function, multiple listeners supported
- **Explicit state** — `setMute(callId, bool)` not `toggleMute()`, no race conditions
- **callId on all commands** — no implicit "current call"
- **Structured errors** — `{ code, message, recoverable }`
- **Sequence numbers** — monotonic `seq` on every event for ordering guarantee
- **Timestamps** — epoch milliseconds (`Date.now()` format) on every event
- **Duration** — in seconds (integer)

### Initialization

```javascript
// Web signals readiness (call as soon as script loads)
window.YallaSIP.ready()

// Native responds with _init:
// {
//   version: "1.0.0",
//   capabilities: ["call", "agentStatus", "callQuality"],
//   agent: { id: "agent-042", name: "Alisher" },
//   bufferedEvents: [ ... ]
// }
```

### Events — Native → Web

Subscribe: `const unsub = window.YallaSIP.on(eventName, handler)`
Unsubscribe: `unsub()` or `window.YallaSIP.off(eventName, handler)`

**Call Lifecycle Events:**

```javascript
// Incoming call received
on('incomingCall', {
    callId: string,       // UUID
    number: string,       // "+998901234567"
    direction: "inbound",
    seq: number,          // monotonic sequence
    timestamp: number     // epoch ms
})

// Outgoing call initiated
on('outgoingCall', {
    callId: string,
    number: string,
    direction: "outbound",
    seq: number,
    timestamp: number
})

// Call answered/connected (inbound or outbound)
on('callConnected', {
    callId: string,
    number: string,
    direction: "inbound" | "outbound",
    seq: number,
    timestamp: number
})

// Call ended
on('callEnded', {
    callId: string,
    number: string,
    direction: "inbound" | "outbound",
    duration: number,     // seconds (integer)
    reason: "hangup" | "rejected" | "missed" | "busy" | "error",
    seq: number,
    timestamp: number
})

// Mute state changed
on('callMuteChanged', {
    callId: string,
    isMuted: boolean,
    seq: number,
    timestamp: number
})

// Hold state changed
on('callHoldChanged', {
    callId: string,
    isOnHold: boolean,
    seq: number,
    timestamp: number
})
```

**System Events:**

```javascript
// Agent status changed
on('agentStatusChanged', {
    status: "ready" | "away" | "break" | "offline",
    previousStatus: string,
    seq: number,
    timestamp: number
})

// SIP connection state
on('connectionChanged', {
    state: "connected" | "reconnecting" | "disconnected",
    attempt: number,      // reconnect attempt (0 if connected)
    seq: number,
    timestamp: number
    // NOTE: no server IP exposed for security
})

// Call quality update (every 5s during active call)
on('callQualityUpdate', {
    callId: string,
    quality: "excellent" | "good" | "fair" | "poor",
    seq: number,
    timestamp: number
    // NOTE: raw metrics (MOS, jitter, RTT) stay native-side for security
})

// Global error (not tied to a command)
on('error', {
    code: string,
    message: string,
    severity: "warning" | "error" | "fatal",
    seq: number,
    timestamp: number
})
```

### Commands — Web → Native

All commands return `Promise<CommandResult>`.

```javascript
// Make outbound call
await window.YallaSIP.makeCall(number: string)
// Success: { success: true, callId: string }
// Failure: { success: false, error: { code, message, recoverable } }

// Answer incoming call
await window.YallaSIP.answer(callId: string)
// Success: { success: true }

// Reject incoming call
await window.YallaSIP.reject(callId: string)
// Success: { success: true }

// End active call
await window.YallaSIP.hangup(callId: string)
// Success: { success: true }

// Set mute state (explicit, not toggle)
await window.YallaSIP.setMute(callId: string, muted: boolean)
// Success: { success: true, isMuted: boolean }

// Set hold state (explicit, not toggle)
await window.YallaSIP.setHold(callId: string, onHold: boolean)
// Success: { success: true, isOnHold: boolean }

// Set agent status
await window.YallaSIP.setAgentStatus(status: string)
// Success: { success: true, status: string }
// Idempotent: setting "away" when already "away" = success (no-op)
```

### Queries — Web → Native

```javascript
// Full state snapshot
await window.YallaSIP.getState()
// {
//   connection: { state: "connected", attempt: 0 },
//   agentStatus: "ready",
//   call: null | {
//     callId: string,
//     number: string,
//     direction: "inbound" | "outbound",
//     state: "incoming" | "outgoing" | "active" | "on_hold",
//     isMuted: boolean,
//     isOnHold: boolean,
//     duration: number  // seconds
//   }
// }

// API version and capabilities
await window.YallaSIP.getVersion()
// { version: "1.0.0", capabilities: ["call", "agentStatus", "callQuality"] }
```

### Error Envelope

All command failures use this format:

```javascript
{
    success: false,
    error: {
        code: string,         // machine-readable, SCREAMING_SNAKE
        message: string,      // human-readable
        recoverable: boolean  // can the user retry?
    }
}
```

**Error codes:**

| Code | Meaning | Recoverable |
|------|---------|-------------|
| `ALREADY_IN_CALL` | Active call exists, cannot make new | No (hangup first) |
| `NO_ACTIVE_CALL` | No call to hangup/mute/hold | No |
| `NO_INCOMING_CALL` | No incoming call to answer/reject | No |
| `INVALID_NUMBER` | Phone number format invalid | Yes (fix number) |
| `NOT_REGISTERED` | SIP not connected | No (wait for reconnect) |
| `RATE_LIMITED` | Too many commands | Yes (wait and retry) |
| `NETWORK_TIMEOUT` | SIP request timed out | Yes (retry) |
| `INTERNAL_ERROR` | Unexpected native error | No |

### Versioning Contract

- **New events/commands**: minor version bump, web checks `capabilities` before using
- **New fields in existing events**: web must tolerate unknown fields (additive only)
- **Breaking changes**: major version bump
- **Web ignores unknown events** — forward compatibility
- **Native checks capabilities before sending** — no events for unsupported features

### TypeScript Definitions

Native team ships a `.d.ts` file with the bridge for frontend consumption. Contains all interfaces, event payloads, command signatures, error codes as union types.

---

## 8. Settings Popover

```
┌─────────────────────────┐
│  Settings                │
├─────────────────────────┤
│  Theme     [☀ Light  ▾] │
├─────────────────────────┤
│  Manual SIP Connection ▸│
│  (expandable form)      │
├─────────────────────────┤
│  [Logout]               │
├─────────────────────────┤
│  v1.0.0                 │
└─────────────────────────┘
```

| Setting | Type | Detail |
|---------|------|--------|
| Theme | Dropdown | Light / Dark |
| Manual SIP | Expandable | Server, port, username, password, Connect button |
| Logout | Button | Confirmation dialog → SIP unregister → login screen |
| Version | Text | App version, dim |

- Opens below gear icon as dropdown popover
- Closes on outside click
- Manual SIP section default collapsed

---

## 9. Theme & Brand

### Color System

Align with Yalla SDK (`uz.yalla.design.color`).

**Light Theme:**

| Token | Value | Usage |
|-------|-------|-------|
| `backgroundBase` | `#FFFFFF` | Main background |
| `backgroundSecondary` | `#F7F7F7` | Toolbar background, input fields |
| `backgroundTertiary` | `#E9EAEA` | Borders, dividers |
| `textBase` | `#101828` | Primary text |
| `textSubtle` | `#98A2B3` | Secondary text, placeholders |
| `brandPrimary` | `#562DF8` | Buttons, links, accents |
| `brandPrimaryDisabled` | `#C8CBFA` | Disabled primary buttons |
| `error` | `#F42500` | Errors, mute indicator, reject |
| `borderDisabled` | `#E4E7EC` | Input borders |
| `borderFilled` | `#101828` | Focused input borders |

**Dark Theme:**

| Token | Value | Usage |
|-------|-------|-------|
| `backgroundBase` | `#1A1A20` | Main background |
| `backgroundSecondary` | `#21222B` | Toolbar background |
| `backgroundTertiary` | `#1D1D26` | Borders, dividers |
| `textBase` | `#FFFFFF` | Primary text |
| `textSubtle` | `#747C8B` | Secondary text |
| `brandPrimary` | `#562DF8` | Buttons, links (same as light) |
| `brandPrimaryDisabled` | `#2C2D34` | Disabled primary buttons |
| `error` | `#F42500` | Errors (same as light) |
| `borderDisabled` | `#383843` | Input borders |
| `borderFilled` | `#FFFFFF` | Focused input borders |

**Call State Colors (both themes):**

| State | Color | Hex |
|-------|-------|-----|
| Ready / Active | Green | `#2E7D32` |
| Incoming / On Hold | Amber | `#F59E0B` |
| Muted | Red | `#F42500` |
| Offline / Ending | Gray | `#98A2B3` |

### Typography

| Platform | Font Family |
|----------|------------|
| macOS | SF Pro Display |
| Windows | Roboto |
| Linux | Roboto |

**Scale** (from yalla-sdk):

| Style | Size | Weight | Usage |
|-------|------|--------|-------|
| Title Large | 22sp | Bold | Login title |
| Title Base | 20sp | Bold | — |
| Body Large | 18sp | Regular/Medium | Phone number display |
| Body Base | 16sp | Regular/Medium | General text |
| Body Small | 14sp | Regular | Secondary info |
| Caption | 13sp | Medium | Status labels, badges |
| Monospace | 16sp | Medium | Call timer |

---

## 10. Desktop Features

### Always on Top

- `window.isAlwaysOnTop = true` — always enabled
- Operator window never buried under other apps

### No Minimize

- Minimize button disabled/hidden
- Window stays open during entire session

### Close Behavior

1. Operator clicks close (✕)
2. Confirmation dialog: "Logout and close Yalla SIP Phone?"
3. Confirm → SIP unregister → shutdown → exit
4. Cancel → nothing happens

### Desktop Notification (Incoming Call)

- macOS native notification via `java.awt.SystemTray` or JNA
- Shows: "Incoming Call — +998 90 123 4567"
- Sound: custom bundled ringtone (`.wav`, 2-3 seconds, professional)
- Click notification → window gains focus

### Window

| Property | Value |
|----------|-------|
| Title | "Yalla SIP Phone — {Agent Name}" |
| Default size | Maximized |
| Minimum size | 1280x720 |
| Resizable | Yes |
| Always on top | Yes |
| Minimize | Disabled |

---

## 11. Keyboard Shortcuts

| Action | Shortcut | Context |
|--------|----------|---------|
| Answer incoming | `Space` | When incoming call ringing |
| Reject incoming | `Escape` | When incoming call ringing |
| End call | `Escape` | When call active |
| Toggle mute | `Ctrl+M` | When call active |
| Toggle hold | `Ctrl+H` | When call active |
| Focus phone input | `Ctrl+D` | Any time (idle) |
| Make call | `Enter` | When phone input focused |

- `Ctrl+` prefix on mute/hold prevents accidental activation
- `Escape` dual purpose: reject (incoming) / end (active) — no conflict, mutually exclusive states
- Shortcuts only active in correct call state
- Extensible registry — new shortcuts added via config in future phases

---

## 12. Reconnection Strategy

### Auto-Reconnect

When SIP connection drops during active session:

1. Zone A temporarily shows reconnecting state: yellow dot + "Reconnecting..." (replaces agent status until reconnected)
2. Exponential backoff: 1s → 2s → 4s → 8s → 16s (5 attempts)
3. During reconnect: webview stays open, toolbar shows reconnecting state
4. If call was active: call is lost (SIP reality), toolbar returns to idle after reconnect
5. On successful reconnect: toolbar returns to normal, `connectionChanged` event sent to web

### Reconnect Failure → Login

After 5 failed attempts:
1. Show error message: "Connection lost. Please re-login."
2. Auto-navigate to login screen after 3 seconds
3. Webview destroyed
4. Login screen shows error context

---

## 13. Scope — What Is NOT in MVP

These features are designed for but NOT implemented:

| Feature | Phase | Foundation in MVP |
|---------|-------|-------------------|
| DTMF keypad | Phase 4 | Keyboard shortcut registry extensible |
| Blind/attended transfer | Phase 5 | JS bridge capabilities system |
| Call recording | Phase 5 | Bridge event extensibility |
| Multiple concurrent calls | Phase 5 | `callId` on all commands (ready) |
| Audio device selection | Phase 4 | Settings popover extensible |
| System tray | — | Not needed (always on top) |
| Auto-update | Phase 5 | Version in settings |
| Remote config | Phase 5 | Backend API interface |
| i18n | Phase 4 | Strings.kt already extracted |
| CRM integration | Phase 5 | JS bridge bidirectional |
| Queue monitoring | Phase 5 | Bridge capabilities |
| Agent status from backend | Phase 4 | Agent status dropdown ready |
| Call history | Phase 4 | `callEnded` events logged |

---

## 14. File Impact Summary

### New Files

| File | Purpose |
|------|---------|
| `feature/login/LoginComponent.kt` | Login screen business logic |
| `feature/login/LoginScreen.kt` | Login screen UI |
| `feature/main/MainComponent.kt` | Main screen orchestrator |
| `feature/main/MainScreen.kt` | Toolbar + Webview layout |
| `feature/main/toolbar/ToolbarComponent.kt` | Toolbar state management |
| `feature/main/toolbar/ToolbarContent.kt` | Toolbar UI composables |
| `feature/main/toolbar/AgentStatusDropdown.kt` | Agent status dropdown |
| `feature/main/toolbar/CallControls.kt` | Call control buttons per state |
| `feature/main/toolbar/SettingsPopover.kt` | Settings dropdown |
| `feature/main/toolbar/CallQualityIndicator.kt` | MOS dot indicator |
| `feature/main/webview/WebviewPanel.kt` | JCEF browser Compose wrapper |
| `domain/AuthApi.kt` | Login API interface |
| `domain/AgentStatus.kt` | Agent status enum |
| `domain/LoginResponse.kt` | Backend response model |
| `data/auth/MockAuthApi.kt` | Mock backend implementation |
| `data/jcef/JcefManager.kt` | JCEF lifecycle management |
| `data/jcef/BridgeRouter.kt` | JS → Native command dispatch |
| `data/jcef/BridgeEventEmitter.kt` | Native → JS event emission |
| `data/jcef/BridgeSecurity.kt` | Origin check, validation, rate limit |
| `data/jcef/BridgeAuditLog.kt` | Command audit logging |
| `data/jcef/PhoneNumberValidator.kt` | Phone number validation |
| `di/WebviewModule.kt` | JCEF DI bindings |
| `di/AuthModule.kt` | Auth API DI bindings |
| `ui/theme/YallaColors.kt` | Yalla brand color definitions |
| `ui/theme/YallaDarkColors.kt` | Dark theme colors |

### Modified Files

| File | Changes |
|------|---------|
| `ui/theme/Theme.kt` | New seed color `#562DF8`, dark theme support, font change |
| `ui/theme/AppTokens.kt` | New toolbar tokens, updated window sizes |
| `navigation/Screen.kt` | Add Login, Main screens |
| `navigation/RootComponent.kt` | Add login/main navigation, remove old dialer |
| `navigation/RootContent.kt` | New window config (always on top, no minimize, new sizes) |
| `navigation/ComponentFactory.kt` | Add login, main screen creation |
| `navigation/ComponentFactoryImpl.kt` | Implement new screen factories |
| `di/SipModule.kt` | Add auth, webview modules |
| `Main.kt` | New startup flow (no SIP init until login) |
| `ui/strings/Strings.kt` | New strings for login, toolbar, settings |

### Deprecated / Removed

| File | Reason |
|------|--------|
| `feature/registration/*` | Replaced by Login screen |
| `feature/dialer/*` | Replaced by Main screen (toolbar) |
| `ui/component/ConnectButton.kt` | Login screen replaces this |
| `ui/component/ConnectionStatusCard.kt` | Toolbar replaces this |
| `ui/component/SipCredentialsForm.kt` | Moved to settings manual SIP |

### Dependencies (build.gradle.kts)

| Dependency | Purpose |
|------------|---------|
| `org.jcef:jcef` (JetBrains fork) | Chromium embedded browser |
| `kotlinx-serialization-json` | JS bridge JSON serialization |

---

## 15. Architecture Diagram

```
Main.kt
├── di/
│   ├── SipModule.kt          (existing — PjsipEngine bindings)
│   ├── AuthModule.kt         (NEW — AuthApi binding)
│   ├── WebviewModule.kt      (NEW — JCEF + Bridge bindings)
│   ├── SettingsModule.kt     (existing — AppSettings)
│   └── FeatureModule.kt      (updated — new screen factories)
├── domain/
│   ├── SipStackLifecycle.kt   (existing)
│   ├── RegistrationEngine.kt  (existing)
│   ├── CallEngine.kt          (existing)
│   ├── AuthApi.kt             (NEW — login interface)
│   ├── AgentStatus.kt         (NEW — enum)
│   ├── LoginResponse.kt       (NEW — data class)
│   └── ... (existing domain files)
├── data/
│   ├── pjsip/                 (existing — unchanged)
│   ├── auth/
│   │   └── MockAuthApi.kt     (NEW — mock backend)
│   └── jcef/
│       ├── JcefManager.kt     (NEW — browser lifecycle)
│       ├── BridgeRouter.kt    (NEW — command dispatch)
│       ├── BridgeEventEmitter.kt (NEW — event emission)
│       ├── BridgeSecurity.kt  (NEW — security layers)
│       ├── BridgeAuditLog.kt  (NEW — audit logging)
│       └── PhoneNumberValidator.kt (NEW)
├── navigation/
│   ├── Screen.kt              (updated — Login, Main)
│   ├── RootComponent.kt       (updated — new flow)
│   ├── RootContent.kt         (updated — new window config)
│   ├── ComponentFactory.kt    (updated)
│   └── ComponentFactoryImpl.kt (updated)
├── feature/
│   ├── login/
│   │   ├── LoginComponent.kt  (NEW)
│   │   └── LoginScreen.kt     (NEW)
│   ├── main/
│   │   ├── MainComponent.kt   (NEW — orchestrates toolbar + webview)
│   │   ├── MainScreen.kt      (NEW — layout)
│   │   ├── toolbar/
│   │   │   ├── ToolbarComponent.kt    (NEW)
│   │   │   ├── ToolbarContent.kt      (NEW)
│   │   │   ├── AgentStatusDropdown.kt (NEW)
│   │   │   ├── CallControls.kt        (NEW)
│   │   │   ├── SettingsPopover.kt     (NEW)
│   │   │   └── CallQualityIndicator.kt (NEW)
│   │   └── webview/
│   │       └── WebviewPanel.kt (NEW — SwingPanel + CefBrowser)
│   ├── registration/          (DEPRECATED — replaced by login)
│   └── dialer/                (DEPRECATED — replaced by toolbar)
└── ui/
    ├── theme/
    │   ├── Theme.kt           (updated — Yalla brand, dark mode)
    │   ├── AppTokens.kt       (updated — toolbar tokens)
    │   ├── YallaColors.kt     (NEW — brand colors)
    │   └── YallaDarkColors.kt (NEW — dark theme)
    ├── strings/Strings.kt     (updated — new strings)
    └── component/             (DEPRECATED — absorbed into features)
```

**~25 new files, ~10 modified, ~5 deprecated.** Net: significant growth, but each file focused and testable.

---

## 16. Expert Review Amendments (2026-04-05)

8-agent parallel review conducted. All findings below are ACCEPTED and override earlier sections where they conflict.

### 16.1 Keyboard Shortcuts — REVISED (UX + Compose + JCEF)

**Space/Escape are DANGEROUS** — operator typing in webview accidentally answers/hangs up.

| Action | OLD | NEW | Reason |
|--------|-----|-----|--------|
| Answer incoming | `Space` | `Ctrl+Enter` | Doesn't conflict with webview typing |
| Reject incoming | `Escape` | `Ctrl+Shift+E` | Escape used in web modals |
| End call | `Escape` | `Ctrl+Shift+E` | Same — reject/end share shortcut |
| Toggle mute | `Ctrl+M` | `Ctrl+M` | Unchanged |
| Toggle hold | `Ctrl+H` | `Ctrl+H` | Unchanged |
| Focus phone input | `Ctrl+D` | `Ctrl+L` | Ctrl+D = Chromium bookmark |
| Make call | `Enter` | `Enter` | Unchanged (only when input focused) |

**Implementation**: All shortcuts via `CefKeyboardHandler.onPreKeyEvent()`, NOT Compose `onKeyEvent`. Compose key handlers don't fire when JCEF has focus.

### 16.2 Toolbar Zones — REVISED (UX)

**Answer/Reject moved from Zone B to Zone C** for spatial consistency:

| State | Zone B (number/input) | Zone C (controls) |
|-------|----------------------|-------------------|
| Idle | Editable phone input | `[📞 Call]` (visible) |
| Outgoing (ringing) | Number (disabled) + "Ringing..." | `[📞 End]` |
| Incoming | Number (disabled, bold) | `[✓ Answer]` `[✕ Reject]` |
| Active | Number (disabled) + `[00:42]` timer | `[🔇 Mute]` `[⏸ Hold]` `[📞 End]` |
| On Hold | Number (disabled) + `[00:42 paused]` | `[🔇 Mute]` `[▶ Resume]` `[📞 End]` |

**Changes from original**:
- Answer/Reject now in Zone C (same physical location as Mute/Hold/End)
- Call button moved to Zone C (idle state)
- Added "Outgoing/Ringing" state — shows "Ringing..." instead of timer until `callConnected`
- Timer starts only on `callConnected`, not on `makeCall`

### 16.3 Agent Status — Wrap-Up Added (UX)

**New agent status**: `Wrap-Up` — auto-entered after call ends, configurable timeout (default 30s).

| Status | Color | Behavior |
|--------|-------|----------|
| Ready | `#2E7D32` (light) / `#4CAF50` (dark) | Available for calls |
| Away | `#F59E0B` | Manually set |
| Break | `#F97316` | Manually set |
| Wrap-Up | `#8B5CF6` (purple-ish) | Auto after call, countdown timer, "Ready" to exit early |
| Offline | `#98A2B3` | Manually set / disconnect |

Zone A shows countdown during Wrap-Up: `"Wrap-Up 0:25"`.

### 16.4 Color Corrections — WCAG Compliance (Theme)

**Light theme fixes:**

| Token | OLD | NEW | Ratio |
|-------|-----|-----|-------|
| `textSubtle` | `#98A2B3` (2.7:1 FAIL) | `#6B7280` | 5.0:1 PASS |
| `errorText` | `#F42500` (4.0:1 FAIL) | `#D32F2F` | 5.5:1 PASS |
| `errorIndicator` | — | `#F42500` | 4.0:1 (icons OK at 3:1) |

**Dark theme fixes:**

| Token | OLD | NEW | Ratio on `#1A1A20` |
|-------|-----|-----|-----|
| `textSubtle` | `#747C8B` (3.3:1 FAIL) | `#9CA3AF` | 5.5:1 PASS |
| `errorText` | `#F42500` (3.6:1 FAIL) | `#FF6B6B` | 5.8:1 PASS |
| `brandPrimaryText` | `#562DF8` (2.9:1 FAIL) | `#8B6FFF` | 5.2:1 PASS |
| Ready green | `#2E7D32` (2.6:1 FAIL) | `#4CAF50` | 4.8:1 PASS |
| `backgroundTertiary` | `#1D1D26` (wrong) | `#383843` | Visible divider |

**Call quality dot**: 8dp → **12dp**. Add text label next to dot: "Good"/"Fair"/"Poor".

**Toolbar divider**: 1px `#383843` bottom border on toolbar (dark theme toolbar/webview separation).

### 16.5 JCEF — Corrected (JCEF Expert)

**Dependency**: ~~`org.jcef:jcef`~~ → **JetBrains Runtime (JBR) with JCEF variant**. No Maven dependency needed — JCEF classes bundled in JBR. Gradle `javaHome` must point to `jbr_jcef-21+`.

**Bundle size**: +150-250MB (Chromium runtime). Total app: 250-350MB.

**Threading**: All JCEF operations on Swing EDT (`SwingUtilities.invokeAndWait`).

**Z-order**: Dropdowns/popovers MUST NOT overlap webview area. Settings popover and Agent Status dropdown constrained to 56px toolbar zone. If dropdown extends below toolbar → use `JDialog` (separate undecorated Swing window).

**Handshake corrected**:
1. `onLoadStart` (main frame) → reset handshake, buffer events
2. `onLoadEnd` (main frame) → inject `window.YallaSIP` bridge script
3. Web polls for `window.YallaSIP` existence, then calls `ready()`
4. Native sends `_init` with buffered events

**DevTools**: `CefSettings.remote_debugging_port = 0` in production. DevTools only in debug builds.

**Disposal order**: `CefMessageRouter.dispose()` → `CefBrowser.close(true)` → `CefClient.dispose()` → `CefApp.dispose()` (only on app exit).

### 16.6 Architecture Corrections (Architecture Expert)

| Change | Detail |
|--------|--------|
| `PhoneNumberValidator` | Move from `data/jcef/` to `domain/` |
| `domain/WebviewManager.kt` | NEW interface — `MainComponent` depends on this, not `JcefManager` |
| `domain/BridgeExecutor.kt` | NEW interface — `executeScript(js)`, testable mock |
| `domain/AuthResult.kt` | NEW — domain model (SipCredentials + dispatcherUrl + AgentInfo) |
| `data/auth/LoginResponse.kt` | DTO stays in data layer, mapped to `AuthResult` |
| `domain/AuthRepository.kt` | Renamed from `AuthApi` — repository pattern |
| `CallState` | Add `callId: String` to `Ringing` and `Active` |
| `LoginComponent` | Depends on `AuthRepository` + `RegistrationEngine` (two-phase login) |
| `JcefManager` | Koin `single` (app-scoped, CefApp lives here) |
| `BridgeRouter`/`BridgeEventEmitter` | Koin `factory` (per-session, destroyed on logout) |
| `domain/WebviewState.kt` | NEW sealed interface: `Loading`/`Ready`/`Error` |
| `YallaColors.kt` + `YallaDarkColors.kt` | Merge into single `YallaColors.kt` |

### 16.7 SIP/pjsip Fixes (SIP Expert)

| Fix | Detail |
|-----|--------|
| **pjsip retry disabled** | `regConfig.retryIntervalSec = 0` — our reconnect logic only |
| **Transport state handling** | Implement `onTransportState` callback in `PjsipEndpointManager` for TCP drop detection |
| **Mute after hold** | `connectCallAudio()` checks `isMuted` flag, skips `captureMedia.startTransmit` if muted |
| **holdInProgress guard** | Reset in `onCallMediaState`, not in `finally` block |
| **Ringtone lifecycle** | Stop ringtone synchronously BEFORE `call.answer()`, not after |
| **Call timer** | Native UI timer authoritative. Timer starts on `callConnected`, not `makeCall` |
| **Log level** | `epConfig.logConfig.level = 3` in release (was 5 — exposes SIP auth headers) |

### 16.8 JS Bridge Additions (All reviewers)

New events:
```javascript
on('themeChanged', { theme: "light" | "dark", seq, timestamp })
on('callRejectedBusy', { number, seq, timestamp })  // auto-rejected 2nd call
on('error', { code, message, severity, seq, timestamp })  // global errors
```

New command:
```javascript
await window.YallaSIP.setCallerInfo(callId, { name, company })
// Web dispatcher pushes caller identity to toolbar after CRM lookup
```

### 16.9 Security Hardening (Security Audit)

| Fix | Detail |
|-----|--------|
| **SIP password** | Use `CharArray` + zero after registration. Never `String`. |
| **Phone numbers in logs** | `PhoneNumberMasker` utility: `+998 90 *** ** 67` |
| **PJSIP log level** | Level 3 in release (level 5 exposes auth) |
| **Login brute-force** | Exponential backoff: 1s, 2s, 4s, 8s, lock 30s after 5 failures |
| **Desktop notifications** | Show "Incoming Call" only, no phone number |
| **Inactivity timeout** | Auto-Away after 5 min idle, lock screen after 15 min |
| **JCEF cache** | `persist_session_cookies = false`, clear cache on logout |
| **JCEF sandbox** | `no_sandbox = false`, block `file://`/`data:`/`javascript:` URLs |
| **Logout cleanup** | Destroy webview, null credentials, clear bridge, reset YallaSIP |
| **Premium numbers** | Configurable blocklist in `PhoneNumberValidator` |

### 16.10 Window Management (Compose Expert)

**Two Window approach** — login and main are separate `Window` composables:
```kotlin
application {
    if (showLogin) {
        Window(resizable = false, alwaysOnTop = false) { LoginScreen() }
    }
    if (showMain) {
        Window(resizable = true, alwaysOnTop = true) { MainScreen() }
    }
}
```

**No minimize**: macOS — JNA to remove `NSMiniaturizableWindowMask`. Fallback: intercept `windowState.isMinimized` and restore.

**Notifications**: MVP — `osascript` on macOS, `SystemTray` on Windows. Production — JNA + UNUserNotificationCenter.

### 16.11 Scope — Tiered Delivery (Risk Manager)

**Session 1 (today):**
- Tier 1: Theme + Login + Navigation + Main screen + Toolbar (all states)
- Tier 2: Agent status, dark theme, shortcuts, settings, desktop features
- JCEF spike: 2-hour hard timebox. Placeholder if fails.
- **Plan B**: `Desktop.browse(dispatcherUrl)` if JCEF doesn't work

**Session 2 (next):**
- JS Bridge: BridgeRouter, BridgeEventEmitter, BridgeSecurity, BridgeAuditLog (TDD)
- Full JCEF integration (if spike succeeded)
- Security hardening (credential lifecycle, log masking, inactivity lock)

### 16.12 Additional Files from Amendments

| File | Purpose |
|------|---------|
| `domain/WebviewManager.kt` | Webview abstraction interface |
| `domain/BridgeExecutor.kt` | JS execution abstraction |
| `domain/AuthResult.kt` | Domain auth model |
| `domain/AuthRepository.kt` | Renamed from AuthApi |
| `domain/WebviewState.kt` | Loading/Ready/Error sealed interface |
| `domain/AgentInfo.kt` | Agent data (id, name) |
| `util/PhoneNumberMasker.kt` | PII masking for logs |
