# Auto-Update Mechanism — Design Spec

**Status:** Approved — ready to implement
**Author:** Islom + Claude (Opus 4.6, 1M context)
**Date:** 2026-04-13
**Scope:** Client-side auto-update flow for yalla-sip-phone Windows desktop app, plus the contract the backend team will implement.

## 1. Problem & Goals

Operators at Ildam run yalla-sip-phone as a Windows MSI on their call-center workstations. Today there is no way to push a new version to the fleet; every operator needs a manual re-install by IT. This:

- Slows down bugfix delivery (days, not minutes).
- Leaves operators stuck on known-broken versions.
- Costs IT time for every release.

**Goal:** an auto-update system where a new release reaches every operator's machine within one hour of publishing, **without ever interrupting an active SIP call** and without requiring local admin rights.

## 2. Non-Goals

- macOS / Linux auto-update (out of scope; `InstallerStrategy` abstraction leaves the door open).
- Code-signing MSIs (product stance: no Authenticode cert).
- Manifest cryptographic signing (documented as v2 security debt).
- TLS certificate pinning (covered by HTTPS + intranet-zone trust).
- Delta / incremental downloads (~358 MB full MSI over LAN is acceptable).
- Dedicated client-side telemetry SDK (observability comes from backend poll logs).
- Staged rollout, kill-switch, and A/B cohorts for MVP (server can implement later by swapping the manifest).
- Markdown rendering library for release notes (plain `Text` is fine).

## 3. Constraints

| # | Constraint | Source |
|---|---|---|
| C1 | Windows only (MSI) for MVP | Product |
| C2 | Per-user install to `%LOCALAPPDATA%`; no UAC prompt | Product (operators lack admin) |
| C3 | Unsigned MSI and unsigned bootstrapper.exe | Product (no certs ever) |
| C4 | Call duration is typically 0-2 minutes — no grace period escalation needed | Operations |
| C5 | LAN-only deployment (backend reachable over intranet) | Operations |
| C6 | Client side written in Kotlin/Compose Desktop; no new dependencies | Tech |
| C7 | Backend team implements the server side — we hand them a contract, not code | Organization |
| C8 | TDD mandatory — tests first per project SDLC | CLAUDE.md |

## 4. High-Level Architecture

```
 ┌─────────────────────────────────────────────────────────────┐
 │                   yalla-sip-phone (client)                  │
 │                                                             │
 │   ┌──────────────┐   ┌──────────────────┐                   │
 │   │ UpdateBadge  │◄──│  UpdateManager   │                   │
 │   │   (Compose)  │   │  Koin single     │                   │
 │   │ UpdateDialog │   │  StateFlow<State>│                   │
 │   │ DiagDialog   │   └───────┬──────────┘                   │
 │   └──────────────┘           │                              │
 │                              │ uses                         │
 │                              ▼                              │
 │   ┌──────────┐  ┌──────────┐  ┌────────────────┐            │
 │   │  Check   │  │ Download │  │   CallEngine   │            │
 │   │   Api    │  │  (Ktor)  │  │ (existing —    │            │
 │   └────┬─────┘  └────┬─────┘  │  callState)    │            │
 │        │             │         └───────▲────────┘           │
 │        │             ▼                 │ idle gate          │
 │        │      ┌──────────┐              │                   │
 │        │      │ Sha256   │              │                   │
 │        │      │ Verifier │              │                   │
 │        │      └────┬─────┘              │                   │
 │        │           ▼                    │                   │
 │        │      ┌──────────────────────┐  │                   │
 │        │      │  MsiBootstrapper     │──┘                   │
 │        │      │  Installer           │                       │
 │        │      └────────┬─────────────┘                       │
 │        │               │  spawn bootstrapper.exe             │
 │        │               │  + exitProcess(0)                   │
 └────────┼───────────────┼─────────────────────────────────────┘
          │               │
          │ HTTPS         │
          ▼               ▼
 ┌──────────────────┐  ┌───────────────────────┐
 │ Backend API      │  │  bootstrapper.exe     │
 │ GET /api/v1/     │  │  (C# .NET helper,     │
 │   app-updates/   │  │   shipped in MSI)     │
 │   latest         │  │  waits for PID death, │
 │                  │  │  runs msiexec,        │
 │ + MSI hosted at  │  │  relaunches new exe,  │
 │   manifest.url   │  │  quarantines old exe  │
 └──────────────────┘  └───────────────────────┘
```

