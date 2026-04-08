# Multi-SIP Account Support + UI Rethink

**Date:** 2026-04-08
**Status:** Approved (rev.2 — post critical review)
**Scope:** Multi-SIP registration, toolbar redesign, login redesign, settings dialog, i18n

## Overview

Transform yalla-sip-phone from single SIP account to multi-SIP account support. All active SIP accounts from backend auto-register on login, calls happen on one line at a time. Complete toolbar content redesign using Yalla Design System palette. Login screen enlarged to match main window size for smooth transitions. Settings moved from popover to overlay dialog window. Internationalization with UZ/RU support.

---

## Multi-SIP Architecture

### Domain Layer

New `SipAccountManager` replaces `RegistrationEngine` as the public registration interface:

```kotlin
data class SipAccountInfo(
    val id: String,              // "${extensionNumber}@${server}" — stable, debuggable
    val name: String,            // from SipConnectionDto.sipName, fallback: "SIP ${ext}"
    val credentials: SipCredentials,
)

data class SipAccount(
    val id: String,
    val name: String,
    val credentials: SipCredentials,
    val state: SipAccountState,
)

sealed interface SipAccountState {
    data object Connected : SipAccountState
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : SipAccountState
    data object Disconnected : SipAccountState
}

interface SipAccountManager {
    val accounts: StateFlow<List<SipAccount>>
    suspend fun registerAll(accounts: List<SipAccountInfo>): Result<Unit>
    suspend fun connect(accountId: String): Result<Unit>
    suspend fun disconnect(accountId: String): Result<Unit>
    suspend fun unregisterAll()
}
```

**`hasActiveCall` removed from `SipAccount`** — derived in UI by combining `accounts` with `callState` (single source of truth).

**`activeCallAccount` removed** — derived: `callState.value.accountId` → find in `accounts`.

### RegistrationEngine Deprecation

`RegistrationEngine` is **deprecated and replaced** by `SipAccountManager`:

| Old consumer | Migration |
|---|---|
| `LoginComponent` → `registrationEngine.register(creds)` | → `sipAccountManager.registerAll(accountInfoList)` |
| `MainComponent` → observes `registrationState` | → observes `sipAccountManager.accounts` |
| `ConnectionManagerImpl` → monitors single `registrationState` | → **removed**, reconnection logic moves into `SipAccountManager` |
| `LogoutOrchestrator` → `registrationEngine.unregister()` | → `sipAccountManager.unregisterAll()` |
| `BridgeRouter` → reads `registrationState.value` | → reads `sipAccountManager.accounts.value` |

`ConnectionManager` interface is **removed**. Per-account reconnection is internal to `SipAccountManager` implementation — each account gets its own exponential backoff loop on the pjsip dispatcher thread.

### CallState Changes

Add `accountId` to **all call-carrying states**:

```kotlin
sealed interface CallState {
    data object Idle : CallState
    data class Ringing(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val isOutbound: Boolean,
        val accountId: String,     // NEW — which SIP line
    ) : CallState
    data class Active(
        val callId: String,
        val remoteNumber: String,
        val remoteName: String?,
        val isOutbound: Boolean,
        val isMuted: Boolean,
        val isOnHold: Boolean,
        val accountId: String,     // NEW
    ) : CallState
    data class Ending(
        val callId: String,
        val accountId: String,     // NEW
    ) : CallState
}
```

### CallEngine Changes

`makeCall` gets `accountId` parameter for outbound call routing:

```kotlin
interface CallEngine {
    val callState: StateFlow<CallState>
    suspend fun makeCall(number: String, accountId: String): Result<Unit>  // CHANGED
    suspend fun answerCall()
    suspend fun hangupCall()
    suspend fun toggleMute()
    suspend fun toggleHold()
    suspend fun sendDtmf(callId: String, digits: String): Result<Unit>
    suspend fun transferCall(callId: String, destination: String): Result<Unit>
}
```

UI sends `makeCall(number, firstConnectedAccount.id)` — outbound call goes through first connected SIP. Future: user-selectable account.

### PjsipEngine Restructure

