# Multi-SIP Account Support + UI Rethink

**Date:** 2026-04-08
**Status:** Approved
**Scope:** Multi-SIP registration, toolbar redesign, login redesign, settings dialog, i18n

## Overview

Transform yalla-sip-phone from single SIP account to multi-SIP account support. All active SIP accounts from backend auto-register on login, calls happen on one line at a time. Complete toolbar content redesign using Yalla Design System palette. Login screen enlarged to match main window size for smooth transitions. Settings moved from popover to overlay dialog. Internationalization with UZ/RU support.

## Multi-SIP Architecture

### Domain Layer

New `SipAccountManager` interface alongside existing engines:

```kotlin
data class SipAccount(
    val id: String,
    val name: String,          // e.g. "Operator-1", "Dispatch"
    val credentials: SipCredentials,
    val state: SipAccountState, // Connected | Reconnecting | Disconnected
    val hasActiveCall: Boolean,
)

sealed interface SipAccountState {
    data object Connected : SipAccountState
    data object Reconnecting : SipAccountState
    data object Disconnected : SipAccountState
}

interface SipAccountManager {
    val accounts: StateFlow<List<SipAccount>>
    val activeCallAccount: StateFlow<SipAccount?>
    suspend fun connect(accountId: String): Result<Unit>
    suspend fun disconnect(accountId: String): Result<Unit>
    suspend fun registerAll(credentials: List<SipCredentials>): Result<Unit>
    suspend fun unregisterAll()
}
```

### Key Constraints

- `CallEngine` interface unchanged — one active call at a time
- `CallState.Ringing` gets new `accountId: String` field to identify which line receives the call
- `PjsipAccountManager` changes from `currentAccount: PjsipAccount?` to `Map<String, PjsipAccount>`
- All pjsip operations remain on `pjsip-event-loop` single-thread dispatcher

### Login Flow Change

Current:
```
me().sips.firstOrNull { it.isActive } → register(one)
```

New:
```
me().sips.filter { it.isActive } → registerAll(list)
→ All registered: navigate to Main
→ None registered: show error
→ Partial: navigate to Main, failed chips show as disconnected (red)
```

## Toolbar UI Redesign

### Layout (left to right, 52dp height)

```
[AgentStatus] [PhoneField] | [CallActions] [Timer?] ← [SipChips →] | [Settings]
```

| Component | Width | Description |
|-----------|-------|-------------|
| AgentStatusButton | 36dp | Icon button with status dot, dropdown on click |
| PhoneField | IntrinsicSize.Min | +998XXXXXXXXX format, tabular nums font |
| Divider | 1dp | `border.disabled` #383843 |
| CallActions | N × 36dp | State-dependent action buttons |
| CallTimer | auto | Brand tint surface, visible only during active call |
| SipChipRow | flex(1f) | LazyRow, items right-aligned |
| Divider | 1dp | `border.disabled` #383843 |
| SettingsButton | 36dp | Opens settings overlay dialog |

### Agent Status

3 states (reduced from 5):

| Status | Color | Yalla Token |
|--------|-------|-------------|
| Liniyada (На линии) | #562DF8 | button.active |
| Band (Занят) | #FF234B | accent.pinkSun |
| Liniyada emas (Не на линии) | #747C8B | text.subtle |

Dropdown menu (not popup) with 3 items. Single icon button — status dot indicates current state.

### Phone Field

- `Modifier.width(IntrinsicSize.Min)` — no hardcoded pixel width
- Monospace/tabular font for digits — consistent width regardless of digit values
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
- Mute — `bg.secondary` #21222B, `icon.subtle` #98A2B3
- Hold — same
- Transfer — same
- Timer — brand tint surface (`rgba(86,45,248,0.15)`, border `rgba(86,45,248,0.3)`, text `#C8CBFA`)

### SIP Chip States

| State | Background | Border | Text | Yalla Source |
|-------|-----------|--------|------|--------------|
| Connected | rgba(562DF8, 0.12) | rgba(562DF8, 0.3) | #C8CBFA | brand tint |
| Reconnecting | rgba(FF234B, 0.1) | rgba(FF234B, 0.25) | #FF234B | pinkSun tint |
| Disconnected | rgba(F42500, 0.1) | rgba(F42500, 0.25) | #F42500 | error tint |
| Active call | #562DF8 solid | #7957FF | #FFFFFF | brand solid |
| Muted (during call) | #2C2D34 | #383843 | #747C8B | button.disabled |

### SIP Chip Toggle Logic

- Click connected → `disconnect(accountId)` → Disconnected
- Click disconnected → `connect(accountId)` → Reconnecting → Connected
- Click reconnecting → ignore (transition state)
- Click connected with active call → warning: "Aktiv qo'ng'iroq bor, uzib bo'lmaydi"

### SIP Chip Hover Tooltip

