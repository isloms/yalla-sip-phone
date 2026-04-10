# Manual Multi-SIP Connection for Testing

**Date:** 2026-04-10
**Status:** Draft
**Scope:** Multiple SIP account management via manual connection dialog + `lastRegisteredServer` bug fix
**Lifecycle:** Debug tool — will be removed before production release

---

## Overview

Enhance the manual connection dialog to support adding multiple SIP accounts for testing the SIP connection layer without a backend. Fix the `lastRegisteredServer` global state bug that causes incorrect call routing in multi-account scenarios.

**Not in scope:** call queue/waiting, post-login account management, account persistence across sessions, concurrent calls, account selection for outgoing calls.

---

## Part 1: `lastRegisteredServer` Bug Fix

### Problem

`PjsipAccountManager.lastRegisteredServer` is a single `String?` that gets overwritten by whichever account registers last. Both `makeCall()` and `transferCall()` in `PjsipCallManager` use this global value to build SIP URIs via `SipConstants.extractHostFromUri()`.

**Example:** Account A registers on `pbx1.example.com`, then Account B registers on `pbx2.example.com`. Now `lastRegisteredServer = "sip:200@pbx2.example.com"`. A call on Account A builds URI `sip:number@pbx2.example.com` — wrong server.

### Solution

Store the server hostname directly on `PjsipAccount`. The account object is already available at both call sites (`makeCall` has `acc`, `transferCall` can look up via `currentAccountId`).

### Changes

**`PjsipAccount`** — add `server` property:

```kotlin
class PjsipAccount(
    val accountId: String,
    val server: String,                    // NEW: plain hostname from credentials
    private val accountManager: PjsipAccountManager,
    private val pjScope: CoroutineScope,
) : Account()
```

**`PjsipAccountManager`** — pass server at creation time, remove `lastRegisteredServer`:

```kotlin
// REMOVE:
override var lastRegisteredServer: String? = null

// In updateRegistrationState(), REMOVE:
if (state is PjsipRegistrationState.Registered) {
    lastRegisteredServer = state.server
}

// In register(), pass credentials.server to PjsipAccount:
val account = PjsipAccount(accountId, credentials.server, this, pjScope).apply {
    create(accountConfig, true)
}
```

**`AccountProvider` interface** — remove `lastRegisteredServer` only, no new methods:

```kotlin
interface AccountProvider {
    fun getAccount(accountId: String): PjsipAccount?
    fun getFirstConnectedAccount(): PjsipAccount?
    // lastRegisteredServer REMOVED — server lives on PjsipAccount.server
}
```

Since `PjsipAccount` now has `server`, callers use `acc.server` directly or `getAccount(id)?.server`.

**`PjsipCallManager.makeCall()`** — `acc` is already resolved, read server directly:

```kotlin
// BEFORE:
val host = SipConstants.extractHostFromUri(accountProvider.lastRegisteredServer)
if (host.isBlank()) return Result.failure(...)

// AFTER:
val host = acc.server
```

**`PjsipCallManager.transferCall()`** — look up account by `currentAccountId`:

```kotlin
// BEFORE:
val host = SipConstants.extractHostFromUri(accountProvider.lastRegisteredServer)
if (host.isBlank()) return Result.failure(...)

// AFTER:
val callAccountId = currentAccountId
    ?: return Result.failure(IllegalStateException("No active call account"))
val host = accountProvider.getAccount(callAccountId)?.server
    ?: return Result.failure(IllegalStateException("No server for account $callAccountId"))
```

**`PjsipRegistrationState.Registered`** — rename misleading field:

```kotlin
// BEFORE:
data class Registered(val server: String)    // actually stores full URI like "sip:102@192.168.0.22"

// AFTER:
data class Registered(val uri: String)       // honest name
```

Update the one reference in `PjsipAccount.onRegState()`:

```kotlin
PjsipRegistrationState.Registered(uri = uri)
```

### Files Changed

| File | Change |
|------|--------|
| `data/pjsip/PjsipAccount.kt` | Add `server: String` constructor param |
| `data/pjsip/PjsipAccountManager.kt` | Remove `lastRegisteredServer`, pass server to PjsipAccount constructor |
| `data/pjsip/PjsipCallManager.kt` | Use `acc.server` in `makeCall`, `getAccount(id)?.server` in `transferCall` |
| `data/pjsip/PjsipRegistrationState.kt` | Rename `Registered.server` to `Registered.uri` |
| Test files referencing `lastRegisteredServer` | Update to new API |

---

## Part 2: Manual Connection Dialog — Multiple Accounts

### Current State

`ManualConnectionDialog` is an `AlertDialog` with 5 fields (server, port, username, password, dispatcherUrl). On submit, creates a single `SipAccountInfo` and calls `registerAll([singleAccount])`.

### New Behavior

The dialog becomes a two-zone layout:

1. **Top zone: Account List** — shows added accounts with status indicators and remove buttons
2. **Bottom zone: Add Form** — same fields as current dialog (server, port, username, password)
3. **Action buttons:** "Add" to add account to list, "Connect All" to register all and navigate

### UI Flow

```
User opens dialog
  → Empty list shown with "No accounts added" placeholder
  → User fills server, port, username, password
  → Clicks "Add" → account appears in list, form clears
  → User fills another account → "Add" → now 2 items in list
  → User clicks "Connect All"
  → LoginComponent.manualConnect(accounts: List<ManualAccountEntry>)
  → registerAll() called with full list
  → Wait for at least one Connected → navigate to Main
```