## 5. The 15 Invariants (updated from review round)

These are enforced by tests and documented in code where applicable.

| # | Invariant | Why |
|---|---|---|
| **I1** | **Active SIP call is sacred.** `UpdateManager` sets `installInProgress = true` atomically and the SIP stack refuses new `INVITE`s while the flag is true. Install is never scheduled while `CallEngine.callState != Idle`. | Dropping a live operator call is catastrophic business impact. |
| I2 | **Download is resumable** via HTTP `Range` header with a `.part` sidecar file storing expected SHA256 + total size. Partial files whose expected SHA256 no longer matches the latest manifest are discarded. | 358 MB over a tired LAN switch drops often. |
| I3 | **SHA256 is the only byte-integrity truth.** Content-Length and manifest `size` are hints, not authorities. | No code signing ⇒ SHA256 is our only integrity check. |
| I4 | **Pre-flight before network.** Verify free disk ≥ `size × 2` on the target drive and write permission on the updates dir before starting download. | Silent failures cascade into disk-full storms. |
| I5 | **Temp files GC'd on app startup.** Orphans from prior crashes (`.part`, `.msi.partial`, untracked `.msi`) are removed. **Exception: files referenced by an active install sentinel are preserved.** | Crash safety. |
| I6 | **Bootstrapper pattern is mandatory** — app never invokes `msiexec` directly while holding its own DLLs. Reason: RestartManager cannot evict JCEF native locks (`libcef.dll`, `jcef_helper.exe` child processes, `jcef-cache` lock) within its 30s window, nor pjsip's `libpjsua2.dll` mapped into the JVM address space. File replacement requires parent-process death first. | Documented so a future engineer doesn't "simplify" the bootstrapper away. |
| I7 | **Install sentinel + recovery is the bootstrapper's job**, not the new app's. Bootstrapper pre-copies the old install to `%LOCALAPPDATA%\YallaSipPhone\backup\`. On non-zero `msiexec` exit the bootstrapper restores the backup and relaunches the old exe. Success clears the backup. | Mid-install failure must not leave the fleet bricked. |
| I8 | **`msiexec` args come from the client's `InstallerArgs` constant** (`/qn /norestart REBOOT=ReallySuppress`), passed as separate argv to the bootstrapper, never string-concatenated. | Injection safety. |
| I9 | **Semver comparison, not equality.** `UpdateManager` uses a pure Kotlin semver comparator. `minSupportedVersion` forces upgrade declaratively; `mandatory` flags were rejected in favor of declarative upgrade floors. |
| I10 | **Capped exponential backoff** on network failures: `30s, 60s, 120s, ..., 30min`. Max 5 attempts per poll cycle. | No unbounded retries. |
| I11 | **Single-instance file-lock** on `%LOCALAPPDATA%\YallaSipPhone\updates\updater.lock`. Prevents two yalla.exe instances from racing the updater. | Defense in depth. |
| I12 | **Credentials survive upgrade because multiplatform-settings stores to Java Preferences (`HKCU\Software\JavaSoft\Prefs\uz\yalla\sipphone`), which MSI does not manage.** Verified by test: save credential, simulate upgrade path, reload. | Correct explanation, not placebo WiX attributes. |
| I13 | **Version blacklist persisted locally.** 3 consecutive verify or install failures on the same version → skip until the manifest's `version` field moves past the blacklisted version. | Stops pathological retry loops. |
| I14 | **No modal dialogs during an active SIP call.** Toolbar badge only. The dialog, if already open, is not force-closed but the "Install" button is disabled. | UX respects operator focus. |
| I15 | **Downgrades refused.** `if manifest.version ≤ currentVersion → NoUpdate`. Rollback is implemented as forward-roll (republish old bits with a higher version number). |
| **I16** | **No polling or downloading during an active call.** If `CallEngine.callState != Idle` when a tick fires, the check is skipped (logged, re-tried next tick). | No fighting SIP for LAN bandwidth. |
| **I17** | **Manifest schema is validated before any field is trusted.** `size > 0 && size < 2 GiB`, `sha256` matches hex regex `^[0-9a-f]{64}$`, `version` parses as semver, `minSupportedVersion` parses as semver, `minSupportedVersion ≤ version`. Any violation → `Failed(MALFORMED_MANIFEST)`, treated as `NoUpdate`. | A bad manifest must not brick clients. |
| **I18** | **Zone.Identifier ADS is stripped** from the downloaded MSI after successful SHA256 verification. This reduces SmartScreen friction without requiring a certificate. | Cert-free mitigation for Mark-of-the-Web. |
| **I19** | **UpgradeCode is pinned** in `build.gradle.kts` via `nativeDistributions.windows.upgradeUuid`. Without this, every install is side-by-side. | Table-stakes MSI hygiene. |

## 6. The Contract (what the backend team implements)

### 6.1 Endpoint

```
GET /api/v1/app-updates/latest
Headers:
  X-App-Version:  1.2.0
  X-App-Platform: windows
  X-App-Channel:  stable | beta
  X-Install-Id:   <uuid-v4-stable-per-machine>
  User-Agent:     YallaSipPhone/1.2.0 (Windows; x64)
