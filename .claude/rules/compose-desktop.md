---
paths:
  - "**/ui/**/*.kt"
  - "**/feature/**/*.kt"
  - "**/Main.kt"
---

# Compose Desktop Rules

Compose Desktop is NOT the same as Compose for Android. Many patterns that work on Android silently misbehave on Desktop.

## Windows & Popups

### Prefer `Popup` and `DropdownMenu` over `DialogWindow`

`DialogWindow` creates a full OS window, which on some platforms:
- Takes focus unexpectedly
- Has a taskbar entry (bad UX for a dropdown)
- Breaks keyboard shortcut propagation
- Is slow to open

Use `Popup` anchored to a composable, or `DropdownMenu` when appropriate. Existing code still has `DialogWindow` in some places (like the unfinished `YallaDropdownWindow`) — migrate when you touch those files.

### `Window` vs `DialogWindow` vs `Popup`

| Case | Use |
|------|-----|
| Main app window | `Window` |
| Modal child (settings, login) | `DialogWindow` (rare — consider bottom sheet first) |
| Tooltip, menu, dropdown, context menu | `Popup` or `DropdownMenu` |
| Notification / toast | Custom overlay over main window |

## Threading

- UI updates: Compose handles this automatically via `LaunchedEffect` and `snapshotFlow`. Don't manually marshal to the main thread
- Background work: `rememberCoroutineScope()` inside composables, use `Dispatchers.IO` for IO, `Dispatchers.Default` for CPU, never block the UI thread
- **Never touch PJSIP from composable functions directly** — go through the DI-injected `SipAccountManager` / `CallEngine` interfaces (real impls live in `data/pjsip/`)

## State

- Use `StateFlow` for shared state exposed to Compose (via `collectAsState()`)
- Use `mutableStateOf` + `remember` for Compose-only local state
- Never use plain `var` in composable closures — Compose won't recompose on change
- `derivedStateOf` for computed values that depend on multiple states

## StringResources (i18n)

This app uses a `StringResources` interface exposed via the `LocalStrings` CompositionLocal for i18n (Uzbek / Russian). Implementations are `UzStrings.kt` and `RuStrings.kt` in `ui/strings/`. Do NOT hardcode user-visible strings. Always:

```kotlin
@Composable
fun LoginScreen() {
    val strings = LocalStrings.current
    Text(strings.loginTitle)
}
```

**String keys are camelCase**, not snake_case. `strings.loginTitle`, not `strings.login_title`.

**When you add a new string**, add it to the `StringResources` interface AND to both `UzStrings.kt` and `RuStrings.kt` in the same commit. The compiler enforces this because they implement the interface.

## Design Tokens

Use the CompositionLocals `LocalYallaColors.current` and `LocalAppTokens.current` from the design system. `YallaColors` and `AppTokens` are `data class`es, not singletons — always go through the CompositionLocal.

```kotlin
// Wrong
Text(text = "Hello", color = Color(0xFF2196F3))
Text(text = "Hello", color = YallaColors.Primary)   // wrong: YallaColors is not a singleton

// Right
val colors = LocalYallaColors.current
val tokens = LocalAppTokens.current
Text(text = "Hello", color = colors.brandPrimary, fontSize = tokens.fontSizeBody)
```

Available color slots on `YallaColors` (data class): `brandPrimary`, `brandPrimaryMuted`, `brandPrimaryText`, `brandLight`, `backgroundBase`, `backgroundSecondary`, `backgroundTertiary`, `textBase`, `textSubtle`, `borderDefault`, `borderStrong`, `errorText`, `destructive`, `statusWarning`, `statusOnline`, `surfaceMuted`, `iconSubtle`, `callReady`, `callIncoming`, `callMuted`, `callOffline`, `callWrapUp`. Light / Dark variants live in `YallaColors.Light` / `YallaColors.Dark` companion objects (provided via `Theme.kt`). Use `statusOnline` (green) for agent "online" dot and SIP "connected" chip — it's the universal success-state color; `brandPrimary` (purple) is reserved for brand identity and the *active call* chip.

## Navigation

This app uses **Decompose** for navigation and component lifecycle (`RootComponent` + `ComponentFactoryImpl` in `navigation/`). Don't roll your own `var screen by mutableStateOf(...)` pattern — use Decompose's `ChildStack` / `StackNavigation`.

## Performance

- Avoid recomposing expensive UI on every state change. Use `derivedStateOf` and `remember` with keys
- Long lists: use `LazyColumn` / `LazyVerticalGrid` — never a plain `Column` with `forEach`
- Heavy images: use Coil's compose-desktop integration if added, never `BitmapFactory` or JavaFX image loading

## JCEF Integration

This app uses JCEF for embedded browser (auth flow). The JS bridge is in `data/jcef/` (files: `BridgeRouter`, `BridgeProtocol`, `BridgeSecurity`). Docs: `docs/js-bridge-api.md`.

- JCEF runs on its own thread. Don't call Compose from JCEF callbacks directly — dispatch via a Channel or StateFlow
- JCEF disposal is handled by the existing wrapper — don't roll your own cleanup

## Review Checklist

- [ ] No raw `Color(0xFF...)` or magic dimensions
- [ ] No static `YallaColors.X` or `AppTokens.X` access — always through `LocalYallaColors.current` / `LocalAppTokens.current`
- [ ] No hardcoded user strings — use `LocalStrings.current` + camelCase keys
- [ ] `DialogWindow` only when necessary (usually `Popup` is better)
- [ ] `StateFlow` for shared state, `mutableStateOf` for local
- [ ] No PJSIP calls from composables — route through DI-injected interfaces
- [ ] No `Dispatchers.Main` misuse (Compose handles main-thread updates)
- [ ] Lists use `LazyColumn` / `LazyVerticalGrid`
- [ ] Navigation uses Decompose, not manual state