### Data Model

Local UI state only — no new domain model needed:

```kotlin
data class ManualAccountEntry(
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
)
```

Defined in `LoginComponent.kt` (not private) since both `LoginScreen` and `LoginComponent` reference it. Maps to `SipAccountInfo` at connect time inside `LoginComponent.manualConnect()`.

### Dialog UI Structure

```kotlin
@Composable
private fun ManualConnectionDialog(
    isLoading: Boolean,
    onConnect: (accounts: List<ManualAccountEntry>, dispatcherUrl: String) -> Unit,
    onDismiss: () -> Unit,
)
```

Layout:

```
┌─────────────────────────────────┐
│  Manual Connection              │
├─────────────────────────────────┤
│  ┌─────────────────────────────┐│
│  │ 102@192.168.0.22:5060   [x]││  ← account list (LazyColumn, max ~200dp height)
│  │ 103@192.168.0.22:5060   [x]││
│  │ 200@10.0.0.5:5060       [x]││
│  └─────────────────────────────┘│
│                                 │
│  Server: [___________________]  │  ← add form (same fields as current, minus dispatcherUrl)
│  Port: [5060]  Username: [___]  │
│  Password: [_________________]  │
│                                 │
│  Dispatcher URL: [___________]  │  ← single field, applies to entire session
│                                 │
│  [Cancel]  [Add]  [Connect All] │  ← 3 buttons
└─────────────────────────────────┘
```

**Validation:**
- "Add" enabled when server and username are non-empty
- "Connect All" enabled when account list is non-empty and not loading
- Duplicate detection: warn if same `username@server:port` already in list (prevent add, don't block)

**Account list item:**
- Display: `username@server:port`
- Remove button (x icon) — removes from local list
- No reordering needed

### LoginComponent Changes

**New `manualConnect` signature:**

```kotlin
fun manualConnect(accounts: List<ManualAccountEntry>, dispatcherUrl: String) {
    val accountInfos = accounts.map { entry ->
        val credentials = SipCredentials(
            server = entry.server,
            port = entry.port,
            username = entry.username,
            password = entry.password,
        )
        SipAccountInfo(
            extensionNumber = entry.username.toIntOrNull() ?: 0,
            serverUrl = entry.server,
            sipName = null,
            credentials = credentials,
        )
    }
    val authResult = AuthResult(
        token = "",
        accounts = accountInfos,
        dispatcherUrl = dispatcherUrl,
        agent = AgentInfo("manual", accounts.first().username),
    )
    _loginState.value = LoginState.Loading
    scope.launch(ioDispatcher) {
        registerAndNavigate(authResult, accountInfos)
    }
}
```

The old single-account `manualConnect(server, port, username, password, dispatcherUrl)` is **removed** — no backward compatibility needed since this is an internal debug tool.

### String Resources

New keys needed:

| Key | UZ | RU |
|-----|----|----|
| `manualAddAccount` | "Qo'shish" | "Добавить" |
| `manualConnectAll` | "Hammasini ulash" | "Подключить все" |
| `manualNoAccounts` | "Account qo'shilmagan" | "Аккаунты не добавлены" |
| `manualDuplicateAccount` | "Bu account allaqachon qo'shilgan" | "Этот аккаунт уже добавлен" |

Existing keys reused: `labelServer`, `labelPort`, `labelUsername`, `labelPassword`, `labelDispatcherUrl`, `buttonCancel`, `loginManualConnection`.

---

## Testing Strategy

### Unit Tests

1. **`lastRegisteredServer` fix:**
   - Register two accounts on different servers → `makeCall` on each → verify correct server in URI
   - `transferCall` uses `currentAccountId`'s server, not global
      - `getAccount(unknownId)?.server` returns null gracefully
   - After `unregister`/`destroy`, accounts map is cleaned up (server goes with account)

2. **LoginComponent multi-account:**
   - `manualConnect` with 3 accounts → `registerAll` receives all 3
   - Empty list → error
   - Navigation happens when at least one account connects

### Existing Test Updates

- `ScriptableRegistrationEngine` — update `lastRegisteredServer` references
- `ScenarioRunner` — update default server handling
- Any test mocking `AccountProvider` — remove `lastRegisteredServer` stub

---

## Files Changed Summary

| File | Part | Change |
|------|------|--------|
| `data/pjsip/PjsipAccount.kt` | 1 | Add `server` param |
| `data/pjsip/PjsipAccountManager.kt` | 1 | Remove `lastRegisteredServer`, add `getServerForAccount` |
| `data/pjsip/PjsipCallManager.kt` | 1 | Per-account server in `makeCall`/`transferCall` |
| `data/pjsip/PjsipRegistrationState.kt` | 1 | Rename `server` → `uri` |
| `feature/login/LoginScreen.kt` | 2 | New `ManualConnectionDialog` with list + add form |
| `feature/login/LoginComponent.kt` | 2 | New `manualConnect(List<ManualAccountEntry>, String)` |
| `ui/strings/StringResources.kt` | 2 | New string keys |
| `ui/strings/UzStrings.kt` | 2 | Uzbek translations |
| `ui/strings/RuStrings.kt` | 2 | Russian translations |
| Test files | 1+2 | Update for new APIs, add multi-account routing tests |
