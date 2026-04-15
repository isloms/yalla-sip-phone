# Update Demo — Design

**Date:** 2026-04-15
**Author:** Islom (via Claude)
**Branch:** `feature/update-demo`

## Context

The auto-update mechanism (`data/update/`, `domain/update/`, `feature/main/update/`) is production-ready. Its UI — `UpdateBadge`, `UpdateDialog`, `UpdateDiagnosticsDialog` — is driven purely by `StateFlow<UpdateState>` and `StateFlow<CallState>`. Every state transition in production requires tagging a new release and actually publishing (`v1.0.5`, `v1.0.6`, ...), which is too slow for iterating on UI copy, colors, or layout, and which only surfaces failure states like `DISK_FULL` or `VERIFY` fail by accident.

This spec introduces a standalone demo harness that renders the real production update UI composables and drives them through every state the machine can be in, without tagging releases, without network calls, and without touching `UpdateManager`.

## Goal

A developer runs `./gradlew runUpdateDemo`, a Compose Desktop window opens, and a control panel lets them trigger every state of the update UI (happy path, all failure modes, call-active install defer, locale swap, diagnostics dialog) either manually button-by-button or via a scripted auto-play sequence. The badge and dialog they see are the exact ones that ship to operators.

A parallel rename turns the existing `runDemo` (busy-operator SIP simulation) into `runSipDemo` so the two demos coexist as sibling Gradle tasks.

## Non-goals

- Not running the real `UpdateManager` state machine. It is covered by `UpdateManagerTest` + `UpdateE2ETest`.
- Not making real HTTP calls or real downloads.
- Not spawning the real C# bootstrapper or calling `msiexec`.
- Not exiting the JVM on "Install" click — the demo emulates the handoff without terminating.
- Not unit-testing the demo driver. It *is* the test harness.
- Not covering states that have no visible UI distinction (`Failed(CHECK)`, `Failed(INSTALL)`, downgrade refusal, in-memory blacklist).

## Architecture

Standalone Compose Desktop app in the **test source set** (same pattern as existing `DemoMain.kt`). Renders the real production composables from `feature/main/update/`. Drives state via a hand-owned `MutableStateFlow<UpdateState>` and `MutableStateFlow<CallState>`. Zero production code changes. Zero new dependencies.

### Why a direct StateFlow driver instead of the real `UpdateManager`

- `UpdateBadge` and `UpdateDialog` are driven purely by `StateFlow<UpdateState>` + `StateFlow<CallState>`. They don't know or care where the state originated.
- The state machine is already tested.
- Forcing specific states through the real manager would require crafting fake `UpdateApiContract` / `UpdateDownloaderContract` / `InstallerContract` implementations for every transition, reverse-engineering the orchestration logic along the way — exactly the kind of friction the demo exists to eliminate.
- A direct driver can hold any state indefinitely (the real manager auto-resets `Failed` after 1.5s).
- A direct driver can reproduce states deterministically for screenshots or recordings.

## Files

### New

All under `src/test/kotlin/uz/yalla/sipphone/demo/update/`:

- **`UpdateDemoMain.kt`** — `fun main()`, Compose `application { Window { ... } }`, wires driver + control panel + production composables.
- **`UpdateDemoDriver.kt`** — owns `MutableStateFlow<UpdateState>` + `MutableStateFlow<CallState>`, exposes read-only `StateFlow`s, provides one method per scenario button.
- **`UpdateDemoControlPanel.kt`** — `@Composable` button grid grouped into Core states / Failures / Scenarios / Settings; also renders a live "Current state" inspector strip.
- **`UpdateDemoAutoPlay.kt`** — scripted coroutine that walks the catalog with deterministic delays; supports Pause / Resume / Reset.
- **`UpdateDemoConsoleLogger.kt`** — timestamped stdout logging of state transitions, same style as the existing `DemoLogger`.

### Modified

- **`build.gradle.kts`** — rename `runDemo` → `runSipDemo`; add `runUpdateDemo` task (mirror of `runSipDemo`, different main class).
- **`.claude/CLAUDE.md`** — update the Quick Commands block to reflect the rename and the new task.

### Unchanged (reused as-is from production)

- `feature/main/update/UpdateBadge.kt`
- `feature/main/update/UpdateUi.kt` (`UpdateDialog`, `UpdateDiagnosticsDialog`)
- `ui/strings/LocalStrings`, `UzStrings`, `RuStrings`
- `ui/theme/YallaSipPhoneTheme`
- `domain/update/UpdateState`, `UpdateRelease`, `UpdateInstaller`
- `domain/CallState`