Auth: none required (public endpoint). JWT MAY be forwarded if backend requires it.
```

### 6.2 Response — 200 OK, update available

```json
{
  "updateAvailable": true,
  "release": {
    "version": "1.2.0",
    "minSupportedVersion": "1.0.0",
    "releaseNotes": "Bug fixes and stability improvements",
    "installer": {
      "url": "https://downloads.yalla.uz/releases/YallaSipPhone-1.2.0.msi",
      "sha256": "abc123...",
      "size": 358400000
    }
  }
}
```

### 6.3 Response — 200 OK, client is up-to-date

```json
{ "updateAvailable": false }
```

**Not 204.** A 200 envelope is used for forward-compatibility: new fields can be added over the years without changing the response shape. A 204 body is dead-on-arrival when the schema needs to grow.

### 6.4 Response headers

```
Cache-Control: private, max-age=60, must-revalidate
Vary: X-App-Version, X-App-Platform, X-App-Channel
```

### 6.5 Forward-compat rules (MUST go into the OpenAPI doc)

1. Clients MUST ignore unknown JSON fields.
2. Servers MUST NOT remove or repurpose existing field names.
3. Servers MUST NOT change a field's type.
4. New required behavior ships as a new field with a safe default for old clients.
5. `/v1/` lives forever; `/v2/` is only for breaking changes.

### 6.6 Field-level rules

| Field | Type | Required | Rules |
|---|---|---|---|
| `updateAvailable` | boolean | yes | Drives client branch |
| `release.version` | string | yes when `updateAvailable` | Strict semver, no `v` prefix, no build metadata |
| `release.minSupportedVersion` | string | yes when `updateAvailable` | Must be `≤ release.version`; if client's current version is strictly less, force-upgrade UX is used |
| `release.releaseNotes` | string | no | Plain text, no HTML. Short. |
| `release.installer.url` | string | yes | Absolute HTTPS URL. Client rejects non-https or host not in allow-list. |
| `release.installer.sha256` | string | yes | Lowercase hex, exactly 64 characters |
| `release.installer.size` | number | yes | JSON number, 1 ≤ size < 2 GiB |

### 6.7 Download-host allow-list

Hard-coded in Kotlin source:
```kotlin
internal val UPDATE_URL_ALLOWLIST = listOf(
    "downloads.yalla.uz",
    "updates.yalla.local",
)
```
Any manifest returning a URL whose host is not in this list is rejected as `Failed(UNTRUSTED_URL)`.

### 6.8 Backend team deliverables (not our code)

- The endpoint above.
- Static hosting for the MSI files under one of the allow-list hosts.
- A release pipeline for publishing a new version (out of scope for this client-side spec — the client has a separate release runbook).
- Access log of the manifest endpoint (this *is* our observability).

**Open for backend team discussion:** auth (public vs. JWT), exact hosting choice, CI handoff (admin API vs. direct upload).

## 7. State Machine

Simpler than the original 9-state draft — collapsed per Agent 3's simplicity review.

```
Idle ──tick/start──> Checking ──updateAvailable=false──> Idle
                        │
                        ├─updateAvailable=true─> Downloading(progress) ──ok──> Verifying
                        │                                                        │
                        │                                                        ├─sha_ok─> ReadyToInstall
                        │                                                        └─sha_bad─> Failed(VERIFY)
                        │
                        └─err─> Failed(CHECK) ──next tick──> Checking