```
PjsipEngine (implements SipStackLifecycle + CallEngine)
├── PjsipEndpointManager          // unchanged — endpoint + transports
├── PjsipAccountManager           // Map<String, PjsipAccount> (was single)
│   ├── per-account registration state
│   ├── per-account reconnection loop (exponential backoff)
│   └── rate limiting between registrations (500ms)
└── PjsipCallManager              // unchanged API, uses accountId for routing
```

`PjsipEngine` no longer implements `RegistrationEngine`. New `PjsipSipAccountManager` implements `SipAccountManager`, wraps `PjsipAccountManager`.

`PjsipAccount.onIncomingCall()` passes `this.accountId` to call manager.
`PjsipAccount.onRegState()` updates per-account state in parent manager.

### Simultaneous Incoming Calls

If account-A has a ringing/active call and account-B gets incoming:
- Account-B's `onIncomingCall()` auto-rejects with **486 Busy Here**
- Only one call exists at a time — enforced in `PjsipCallManager`

### Registration Sequencing

`registerAll()` registers accounts **sequentially** on pjsip thread with 500ms delay between each. Returns `Result.success` if **at least one** account succeeds. Individual failures are reflected as `SipAccountState.Disconnected` on the specific account — per-account reconnection loop starts automatically.

Auth failures (`401`) skip reconnection — marked as `Disconnected` with no auto-retry (bad credentials won't improve).

### Credential Storage

`SipAccountManager` maintains internal `Map<String, SipAccountInfo>` populated by `registerAll()`. `connect(accountId)` uses cached credentials. `disconnect(accountId)` removes registration but keeps credentials cached for re-connect.

### Auto-Logout Logic

Auto-logout triggers when: **ALL SIP accounts are `Disconnected` AND `callState is Idle`**. If any account is `Connected` or `Reconnecting`, no auto-logout.

### AuthResult Changes

```kotlin
data class AuthResult(
    val token: String,
    val accounts: List<SipAccountInfo>,  // CHANGED from sipCredentials: SipCredentials
    val dispatcherUrl: String,
    val agent: AgentInfo,
)
```

`MeResultDto.toAuthResult()` maps `sips.filter { it.isActive }` → `List<SipAccountInfo>`:
- `id` = `"${sip.extensionNumber}@${sip.serverUrl}"`
- `name` = `sip.sipName ?: "SIP ${sip.extensionNumber}"`
- `credentials` = existing `SipCredentials` mapping

### LogoutOrchestrator Changes

```kotlin
class LogoutOrchestrator(
    private val sipAccountManager: SipAccountManager,  // CHANGED from registrationEngine
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
) {
    suspend fun logout() {
        sipAccountManager.unregisterAll()  // unregisters all accounts sequentially
        runCatching { authApi.logout() }
        tokenProvider.clear()
    }
}
```

### Key Constraints

- `CallEngine` API changes: `makeCall` gets `accountId` parameter
- All pjsip operations remain on `pjsip-event-loop` single-thread dispatcher
- pjsip transports are shared across accounts (created once per endpoint)
- `SipAccount.id` format: `"${extensionNumber}@${server}"` — stable across sessions

---

## Toolbar UI Redesign

### Layout (left to right, 52dp height)

```
[AgentStatus] [PhoneField] | [CallActions] [Timer?] ← [SipChips →] | [Settings]
```

| Component | Width | Description |
|-----------|-------|-------------|
| AgentStatusButton | 36dp | Icon button with status dot, dropdown on click |
| PhoneField | widthIn(min=120.dp, max=160.dp) | +998XXXXXXXXX format, tabular nums font |
| Divider | 1dp | `border.disabled` #383843 |
| CallActions | N × 36dp | State-dependent action buttons |
| CallTimer | auto | Brand tint surface, visible only during active call |
| SipChipRow | flex(1f) | Row with Arrangement.End (< 6 chips), LazyRow (6+ chips) |
| Divider | 1dp | `border.disabled` #383843 |
| SettingsButton | 36dp | Opens settings dialog window |

Minimum toolbar width at 1280dp: ~513dp fixed + ~767dp for SIP chips. Fits 6-7 chips without scrolling.

### Agent Status

3 UI states (display-only simplification — internal enum keeps all 5):

| UI Display | Internal Mapping | Color | Yalla Token |
|-----------|-----------------|-------|-------------|
| Liniyada | READY | #562DF8 | button.active |
| Band | AWAY, BREAK, WRAP_UP | #FF234B | accent.pinkSun |
| Liniyada emas | OFFLINE | #747C8B | text.subtle |

Bridge layer continues to accept/emit all 5 values. UI dropdown shows 3 options. When user selects "Band", bridge sends `AWAY` (default busy mapping).

Dropdown menu (not popup — inline expand pattern to avoid SwingPanel z-order issues).

### Phone Field

- `Modifier.widthIn(min = 120.dp, max = 160.dp)` — stable layout, no pixel jumps
- Monospace/tabular font for digits — consistent width
- Max format: `+998XXXXXXXXX` (13 characters)
- Placeholder in idle: `+998 __ ___ __ __`
- During call: shows caller/callee number
- Border: `border.disabled` default, `button.active` during ringing

### Call Actions (state-dependent)

**Idle:**
- Call button (disabled) — `button.disabled` #2C2D34, `icon.disabled` #C8CBFA
- Mute button (disabled)
- Hold button (disabled)

**Ringing (inbound):**
- Answer — `button.active` #562DF8, glow shadow
- Reject — `icon.red` #F42500
- "Qo'ng'iroq..." label — brand tint surface

**Active call:**
- Hangup — `icon.red` #F42500
- Mute — `bg.secondary` #21222B, `icon.subtle` #98A2B3 (active: brand tint when muted)
- Hold — same pattern
- Timer — brand tint surface (`rgba(86,45,248,0.15)`, border `rgba(86,45,248,0.3)`, text `#C8CBFA`)

**Transfer:** Removed from toolbar scope — tracked as separate feature. Requires its own UX spec (destination input, blind vs attended).

### SIP Chip States

| State | Background | Border | Text | Yalla Source |
|-------|-----------|--------|------|--------------|
| Connected | rgba(562DF8, 0.12) | rgba(562DF8, 0.3) | #C8CBFA | brand tint |
| Reconnecting | rgba(FF234B, 0.1) | rgba(FF234B, 0.25) | #FF234B | pinkSun tint |
| Disconnected | rgba(F42500, 0.1) | rgba(F42500, 0.25) | #F42500 | error tint |
| Active call | #562DF8 solid | #7957FF | #FFFFFF | brand solid |
| Muted (during call) | #2C2D34 | #383843 | #747C8B | button.disabled |

**During call:** Active call chip = brand solid, connected chips = muted (#2C2D34), disconnected chips keep error tint (still visible).

### SIP Chip Toggle Logic

- Click connected → `disconnect(accountId)` → Disconnected
- Click disconnected → `connect(accountId)` → Reconnecting → Connected
- Click reconnecting → ignore (transition state)
- Click connected with active call → snackbar warning (i18n: `sipDisconnectBlockedByCall`)
- Toggle failure timeout: after 30s reconnecting → auto-revert to Disconnected, tooltip shows error reason

### SIP Chip Hover Tooltip

**Implementation: `DialogWindow`** (OS-level) positioned above chip — avoids SwingPanel z-order issues. Shows on hover with 300ms delay, hides on mouse exit.

Content:
- SIP name (bold, #FFFFFF)
- Extension number (#98A2B3)
- Server (#98A2B3)
- Transport (#98A2B3) — shown only if non-default (not UDP)
- Status (colored: brand/pinkSun/error)
- If disconnected: "Bosing → qayta ulash" hint (#FF234B)

Tooltip style: `bg.secondary` #21222B, border `#383843`, rounded 8dp, shadow.

### SIP Chip Row Layout

- **< 6 chips:** Regular `Row` with `Arrangement.End` — right-aligned, no scrolling
- **6+ chips:** `LazyRow` with `reverseLayout = true` — newest/rightmost visible first, scroll left to see more
- Transition handled by `if (accounts.size < 6) Row { ... } else LazyRow { ... }`

### Component File Structure

```
feature/main/toolbar/
├── ToolbarComponent.kt          // business logic, state management
├── ToolbarContent.kt            // main Row layout
├── AgentStatusButton.kt         // icon button + inline dropdown menu
├── PhoneField.kt                // widthIn, tabular nums
├── CallActions.kt               // state-dependent action buttons
├── CallTimer.kt                 // brand tint duration surface
├── SipChipRow.kt                // Row/LazyRow + SipChip + hover tooltip
└── SettingsDialog.kt            // DialogWindow (replaces SettingsPopover)
```

Delete: `AgentStatusDropdown.kt`, `CallControls.kt`, `CallQualityIndicator.kt`, `SettingsPopover.kt`

### Keyboard Shortcuts

Existing shortcuts remain unchanged:
- `Ctrl+Enter` = Answer incoming call
- `Ctrl+Shift+E` = Reject/End call
- `Ctrl+M` = Toggle mute
- `Ctrl+H` = Toggle hold
- `Ctrl+L` = Focus phone input

New features (SIP chip toggle, settings, agent status) do not need keyboard shortcuts in this phase.

---

## Login Screen Redesign

### Window Sizing

- Login and Main both use **1280×720**
- No window resize on navigation — transition is pure Compose animation (slide + fade)
- `resizable = false` on login, `resizable = true` on main
- `alwaysOnTop = false` on login, `alwaysOnTop = true` on main
- Remove AWT resize logic for login — single size for both screens
- **Note:** On 1366x768 displays, 1280x720 + title bar is tight but fits. AWT `minimumSize` stays at 1280x720.

### Visual Design

- Background: splash gradient (`#7957FF → #562DF8 → #3812CE`, top-left to bottom-right — matches `SplashBackground` in DS)
- Centered card: 320dp wide, semi-transparent background `Color(0xFF1A1A20).copy(alpha = 0.85f)` — **no blur** (Compose Desktop doesn't support backdrop-filter)
- Logo: 56dp rounded square, brand #562DF8, phone icon
- Title: "Yalla SIP Phone", 20sp, #FFFFFF
- Subtitle slot: 20dp fixed height — shows context text or error message

### States (all same card size)

**Default:**
- Subtitle: "Tizimga kirish" (#98A2B3)
- Password field: `bg.secondary`, `border.disabled`, lock icon, eye toggle (show/hide password)
- Button: "Kirish", `button.active` #562DF8
- "Qo'lda ulanish" link (#747C8B)
- Version: #383843

**Loading:**
- Password field: disabled (opacity 0.5)
- Button: "Ulanmoqda..." with spinner, `bg.secondary` + brand border
- Spinner: brand color

**Error — Wrong Password:**
- Subtitle: "Parol noto'g'ri" (#F42500) — replaces default subtitle, no layout shift
- Password field: `border.error` #F42500
- Button: "Qayta urinish", `button.active`

**Error — Network:**
- Subtitle: "Serverga ulanib bo'lmadi" (#FF234B)
- Password field: normal border
- Button: "Qayta urinish"

### Manual Connection Dialog

Stays as `AlertDialog` — no visual changes needed.

---

## Settings Dialog

### Implementation: `DialogWindow` (OS-level)

**NOT a Compose overlay** — must be a real OS-level `DialogWindow` to render above JCEF SwingPanel. Styled to look like an overlay dialog.

### Structure

- Window: undecorated, centered over parent, background transparent
- Card: `bg.tertiary` #1D1D26, border #383843, rounded 14dp
- Close button: top-right, `bg.secondary` 28dp
- Click outside card → dismiss

### Content

1. **Header:** "Sozlamalar" title + close button
2. **Agent info card:** Avatar initials (brand bg) + name + agent ID
3. **Theme:** Label + 3 icon toggle (sun/moon/monitor), segmented control style
4. **Locale:** Label + 2 flag toggle (🇺🇿/🇷🇺), segmented control style
5. **Logout:** "Chiqish" button, error tint (`#450a0a` bg, `#F42500` text)
6. **Version:** "Yalla SIP Phone v1.0.0", #383843

### Removed

- Audio device selector (separate feature)
- English locale option
- Transfer button (separate feature)

---

## Internationalization (i18n)

### Architecture

```kotlin
interface StringResources {
    val loginTitle: String
    val loginSubtitle: String
    val loginPasswordPlaceholder: String
    val loginButton: String
    val loginConnecting: String
    val loginRetry: String
    val loginManualConnection: String
    val errorWrongPassword: String
    val errorNetworkFailed: String
    val agentStatusOnline: String       // "Liniyada" / "На линии"
    val agentStatusBusy: String         // "Band" / "Занят"
    val agentStatusOffline: String      // "Liniyada emas" / "Не на линии"
    val sipConnected: String
    val sipReconnecting: String
    val sipDisconnected: String
    val sipDisconnectBlockedByCall: String  // warning message
    val sipReconnectHint: String
    val settingsTitle: String
    val settingsTheme: String
    val settingsLocale: String
    val settingsLogout: String
    val callRinging: String             // "Qo'ng'iroq..."
    // ... all SIP error messages
}

object UzStrings : StringResources { ... }
object RuStrings : StringResources { ... }

val LocalStrings = staticCompositionLocalOf<StringResources> { 
    error("StringResources not provided") 
}
```

### Non-Compose Strings

`Strings.kt` object **retained** for non-Compose contexts:
- `Main.kt` JOptionPane error dialogs
- Window titles
- Shutdown hooks

These strings are NOT i18n'd in this phase — they are internal/developer-facing. Future: `LocaleProvider` singleton for all contexts.

### Storage

- `AppSettings.locale: String` — "uz" or "ru"
- Default: "uz"
- Applied via `CompositionLocalProvider(LocalStrings provides strings)` in `YallaSipPhoneTheme`

### SipError i18n

`SipError.displayMessage` currently returns English strings. Add `fun displayMessage(strings: StringResources): String` overload that returns localized text.

---

## JS Bridge Changes

### getState() Response

Add `accounts` field. Keep `connection` as **aggregate** for backwards compatibility:

```json
{
  "agent": { "id": 1042, "name": "Islom" },
  "connection": {
    "state": "connected"
  },
  "accounts": [
    { "id": "1001@sip.yalla.uz", "name": "Operator-1", "extension": "1001", "status": "connected" },
    { "id": "1002@sip.yalla.uz", "name": "Dispatch", "extension": "1002", "status": "disconnected" }
  ],
  "capabilities": ["makeCall", "transfer", ...],
  "token": "..."
}
```

**`connection.state` semantic:** "connected" if ANY account connected, "disconnected" if ALL disconnected, "reconnecting" if any reconnecting and none connected. Backwards compatible with existing React hook.

### Bridge Protocol Version

Bump to **1.2.0**. `getVersion()` returns `{ version: "1.2.0", capabilities: [..., "multiSip"] }`.

### Modified Events

- `connectionChanged` → add `accountId` field (additive, backwards compatible)
  ```json
  { "state": "connected", "attempt": 0, "accountId": "1001@sip.yalla.uz" }
  ```
- `incomingCall` → add `accountId` field
  ```json
  { "number": "+998901234567", "callId": "call-1", "accountId": "1001@sip.yalla.uz" }
  ```
- `callConnected` → add `accountId` field

### New Events

- `accountStatusChanged` → per-account state changes
  ```json
  { "accountId": "1002@sip.yalla.uz", "name": "Dispatch", "status": "disconnected" }
  ```
  **Note:** React frontend must add handler for this. If not handled, event is silently ignored (existing pattern).

### Agent Status Bridge

Bridge layer keeps all 5 internal values. UI maps to 3 display states.

- `setAgentStatus("away")` → still works (mapped to "Band" in UI)
- `setAgentStatus("break")` → still works (mapped to "Band" in UI)
- UI dropdown sends: "Liniyada" → `READY`, "Band" → `AWAY`, "Liniyada emas" → `OFFLINE`

---

## DI Module Changes

### SipModule (before → after)

```kotlin
// BEFORE
single { PjsipEngine(...) } binds arrayOf(
    SipStackLifecycle::class,
    RegistrationEngine::class,
    CallEngine::class,
)
single<ConnectionManager> { ConnectionManagerImpl(get()) }

// AFTER
single { PjsipEngine(...) } binds arrayOf(
    SipStackLifecycle::class,
    CallEngine::class,          // RegistrationEngine removed
)
single<SipAccountManager> { PjsipSipAccountManager(get()) }
// ConnectionManager removed — subsummed by SipAccountManager
```

### AuthModule

`LogoutOrchestrator` constructor: `RegistrationEngine` → `SipAccountManager`

### FeatureModule

`ComponentFactory.createMain()` receives `SipAccountManager` instead of `RegistrationEngine`.

---

## Yalla Design System Color Mapping

All colors from `uz.yalla.design.color.ColorScheme`:

### Dark Theme

| Token | Hex | Usage |
|-------|-----|-------|
| bg.base | #1A1A20 | Toolbar background |
| bg.secondary | #21222B | Button surfaces, phone field, tooltips |
| bg.tertiary | #1D1D26 | Settings dialog bg, dropdown selected |
| bg.brand | #562DF8 | Answer button, active SIP chip |
| border.disabled | #383843 | Dividers, default borders |
| border.error | #F42500 | Error field border |
| button.active | #562DF8 | Primary actions |
| button.disabled | #2C2D34 | Disabled buttons, muted SIP chips |
| icon.disabled | #C8CBFA | Disabled icon stroke, brand tint text |
| icon.subtle | #98A2B3 | Mute/hold icons, settings icon |
| icon.red | #F42500 | Hangup, reject, disconnected |
| text.base | #FFFFFF | Primary text |
| text.subtle | #747C8B | Placeholder, muted chip text |
| accent.pinkSun | #FF234B | "Band" status, reconnecting |
| gradient.splash | #7957FF → #562DF8 → #3812CE | Login background |

### Light Theme

| Token | Hex | Usage |
|-------|-----|-------|
| bg.base | #FFFFFF | Toolbar background |
| bg.secondary | #F7F7F7 | Button surfaces |
| bg.tertiary | #E9EAEA | Settings dialog bg |
| bg.brand | #562DF8 | Same — brand consistent |
| border.disabled | #E4E7EC | Dividers |
| border.error | #F42500 | Error borders |
| button.active | #562DF8 | Primary actions |
| button.disabled | #F7F7F7 | Disabled buttons |
| icon.disabled | #C8CBFA | Disabled icons |
| icon.subtle | #98A2B3 | Secondary icons |
| icon.red | #F42500 | Destructive actions |
| text.base | #101828 | Primary text |
| text.subtle | #98A2B3 | Placeholder text |
| accent.pinkSun | #FF234B | Busy/reconnecting |

### Theme Migration

Current `YallaColors` values must align with Yalla DS:
- `YallaColors.Dark.backgroundTertiary`: `#383843` → `#1D1D26` (DS value)
- `#383843` becomes `borderDisabled` (already matches DS)

---

## Test Impact

### Existing Tests That Break

| Test File | Reason | Fix |
|-----------|--------|-----|
| `LoginComponentTest` (7 tests) | `register(sipCredentials)` → `registerAll(accounts)` | Update to use `SipAccountManager`, `FakeRegistrationEngine` → `FakeSipAccountManager` |
| `ToolbarComponentTest` | `AgentStatus.AWAY` used directly | Update to new mapping, test 3 UI states |
| `BridgeIntegrationTest` | `BridgeState` gains `accounts` field | Update serialization assertions |
| `BridgeProtocolTest` | `BridgeState` data class changes | Update expected JSON |
| `CallFlowIntegrationTest` | `CallState.Ringing` gains `accountId` | Add `accountId` to test constructors |
| `BusyOperatorIntegrationTest` | Same `CallState` changes | Same fix |

### New Test Scenarios

1. Multi-account registration: all succeed / some fail / all fail
2. Per-account reconnection: account-2 drops while account-1 connected
3. Incoming call routing: call arrives on account-2, correct chip highlighted
4. Simultaneous incoming: second call auto-rejected with 486
5. SIP chip toggle: connect/disconnect individual accounts
6. Active call protection: cannot disconnect chip with active call
7. Auto-logout: only when ALL disconnected AND idle
8. Bridge `getState()` with mixed account states
9. Bridge `accountStatusChanged` event emission
10. i18n: both UZ/RU cover all strings, `staticCompositionLocalOf` crashes without provider
