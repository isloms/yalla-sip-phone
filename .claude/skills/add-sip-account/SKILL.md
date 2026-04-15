---
name: add-sip-account
description: Scaffold a new multi-SIP account in yalla-sip-phone's existing architecture. Adds the account config, wires it through SipAccountManager, and updates UI. Use when Islom wants to support an additional PBX or extension. Triggers include phrases like "add sip account", "new account", "add another sip", "multi-account", "add extension".
allowed-tools: Read, Write, Edit, Bash, Glob, Grep
---

# Add SIP Account

Add a new SIP account configuration to the multi-SIP setup. This app already supports multi-SIP (`SipAccountManager` + `PjsipSipAccountManager`) — we're adding one more account slot, not building the framework.

## Prerequisites

Read `rules/pjsip-threading.md` first. All changes here touch `sip/` which has strict threading rules.

## Gather Info

Ask Islom for:
- Account display name (e.g., "Operator 2")
- SIP URI (`sip:<ext>@<host>`)
- Username / password
- Realm (if needed)
- Server (Asterisk or Oktell)
- Codec preference (if non-default)
- Transport (UDP / TCP / TLS)

## Process

### 1. Add Account Config

Depending on how account persistence is structured (check `SettingsModule`), either:
- Add to a persisted list of accounts
- Add to an in-memory `AccountConfig` registry

### 2. Wire Through SipAccountManager

```kotlin
// Pseudocode — actual API depends on current state
sipAccountManager.addAccount(
    AccountConfig(
        id = AccountId("<new-id>"),
        displayName = "<name>",
        uri = "sip:<user>@<host>",
        username = "<user>",
        password = "<password>",
        server = Server.Asterisk,   // or .Oktell
    )
)
```

Verify `PjsipSipAccountManager` has exponential-backoff reconnect logic in place (it should — see session logs).

### 3. Register and Verify

Run the app, log in, verify the new account registers. Check:
- Logs show `onRegState` with status 200
- UI shows the account as "Registered"
- Outgoing call from this account reaches the target

### 4. Update Multi-Account Login Dialog (if needed)

If there's a login dialog that hardcodes the number of accounts, update it to allow the new one. Check `ui/login/` or similar.

### 5. Add a Test

Unit test in `src/test/kotlin/<...>/SipAccountManagerTest.kt`:

```kotlin
@Test
fun `adding a new account registers it independently`() = runTest {
    val fakeEngine = FakeSipEngine()
    val manager = SipAccountManager(fakeEngine)

    val config = testAccountConfig(id = "new-account")
    manager.addAccount(config)

    assertTrue(manager.accounts.value.any { it.id == config.id })
}
```

### 6. Commit

```
feat(sip): add support for <account-name> multi-SIP account

- SettingsModule: new account persisted
- SipAccountManager: registers independently via existing multi-SIP plumbing
- UI: login dialog updated to allow selecting this account
- Test: covers new account registration

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
```

## Non-goals

- Do NOT touch `PjsipEngine` or the dispatcher wiring — the multi-SIP machinery is already in place
- Do NOT add a new codec for this account unless Islom specifically asks — codec decisions are cross-account
- Do NOT skip `rules/pjsip-threading.md` discipline

## Reference

- `docs/architecture.md` — module map
- `docs/pjsip-guide.md` — PJSIP integration details