ReadyToInstall
  ├─ user clicks Install + CallState.Idle ──> Installing ──(msiexec spawns, app exits)──> [new process]
  ├─ user clicks Install + not Idle ──> wait for Idle, then Installing
  └─ user clicks Later ──> Idle (re-check next tick)

Failed(*) ──next tick──> Checking   (backoff applies to CHECK/DOWNLOAD; VERIFY 3x → blacklist)
```

**Key simplifications vs. original draft:**
- `Checking` state has no UI presence — invisible to the operator.
- `Available` and `ReadyToInstall` are merged; with fast LAN, verify completes in seconds.
- `WaitingForIdleCall` is not a state; it's a UI predicate `canInstallNow = state is ReadyToInstall && callState is Idle`.
- `Installing` lives for ~100 ms before `exitProcess(0)`; visible only as a brief snackbar.

## 8. File / Package Layout

All under `uz.yalla.sipphone.*`. Target: **10 Kotlin files + 1 C# project + 1 WiX addition**.

```
src/main/kotlin/uz/yalla/sipphone/
├── domain/update/
│   ├── UpdateManifest.kt          // @Serializable DTO envelope + Release + Installer
│   ├── UpdateState.kt             // sealed interface UpdateState + UpdateChannel enum
│   └── SemverComparator.kt        // pure Kotlin semver parser + comparator
├── data/update/
│   ├── UpdateApi.kt               // Ktor call to GET /app-updates/latest
│   ├── UpdateDownloader.kt        // Ktor Range-based streaming download with .part sidecar
│   ├── Sha256Verifier.kt          // MessageDigest over file, returns Boolean
│   ├── UpdatePaths.kt             // temp dir locations, GC helper
│   ├── MsiBootstrapperInstaller.kt// spawns bootstrapper.exe + exitProcess, MOTW strip
│   └── UpdateManager.kt           // orchestrator: tickerFlow + state machine + Koin single
├── feature/main/update/
│   ├── UpdateBadge.kt             // Compose badge in toolbar
│   └── UpdateUi.kt                // AlertDialog + DiagnosticsPanel composables
└── di/
    └── UpdateModule.kt            // Koin bindings

src/main/resources/
├── wix/main.wxs                   // custom WiX template (UpgradeCode pin + MajorUpgrade + perUser)
└── bootstrapper/                  // ships bootstrapper.exe into the MSI
    └── yalla-update-bootstrap.exe (build artifact, committed for now)

bootstrapper/                      // separate C# project, dotnet publish
├── YallaUpdateBootstrap.csproj
└── Program.cs                     // ~150 LoC

