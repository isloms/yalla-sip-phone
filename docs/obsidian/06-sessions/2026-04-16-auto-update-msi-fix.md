---
date: 2026-04-16
task: Fix MSI auto-update bootstrapper — msiexec failures during in-app upgrade
type: fix
scope: update/bootstrapper
outcome: completed
commits:
  - ce473ec chore: rebuild bootstrapper binary with UAC elevation
  - 7a6f0aa fix(update): force per-machine context + STA thread for UAC elevation
  - d80fa8b chore: rebuild bootstrapper with ALLUSERS=1 + STAThread
  - 752c6e6 chore: bump MIN_SUPPORTED_VERSION to 1.0.11
  - 28ff42c fix(update): revert to per-user msiexec (no elevation, no ALLUSERS)
  - 2eb8512 chore: rebuild bootstrapper with per-user msiexec (no elevation)
  - 13d50b9 fix(update): copy MSI to %TEMP% before running msiexec
  - e8c4f84 chore: rebuild bootstrapper with MSI-to-temp fix
  - 01c7a74 fix(update): two-step uninstall/install instead of RemoveExistingProducts
  - bfb118c chore: rebuild bootstrapper with two-step uninstall/install
files_changed:
  - bootstrapper/Program.cs
  - app-resources/windows-x64/yalla-update-bootstrap.exe
  - MIN_SUPPORTED_VERSION
pages_updated:
  - 04-features/auto-update.md
  - 05-operations/troubleshooting.md
last_verified_sha: bfb118c
tags: [session]
---

## Task

Fix the MSI auto-update bootstrapper. The in-app update flow (download MSI → launch bootstrapper → msiexec) was failing with msiexec exit code 1603 every time.

## What Was Done

- Debugged three distinct msiexec failure modes through iterative log analysis
- Rewrote the bootstrapper from single-transaction upgrade to explicit two-step uninstall/install
- Built and tested v1.0.13 → v1.0.14 upgrade successfully on Windows via SSH
- Built macOS DMG v1.0.14
- Cleaned up all stale branches and tags across Mac, Windows, and remote

## Key Decisions

- **Two-step uninstall/install instead of RemoveExistingProducts** — The jpackage-generated WiX MSI's single-transaction upgrade (RemoveExistingProducts) causes Error 1406 registry write failures during InstallFinalize. Splitting into `/x` then `/i` eliminates the nested transaction entirely.
- **Copy MSI to %TEMP% before msiexec** — The downloaded MSI lives inside the install directory (`updates/` subfolder). During upgrade, WixRemoveFoldersEx nukes the install tree including the MSI being read, causing SECREPAIR failures.
- **No UAC elevation / no ALLUSERS=1** — The app is per-user (`perUserInstall = true` in build.gradle.kts). Forcing ALLUSERS=1 creates a per-machine context that conflicts with the per-user install, causing Error 1316 cross-context failures. Per-user installs to AppData don't need admin.
- **Registry lookup for old ProductCode** — Bootstrapper searches HKCU Uninstall registry to find the installed product's ProductCode for explicit `/x` uninstall. Falls back to letting msiexec handle it if not found.

## Gotchas / Learnings

- **MSI source inside install directory is fatal**: WixRemoveFoldersEx (a WiX custom action in jpackage MSIs) recursively deletes the install directory during upgrade. If the MSI source file is inside that directory, the transaction corrupts mid-flight. Always copy the MSI to %TEMP% before invoking msiexec.
- **ALLUSERS=1 on a per-user install = guaranteed failure**: Windows Installer cannot upgrade across install contexts (per-user vs per-machine). The UpgradeCode match is skipped, components conflict with Error 1316.
- **MsiSystemRebootPending poisons all subsequent installs**: A failed per-machine install attempt leaves PendingFileRenameOperations in the registry. Every subsequent msiexec inherits `MsiSystemRebootPending = 1` and fails. Only a reboot clears it.
- **CI-built MSIs embed the bootstrapper from build time**: Fixing the bootstrapper source code doesn't fix already-installed apps. The INSTALLED binary is what runs. Must rebuild the MSI to ship the fix.
- **Ghost HKLM registrations from failed ALLUSERS=1 attempts**: Failed per-machine installs can leave entries in HKLM that block future per-user installs ("Another version already installed"). Must manually clean `HKLM\...\Uninstall\{ProductCode}` and `HKLM\...\Installer\UserData\...\Products\`.
- **SSH escaping with PowerShell is unreliable**: Pipe characters and curly braces get mangled through SSH → cmd.exe → PowerShell. Use `reg query`, `wmic`, or write a .ps1 file instead.

## Follow-ups for Next Session

- [ ] Add HKLM search to `FindInstalledProductCode()` — currently only checks HKCU, misses products registered per-machine from old installs
- [ ] Tag v1.0.14 as an actual release and push tag to remote
- [ ] Update MIN_SUPPORTED_VERSION to match the release
- [ ] Consider cleaning up old version tags (v0.1.0–v1.0.9) if they're not meaningful releases

## Verification

- Build: v1.0.13 MSI + v1.0.14 MSI built successfully on Windows
- Build: v1.0.14 DMG built successfully on macOS
- Manual test: v1.0.13 → v1.0.14 in-app update via bootstrapper — msiexec exit 0, app relaunched as v1.0.14
- Repo state: all branches and tags clean across Mac, Windows, and remote