## Gradle tasks

```kotlin
// RENAMED from runDemo
tasks.register<JavaExec>("runSipDemo") {
    group = "demo"
    description = "Run visual demo with fake SIP engines simulating a busy operator day"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("uz.yalla.sipphone.demo.DemoMainKt")
    jvmArgs(
        "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
}

// NEW
tasks.register<JavaExec>("runUpdateDemo") {
    group = "demo"
    description = "Run visual demo of the auto-update UI — all states, failure modes, and interactions"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("uz.yalla.sipphone.demo.update.UpdateDemoMainKt")
    jvmArgs(
        "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
}
```

## Components

### `UpdateDemoDriver`

Owns and exposes:

- `val state: StateFlow<UpdateState>` — read-only view of `_state`
- `val callState: StateFlow<CallState>` — read-only view of `_callState`
- `val channel: StateFlow<UpdateChannel>` — stable / beta indicator
- `val locale: StateFlow<String>` — "uz" / "ru"

Scenario methods (each just sets `_state.value = <state>` or flips `_callState`):

- `reset()`, `showChecking()`
- `showDownloading(percentInt: Int)` — computes `bytesRead` from `percent × sampleRelease.installer.size`
- `showVerifying()`, `showReady()`, `showInstalling()`
- `failVerify()`, `failDownload()`, `failDiskFull()`, `failUntrustedUrl()`, `failMalformed()`
- `toggleCallActive()` — flips `_callState` between `Idle` and `Active(...)` with a fake `+998901112233` caller
- `toggleChannel()`, `cycleLocale()`
- `mockInstall()` — emulates `UpdateManager.confirmInstall()`: transitions to `Installing`, logs handoff, delays 3s, resets to `Idle` (no `exitProcess`)

Uses a single sample `UpdateRelease(version = "1.0.5", ...)` as the release payload across states.

### `UpdateDemoAutoPlay`

One suspending function that walks the catalog:

1. Idle → Checking → Idle (no update path, 2s)
2. Checking → Downloading (animated 0 → 100% over 6s, 100ms ticks)
3. Verifying (1s)
4. ReadyToInstall (3s, badge appears)
5. Toggle call active → waiting-for-call text (3s)
6. Toggle call inactive → Install enabled (2s)
7. Installing (mock, 3s) → console handoff line
8. Reset → Idle
9. Failed(MALFORMED_MANIFEST) → Idle (3s)
10. Failed(VERIFY) → Idle (3s)
11. Failed(DOWNLOAD) → Idle (3s)
12. Failed(DISK_FULL) → Idle (3s)
13. Failed(UNTRUSTED_URL) → Idle (3s)
14. Done — state inspector shows "demo complete"

Total runtime ~40 s. Pause freezes on the active step via an `AtomicBoolean` the loop checks between `delay()` calls. Reset cancels the job and sets `_state.value = Idle`.

### `UpdateDemoControlPanel`

Compose `@Composable` rendering a 4-section button grid:

- **Play controls:** Play All · Pause · Reset
- **Core states:** Idle · Checking · Download 0% / 25% / 50% / 75% / 100% · Verifying · Ready to Install · Installing (mock)
- **Failures:** Verify fail · Download fail · Disk full · Untrusted URL · Malformed manifest
- **Scenarios:** Toggle call active · Open diagnostics dialog
- **Settings:** Locale uz / ru · Channel stable / beta

Above the grid, a "Current state" inspector strip shows `state.value::class.simpleName` plus version if applicable, plus call state, plus channel, plus locale. Live-updates via `collectAsState()`.

### `UpdateDemoConsoleLogger`

Collects `driver.state` and `driver.callState` in a launched coroutine, prints a timestamped line per transition. Same format as the existing `DemoLogger`:

```
==================================================
  YALLA SIP PHONE — UPDATE DEMO
==================================================
[13:42:01] Idle
[13:42:02] Checking
[13:42:03] Downloading v1.0.5 (0%)
...
[13:42:09] ReadyToInstall v1.0.5
[13:42:12] Call became active — install deferred
[13:42:15] Call ended — install enabled
[13:42:17] [INSTALL HANDOFF] Would have spawned bootstrapper.exe --msi /tmp/fake.msi --expected-sha256 <64-hex> --parent-pid <pid>
```

## Window layout