src/main/kotlin/uz/yalla/sipphone/ui/strings/
├── StringResources.kt             // + update strings
├── UzStrings.kt                   // + translations
└── RuStrings.kt                   // + translations
```

**Cuts vs. original 18-file blueprint:**
- `UpdateCheckRepository` interface + impl merged into `UpdateApi`
- `UpdateDownloader` interface dropped (single impl)
- `UpdateVerifier` interface dropped (single impl)
- `InstallerStrategy` interface kept but only one impl, tiny
- `UpdateStateHolder` merged into `UpdateManager`
- `DefaultUpdateOrchestrator` merged into `UpdateManager`
- `UpdateCheckResult` sealed class replaced with `UpdateManifest?`

## 9. Tests (TDD first)

Under `src/test/kotlin/uz/yalla/sipphone/data/update/`:

**🔴 Write these first (RED)** — they encode the safety invariants before the implementation exists:

1. `UpdateManagerTest > manager waits for CallState Idle before transitioning to Installing`
2. `UpdateManagerTest > manager refuses manifest whose url host is not in allow-list`
3. `UpdateManagerTest > manager refuses manifest whose version is lower or equal to current`
4. `Sha256VerifierTest > verify returns false on tampered file`
5. `Sha256VerifierTest > verify returns true on correct hash`
6. `UpdateApiTest > parses 200 envelope with updateAvailable true`
7. `UpdateApiTest > parses 200 envelope with updateAvailable false`
8. `UpdateApiTest > rejects malformed manifest — negative size`
9. `UpdateApiTest > rejects malformed manifest — sha256 wrong length`
10. `UpdateApiTest > rejects malformed manifest — minSupportedVersion greater than version`
11. `UpdateDownloaderTest > resumes from partial file via Range header when part file exists`
12. `UpdateDownloaderTest > discards partial file when expected SHA256 differs`
13. `UpdatePathsTest > cleanupPartials removes stale .part files older than 24h`
14. `SemverComparatorTest > 1.2.0 greater than 1.1.9`
15. `SemverComparatorTest > 1.2.0 equal to 1.2.0`
16. `UpdateManagerTest > manager skips check when call state is not idle (I16)`
17. `UpdateManagerTest > manager blacklists version after 3 verify failures`
18. `UpdateManagerTest > manager falls back to previous state on failed check`

**Integration (ktor-client-mock):**
19. `UpdateApiIntegrationTest > full happy path with mock server`
20. `UpdateApiIntegrationTest > handles 5xx with exponential backoff`

**Manual (not automated):**
- `MsiBootstrapperInstaller` on a real Windows 11 VM.
- The bootstrapper's recovery path (kill msiexec mid-flight).

## 10. UI — strings & flow

### 10.1 Strings added to `StringResources`

```
val updateAvailableBadge: String            // "Yangilanish mavjud"
val updateAvailableDialogTitle: String      // "Yangilanish mavjud"
val updateInstallButton: String             // "O'rnatish"
val updateLaterButton: String               // "Keyinroq"
val updateWaitingForCallMessage: String     // "Qo'ng'iroq tugashini kuting"
val updateDownloadingMessage: String        // "Yuklanmoqda... {0}%"
val updateVerifyingMessage: String          // "Tekshirilmoqda..."
val updateInstallingMessage: String         // "O'rnatilmoqda..."
val updateFailedVerify: String              // "Fayl buzilgan. Qayta urinib ko'ring."
val updateFailedDownload: String            // "Yuklab bo'lmadi. Tarmoqni tekshiring."
val updateFailedDisk: String                // "Diskda joy yetarli emas"
val updateReleaseNotesHeader: String        // "Nima yangi?"
val updateDiagnosticsTitle: String          // "Diagnostika"
val updateDiagnosticsCopy: String           // "Nusxa olish"
val updateCurrentVersion: String            // "Joriy versiya"
val updateChannelToggleSwitched: String     // "Kanal almashtirildi: {0}"
```

Uzbek and Russian translations are added to `UzStrings` and `RuStrings`.

### 10.2 Toolbar badge

A small icon button in `feature/main/MainScreen` toolbar. Visible when `state ∉ {Idle}`. Click opens the update dialog. Tooltip shows the version.

### 10.3 Dialog

Material 3 `AlertDialog`. Content:
- Header: "Yangilanish mavjud — v1.2.1"
- "Nima yangi?" + release notes as `Text` (plain, line breaks preserved)
- Progress bar when `Downloading`
- Buttons:
  - **Install** (disabled when not `ReadyToInstall`; disabled with helper text when call is active)
  - **Later** (moves state back to `Idle` for this tick)
- If `minSupportedVersion > currentVersion`: button label flips to "O'rnatish" + "Later" is hidden. Operator can't skip.

### 10.4 Diagnostics panel (hidden)

Global shortcut `Ctrl+Shift+Alt+D` (wired in `Main.kt` AWT key listener) opens a modal showing:
- `X-App-Version: <currentVersion>`
- `X-Install-Id: <uuid>`
- `UpdateState: <state>`
- Last check timestamp
- Last error
- Log tail (last 50 lines from Logback)
- Big "Nusxa olish" button → copies everything to clipboard as plain text.

### 10.5 Channel toggle (hidden)

Global shortcut `Ctrl+Shift+Alt+B` (wired in `Main.kt`):
- Toggles `AppSettings.updateChannel` between `stable` and `beta`.
- Shows a snackbar: "Kanal: beta".
- Requires a confirm dialog to prevent accidental activation.
- About dialog displays the current channel for sanity.

## 11. C# Bootstrapper

### 11.1 Location and deliverable

`bootstrapper/YallaUpdateBootstrap.csproj` — a standalone .NET 8 Windows console app, `PublishSingleFile=true`, `SelfContained=false` (uses framework from target machine — operators usually have .NET 8 Desktop Runtime; if not, the MSI installs it as a prerequisite).

Published artifact `yalla-update-bootstrap.exe` is committed to `src/main/resources/bootstrapper/` and copied into the MSI via a WiX component.

### 11.2 Command-line interface

```
yalla-update-bootstrap.exe \
  --msi <absolute-path-to-downloaded.msi> \
  --install-dir <absolute-path-to-current-install> \
  --parent-pid <pid-of-yalla.exe> \
  --expected-sha256 <hex64> \
  --log <absolute-path-to-log-file>
