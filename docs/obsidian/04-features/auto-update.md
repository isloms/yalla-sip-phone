---
title: "Auto-Update (Windows MSI)"
last_verified_sha: bfb118c
last_updated: 2026-04-16
last_author: claude
status: current
tags: [feature, update, windows, msi]
---

# Auto-Update (Windows MSI)

Windows-only. macOS DMG has no auto-update mechanism.

## How It Works

```
App startup → UpdateChecker polls backend → compares semver →
  if newer: downloads MSI to <installDir>/updates/ →
    SHA256 verify → shows update badge in UI →
      user clicks Install → copies bootstrapper to %TEMP% →
        launches bootstrapper → app calls exitProcess(0)

Bootstrapper (separate .exe, %TEMP%):
  waits for parent PID to die → kills orphaned jcef_helper/YallaSipPhone →
    copies MSI to %TEMP% → finds old ProductCode from registry →
      msiexec /x {old} (uninstall) → msiexec /i new.msi (fresh install) →
        launches new YallaSipPhone.exe
```

## Key Files

| File | Role |
|------|------|
| `data/update/UpdateManager.kt` | Orchestrates check → download → install flow |
| `data/update/UpdateDownloader.kt` | Downloads MSI with resume support, SHA256 verify |
| `data/update/MsiBootstrapperInstaller.kt` | Copies bootstrapper to %TEMP%, launches it, exits app |
| `bootstrapper/Program.cs` | C# exe: waits for app exit, uninstalls old, installs new, relaunches |
| `domain/update/` | Domain types: UpdateManifest, SemVer |
| `build.gradle.kts` (windows block) | `upgradeUuid`, `perUserInstall = true` |

## Backend Contract

Backend serves JSON at the update check endpoint:

```json
{
  "version": "1.0.14",
  "minSupportedVersion": "1.0.14",
  "releaseNotes": "...",
  "installer": {
    "url": "http://192.168.0.98:8080/releases/YallaSipPhone-1.0.14.msi",
    "sha256": "<hex>",
    "size": 146676700
  }
}
```

## Critical Invariants

1. **MSI must be copied to %TEMP% before msiexec** — it lives inside the install directory which gets nuked during upgrade
2. **Two-step uninstall/install** — single-transaction RemoveExistingProducts causes Error 1406 registry failures with jpackage WiX MSIs
3. **No ALLUSERS=1** — app is per-user (`perUserInstall = true`), forcing per-machine context causes Error 1316
4. **No UAC elevation** — per-user install writes to HKCU + AppData, no admin needed
5. **Bootstrapper runs from %TEMP%** — not from the install directory (would be deleted during upgrade)
6. **install.log must be closed before msiexec** — it lives inside the install tree, msiexec needs exclusive directory access

## Bootstrapper Flow (Program.cs)

1. Verify MSI SHA256
2. Wait for parent PID to exit (60s timeout)
3. Sleep 3s for file lock release
4. Kill orphaned `jcef_helper` and `YallaSipPhone` processes
5. Sleep 2s
6. Strip Mark-of-the-Web from MSI
7. Copy MSI to %TEMP%
8. Find old ProductCode from HKCU Uninstall registry
9. If found: `msiexec /x {ProductCode} /qn /norestart` (uninstall old)
10. Delete leftover install directory
11. `msiexec /i %TEMP%\new.msi /qn /norestart` (fresh install)
12. Launch `YallaSipPhone.exe` from install directory

Related: [[06-sessions/2026-04-16-auto-update-msi-fix]]