Single Compose `Window`, `DpSize(1280.dp, 760.dp)`, resizable, **not always-on-top** (so the `AlertDialog` overlay isn't fought by z-order). Title: *"DEMO — Yalla SIP Phone Update UI"*.

```
┌──────────────────────────────────────────────────────────────────────┐
│  DEMO: Auto-Update UI     [ badge slot ]       current v1.0.4        │  ← 80dp toolbar-style strip
├──────────────────────────────────────────────────────────────────────┤
│  State: ReadyToInstall(v1.0.5)  Call: Idle  Channel: stable  Loc: uz │  ← 40dp live inspector
├──────────────────────────────────────────────────────────────────────┤
│  [ Play All ] [ Pause ] [ Reset ]                                    │
│                                                                      │
│  ── Core states ──                                                   │
│  [Idle] [Checking] [DL 0%] [25%] [50%] [75%] [100%]                  │
│  [Verifying] [Ready] [Installing]                                    │
│                                                                      │
│  ── Failures ──                                                      │
│  [Verify fail] [Download fail] [Disk full] [Untrusted] [Malformed]   │
│                                                                      │
│  ── Scenarios ──                                                     │
│  [Toggle call active]  [Open diagnostics]                            │
│                                                                      │
│  ── Settings ──                                                      │
│  Locale: [uz] [ru]    Channel: [stable] [beta]                       │
└──────────────────────────────────────────────────────────────────────┘
```

`UpdateDialog` and `UpdateDiagnosticsDialog` render as Material3 `AlertDialog` overlays on top of the window content — same as production.

## CompositionLocal wiring

`UpdateDialog` / `UpdateBadge` consume `LocalStrings.current`. `UpdateDemoMain` must wrap its root content in:

```kotlin
YallaSipPhoneTheme(isDark = false) {
    CompositionLocalProvider(LocalStrings provides stringsFor(driver.locale.value)) {
        UpdateDemoRoot(driver)
    }
}
```

…and recompose when `driver.locale` flips (use `collectAsState()` to read the locale and re-resolve `stringsFor(...)`).

## Install click behavior

Production `UpdateDialog` `onInstall = { updateManager.confirmInstall() }` → `exitProcess(0)`. In the demo:

```kotlin
onInstall = { driver.mockInstall() }
```

`mockInstall()` transitions to `Installing`, prints a handoff line to the console, delays 3 seconds, resets to `Idle`. The JVM never exits.

## Testing

The demo itself is the test harness — it exercises every input variant of the real production composables. No unit tests on the demo driver.

Existing `UpdateManagerTest` + `UpdateE2ETest` continue to cover the state machine. The demo is orthogonal.

Verification steps for the feature:

1. `./gradlew build` — confirms the new test-source file compiles and no production code was accidentally touched.
2. `./gradlew test` — existing 259 tests still pass.
3. `./gradlew runSipDemo` — confirms the rename works, existing SIP demo still runs.
4. `./gradlew runUpdateDemo` — manually verify every button, auto-play, dialog, locale swap, and call-active toggle.

## Risks

1. **`UpdateRelease` / `UpdateInstaller` field names** — to be confirmed against `domain/update/UpdateManifest.kt` during implementation. The design assumes fields `version`, `minSupportedVersion`, `releaseNotes`, `installer.url`, `installer.sha256`, `installer.size`.
2. **Locale recomposition** — `LocalStrings` must re-provide when `driver.locale` changes, otherwise the window will stay frozen on the initial language. Solved by wrapping the content in a composable that reads `driver.locale.collectAsState()` and picks the `StringResources` implementation inside the composition.
3. **`AlertDialog` on Compose Desktop with `alwaysOnTop = true`** — in some Compose Desktop versions this fights with dialog overlays. Mitigation: `alwaysOnTop = false` on the demo window.
4. **Pending `libs/pjsua2.jar` modification** — the working tree has an uncommitted `M libs/pjsua2.jar`. Demo commits must stage only the new/modified demo files, not `-A`.

## Out of scope (deliberate cuts)

- `Failed(CHECK)` / `Failed(INSTALL)` — no visual distinction from `Failed(DOWNLOAD)`.
- Downgrade refusal — silently returns to `Idle`, nothing to show.
- Blacklist after 3 verify failures — in-memory counter, no UI indication.
- Driving the real `UpdateManager` state machine — architectural decision above.
- Unit tests on demo code — it *is* the manual test harness.

## Dependencies between this and other work

- None. This is purely additive under `src/test/kotlin/uz/yalla/sipphone/demo/update/` plus two small file edits (`build.gradle.kts`, `.claude/CLAUDE.md`). Nothing else in the codebase moves. No production binary effect.