```

All args are required; missing args → fail-fast with usage message.

### 11.3 Behaviour

1. **Parse args**, open log file.
2. **Re-verify SHA256** of the MSI (defense in depth — client already verified, but the MSI file could have been swapped between verify and exec).
3. **Wait for parent process death** via `Process.WaitForExit()` on a `Process.GetProcessById(parentPid)` handle, with 60s timeout. After exit, wait an additional 3 s for Windows to release JCEF/pjsip file locks.
4. **Strip Mark-of-the-Web** ADS from the MSI: `File.Delete(msi + ":Zone.Identifier")`.
5. **Quarantine old install** — copy `<install-dir>\**` to `<install-dir>\..\backup\<old-version>\`. On copy failure, log and abort (do not proceed).
6. **Run `msiexec /i <msi> /qn /norestart REBOOT=ReallySuppress /L*v <log-dir>\install.log`**. Capture exit code.
7. **If exit code ∈ {0, 3010 (success + needs reboot)}:** delete quarantine. Find new `YallaSipPhone.exe` (MSI may have changed path). Launch it. Exit 0.
8. **If exit code ∈ {1602 (user cancel), 1618 (another install in progress)}:** do not restore quarantine. Relaunch old `YallaSipPhone.exe`. Exit with the msiexec code.
9. **Any other exit code:** restore quarantine (`Copy -Force` back), relaunch old `YallaSipPhone.exe`. Exit non-zero.

### 11.4 Logging

All steps are written to the log file at `--log`. On any exception, dump the full stack trace. The yalla-sip-phone updater reads this file on next launch and surfaces the last line in the Diagnostics panel.

### 11.5 Build

```bash
cd bootstrapper
dotnet publish -c Release -r win-x64 --self-contained=false -p:PublishSingleFile=true
cp bin/Release/net8.0/win-x64/publish/YallaUpdateBootstrap.exe ../src/main/resources/bootstrapper/yalla-update-bootstrap.exe
```

Committed to git so Gradle builds work without .NET installed on the dev machine. A GitHub Actions workflow rebuilds the artifact from source on tag.

## 12. WiX customization

Compose Desktop (`jpackage`) generates a basic MSI. Three settings are critical:

1. **UpgradeCode pinned.** Added to `build.gradle.kts`:
   ```kotlin
   nativeDistributions {
       targetFormats(TargetFormat.Msi)
       windows {
           upgradeUuid = "E7A4F1B2-9C5D-4E8A-B1F6-2D3E4F5A6B7C"
           perUserInstall = true
           menuGroup = "Yalla"
           shortcut = true
           menu = true
       }
   }
   ```

2. **Per-user install.** Enabled via `perUserInstall = true` above. This emits `InstallScope="perUser"` into the generated WiX, installs to `%LOCALAPPDATA%\YallaSipPhone`, and does not prompt for UAC.

3. **Bootstrapper bundled.** A new resource directory holds an override WiX fragment that adds `yalla-update-bootstrap.exe` as a file component. `jpackage --resource-dir` is passed via `nativeDistributions.windows.resourceDir` (TODO: verify Compose Desktop DSL exposes this; if not, a post-processing Gradle task copies the file into the `.image` directory before packaging).

**Known unknowns — flagged for manual MSI decompile with Orca after first build:**
- Whether Compose Desktop actually emits `InstallScope="perUser"` or just `ALLUSERS=2`.
- Whether UpgradeCode is honored and downgrades are blocked by the generated `MajorUpgrade` element.
- Whether `--resource-dir` is routed through cleanly.

If any of these fail, the fallback is a custom Gradle task that runs `wix` CLI directly on a hand-written `main.wxs`.

## 13. UpdateManager state machine — concurrency & lifecycle

- **Koin binding:** `single { UpdateManager(...) }` in `di/UpdateModule.kt`.
- **Lifecycle:** owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("updater"))`. `Main.kt` calls `get<UpdateManager>().start()` after Koin init and `stop()` from the window `onCloseRequest`.
- **Poll loop:**
  ```kotlin
  merge(flowOf(Unit), tickerFlow(period = 1.hour, jitter = 0..600.seconds))
      .filter { callEngine.callState.value is CallState.Idle }   // I16
      .collect { runCheckCycle() }
  ```
  `runCheckCycle` is a `suspend` function wrapping a try/catch per stage so one failure does not kill the loop.