Shows above chip on hover:
- SIP name (bold, #FFFFFF)
- Extension number (#98A2B3)
- Server (#98A2B3)
- Transport (#98A2B3)
- Status (colored: brand/pinkSun/error)
- If disconnected: "Bosing → qayta ulash" hint (#FF234B)

Tooltip style: `bg.secondary` #21222B, border `#383843`, rounded 8dp, shadow.

### Component File Structure

```
feature/main/toolbar/
├── ToolbarComponent.kt          // business logic, state management
├── ToolbarContent.kt            // main Row layout
├── AgentStatusButton.kt         // icon button + dropdown menu
├── PhoneField.kt                // intrinsic width, tabular nums
├── CallActions.kt               // state-dependent action buttons
├── CallTimer.kt                 // brand tint duration surface
├── SipChipRow.kt                // LazyRow + SipChip + hover tooltip
└── SettingsDialog.kt            // overlay dialog (replaces SettingsPopover)
```

Delete: `AgentStatusDropdown.kt`, `CallControls.kt`, `CallQualityIndicator.kt`, `SettingsPopover.kt`

## Login Screen Redesign

### Window Sizing

- Login and Main both use **1280×720**
- No window resize on navigation — transition is pure Compose animation (slide + fade)
- `resizable = false` on login, `resizable = true` on main
- `alwaysOnTop = false` on login, `alwaysOnTop = true` on main
- Remove AWT resize logic for login — single size for both screens

### Visual Design

- Background: splash gradient (`#7957FF → #562DF8 → #3812CE`, 135deg)
- Centered card: 320dp wide, glassmorphism (`rgba(1A1A20, 0.85)` + blur)
- Logo: 56dp rounded square, brand #562DF8, phone icon
- Title: "Yalla SIP Phone", 20sp, #FFFFFF
- Subtitle slot: 20dp fixed height — shows context text or error message

### States (all same card size)

**Default:**
- Subtitle: "Tizimga kirish" (#98A2B3)
- Password field: `bg.secondary`, `border.disabled`, lock icon, eye toggle
- Button: "Kirish", `button.active` #562DF8
- "Qo'lda ulanish" link (#747C8B)
- Version: #383843

**Loading:**
- Password field: disabled (opacity 0.5)
- Button: "Ulanmoqda..." with spinner, `bg.secondary` + brand border
- Spinner: brand color

**Error — Wrong Password:**
- Subtitle: "Parol noto'g'ri" (#F42500)
- Password field: `border.error` #F42500
- Button: "Qayta urinish", `button.active`

**Error — Network:**
- Subtitle: "Serverga ulanib bo'lmadi" (#FF234B)
- Password field: normal border
- Button: "Qayta urinish"

### Manual Connection Dialog

Stays as AlertDialog — no visual changes needed.

## Settings Dialog

### Structure

Overlay dialog (not popover/popup):
- Dimmed background (`rgba(0,0,0,0.6)`)
- Card: `bg.tertiary` #1D1D26, border #383843, rounded 14dp
- Close button: top-right, `bg.secondary` 28dp

### Content

1. **Header:** "Sozlamalar" title + close button
2. **Agent info card:** Avatar initials (brand bg) + name + agent ID
3. **Theme:** Label + 3 icon toggle (sun/moon/monitor), segmented control style
4. **Locale:** Label + 2 flag toggle (🇺🇿/🇷🇺), segmented control style
5. **Logout:** "Chiqish" button, error tint (`#450a0a` bg, `#F42500` text)
6. **Version:** "Yalla SIP Phone v1.0.0", #383843

### Removed

- Audio device selector (was planned, not needed now)
- English locale option

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
    // ... all UI strings
}

object UzStrings : StringResources { ... }
object RuStrings : StringResources { ... }

val LocalStrings = compositionLocalOf<StringResources> { UzStrings }
```

### Storage

- `AppSettings.locale: String` — "uz" or "ru"
- Default: "uz"
- Applied via `CompositionLocalProvider(LocalStrings provides strings)`

### Replaces

Current `Strings.kt` object → `StringResources` interface + implementations.

## JS Bridge Changes

### getState() Response

Add `accounts` field:
```json
{
  "agent": { "id": 1042, "name": "Islom" },
  "accounts": [
    { "id": "acc-1", "name": "Operator-1", "extension": "1001", "status": "connected" },
    { "id": "acc-2", "name": "Dispatch", "extension": "1002", "status": "connected" }
  ],
  "capabilities": ["makeCall", "transfer", ...]
}
```

### New/Modified Events

- `connectionChanged` → add `accountId` field
- New: `accountStatusChanged` → `{ accountId, name, status: "connected"|"reconnecting"|"disconnected" }`

## Yalla Design System Color Mapping

All colors from `uz.yalla.design.color.ColorScheme` (dark theme):

| Token | Hex | Toolbar Usage |
|-------|-----|---------------|
| bg.base | #1A1A20 | Toolbar background |
| bg.secondary | #21222B | Button surfaces, phone field, settings |
| bg.tertiary | #1D1D26 | Dropdown selected, settings dialog bg |
| bg.brand | #562DF8 | Answer button, active SIP chip, agent "Liniyada" |
| border.disabled | #383843 | Dividers, default borders |
| border.error | #F42500 | Error field border |
| button.active | #562DF8 | Primary actions |
| button.disabled | #2C2D34 | Disabled buttons, muted SIP chips |
| icon.disabled | #C8CBFA | Disabled icon stroke, brand tint text |
| icon.subtle | #98A2B3 | Mute/hold/transfer icons |
| icon.red | #F42500 | Hangup, reject, disconnected |
| text.base | #FFFFFF | Primary text |
| text.subtle | #747C8B | Placeholder, muted chip text |
| accent.pinkSun | #FF234B | "Band" status, reconnecting |
| gradient.splash | #7957FF → #562DF8 → #3812CE | Login background |