- **Backoff:** failures increment an in-memory counter; next tick is delayed `30.seconds * 2^n` up to `30.minutes`. Reset on any success.
- **Install gate:** `callEngine.callState.first { it is CallState.Idle }` — with 0-2 min calls this is near-instant. The transition to `Installing` is atomic: it sets `installInProgress = true` on the existing `SipStackLifecycle`-like facade (TODO: wire a boolean into `SipAccountManager` that refuses new `INVITE`s when set), spawns the bootstrapper, and calls `exitProcess(0)`.

## 14. Release Pipeline Overview (future-leaning)

Out of scope for this spec, but designed so the client works with any of:

- Tag `v1.2.0` → CI builds → uploads MSI → writes manifest. (Long-term goal.)
- Manual: human runs `./gradlew packageMsi`, SCPs the MSI, edits the JSON. (MVP reality.)

The client requires exactly: a URL to GET, a SHA256 field in the response, and an MSI file reachable at the manifest's `url`. Anything above that is backend-team territory.

## 15. Security Posture & Debt

### 15.1 What we rely on (MVP)

- **HTTPS** for manifest fetch + MSI download.
- **SHA256** integrity check.
- **Download-host allow-list** pinned in Kotlin source.
- **Zone.Identifier ADS stripping** after verify (cert-free SmartScreen mitigation).
- **LAN-only deployment** — endpoint is not publicly reachable.
- **Schema validation** of the manifest body.
- **CallStateGate** to protect business-critical invariant I1.

### 15.2 What we do not have (documented v2 debt)

- No manifest signing (minisign/cosign/gpg) — if the backend is compromised, an attacker can push arbitrary MSI URLs. LAN-only + allow-list narrows the blast radius to "a URL in one of two hosts", but the content at that URL is still attacker-controlled.
- No Authenticode cert on the MSI — SmartScreen still shows the "Unknown publisher" warning on the first manual install. Mark-of-the-Web stripping covers the auto-update path but not the initial install.
- No code-signing verification of `bootstrapper.exe` — the first version is trusted by being delivered inside a trusted MSI.
- No TLS cert pinning — we rely on the OS trust store.
- No kill switch — rollback is forward-roll only.
- No staged rollout — a broken release reaches the whole fleet. Backend can simulate a staged rollout by returning the new manifest only to a subset of install IDs, but that is optional server-side behavior.

### 15.3 Threat scenarios documented for awareness

| Scenario | Mitigation today | Residual risk |
|---|---|---|
| External MITM on LAN | HTTPS + allow-list | Low (LAN-only) |
| Compromised backend | SHA256 + allow-list | **High** — attacker picks MSI content |
| Malicious `url` off-domain | Allow-list rejects | None |
| Replay / downgrade | I15 (version ≤ current → NoUpdate) | Low |
| Operator machine compromise | Out of scope | N/A |

## 16. Observability

Client sends headers on every poll (per §6.1). Backend logs every poll. A single SQL query answers "which operators are on which version". No client-side telemetry SDK.

If the backend doesn't log (MVP), Islom's workflow is:
1. Ask the operator to press `Ctrl+Shift+Alt+D`.
2. Copy the diagnostics text to Telegram.
3. Read version, install ID, last error, log tail.

This is explicitly the MVP support path until the backend grows a real observability story.

## 17. Open Questions / Placeholders

These must be filled in during backend handoff. They do not block writing client code:

| # | Placeholder | Resolver |
|---|---|---|
| OQ1 | `BACKEND_BASE_URL` for `/api/v1/app-updates/latest` | Backend team sync |
| OQ2 | Allow-list of MSI download hosts (`downloads.yalla.uz`?) | Backend team sync |
| OQ3 | Whether JWT auth is required on the manifest endpoint | Backend team sync |
| OQ4 | MSI hosting location | Backend team sync |
| OQ5 | Whether backend already logs requests usefully | Backend team sync |
| OQ6 | Exact jpackage behaviour for `perUserInstall = true` | Manual MSI decompile with Orca after first build |
| OQ7 | .NET 8 Desktop Runtime presence on operator machines | IT |
| OQ8 | Real operator experience with unsigned bootstrapper + AV | Field testing month 1 |

## 18. Glossary

- **Manifest** — the JSON blob returned by `/app-updates/latest`.
- **Channel** — `stable` or `beta`. Separate backend endpoints (or a query parameter) per channel.
- **Install ID** — UUID generated on first launch, stored in `multiplatform-settings`, used by backend logs to track per-machine versions.
- **Bootstrapper** — the C# helper that does the actual `msiexec` work after the main app exits.
- **Quarantine** — the `backup/<old-version>/` folder the bootstrapper uses for rollback if `msiexec` fails.
- **Sentinel** — a marker file indicating an install is in progress, so the next app launch can detect a crashed install.

## 19. Changelog

- **2026-04-13** — Initial design spec. Based on multi-round brainstorming + 4 parallel critical reviews. Approved for implementation.
