---
date: 2026-04-16
task: Debug and fix MSI auto-update bootstrapper failures (msiexec 1603)
type: fix
scope: data/update, bootstrapper
outcome: deferred
commits:
  - ca68d16 fix(update): run msiexec with UAC elevation for per-machine upgrades
  - 7a6f0aa fix(update): force per-machine context + STA thread for UAC elevation
  - 28ff42c fix(update): revert to per-user msiexec (no elevation, no ALLUSERS)
  - 13d50b9 fix(update): copy MSI to %TEMP% before running msiexec
  - 01c7a74 fix(update): two-step uninstall/install instead of RemoveExistingProducts
  - 752c6e6 chore: bump MIN_SUPPORTED_VERSION to 1.0.11
  - ce473ec chore: rebuild bootstrapper binary with UAC elevation
  - d80fa8b chore: rebuild bootstrapper with ALLUSERS=1 + STAThread
  - 2eb8512 chore: rebuild bootstrapper with per-user msiexec (no elevation)
  - e8c4f84 chore: rebuild bootstrapper with MSI-to-temp fix
  - bfb118c chore: rebuild bootstrapper with two-step uninstall/install
files_changed:
  - bootstrapper/Program.cs
  - app-resources/windows-x64/yalla-update-bootstrap.exe
  - MIN_SUPPORTED_VERSION
pages_updated:
  - 05-operations/troubleshooting.md
last_verified_sha: bfb118c
tags: [session, update, msi, windows]
---

## Task

Debug repeated msiexec 1603 failures during auto-update on Windows test machine (192.168.0.130). The bootstrapper spawns msiexec to upgrade the installed MSI, but every attempt failed.

## What Was Done

- Investigated 5+ distinct msiexec 1603 failure root causes via verbose msiexec logs
- Attempted UAC elevation (`Verb = "runas"`) — failed because it forced per-machine context on a per-user install
- Attempted `ALLUSERS=1` to force per-machine — caused Error 1316 (cross-context component conflict)
- Reverted to per-user msiexec (no elevation, no ALLUSERS override)
- Discovered pending reboot state from failed per-machine attempt was poisoning all subsequent installs
- Rebooted Windows machine to clear `PendingFileRenameOperations`
- Rebuilt v1.0.12 (installed) and v1.0.13 (update) from clean HEAD with correct bootstrapper
- Prepared for final test — v1.0.12 installed on Windows, v1.0.13 MSI + JSON given to backend

## Key Decisions

- **Per-user installs only (no elevation)** — Windows Installer fundamentally cannot do cross-context major upgrades. Per-machine to per-user or vice versa always fails. Since jpackage sets `perUserInstall = true`, we stay per-user everywhere. No ALLUSERS override, no Verb="runas".
- **Both versions must be built from the same codebase** — the CI-built v1.0.10 had the old broken bootstrapper baked in. The installed app runs ITS OWN bootstrapper, not the update's. Both the installed version and update version must contain the correct bootstrapper binary.
- **Reboot required after failed per-machine MSI attempt** — a failed msiexec that touches HKLM leaves `PendingFileRenameOperations` in the registry, setting `MsiSystemRebootPending = 1` for all subsequent installs.

## Gotchas / Learnings

- **Windows Installer cross-context upgrades are impossible by design.** Microsoft docs explicitly state: "The Windows Installer will not install major upgrades across installation context." `FindRelatedProducts` only searches the current context (HKCU for per-user, HKLM for per-machine). Per-machine existing + per-user new = old product not found = component conflict.
- **jpackage's `JpFindRelatedProducts` custom action breaks cross-context even harder.** It finds products across contexts (unlike the standard MSI action), sets the MIGRATE property, triggering RemoveExistingProducts on a product in the wrong context. This causes Error 1316.
- **`ALLUSERS=1` on the msiexec command line overrides the MSI's internal `ALLUSERS=""`.** This forcibly changes the install context, which is exactly what causes cross-context failures.
- **`UseShellExecute = true, Verb = "runas"` requires `[STAThread]`** on Main for proper COM apartment state. Also incompatible with `ArgumentList` (must use `Arguments` string) and `CreateNoWindow`.
- **UAC denial throws `Win32Exception` with NativeErrorCode 1223**, not null Process. Must catch explicitly.
- **`MsiSystemRebootPending = 1` poisons subsequent installs.** Even if the current install doesn't need a reboot, a pending reboot from ANY prior install causes weird behavior. Only a real reboot clears it.
- **The installed app's bootstrapper is what runs during update, not the update's bootstrapper.** Fixing the bootstrapper source code doesn't help until the INSTALLED version contains the fix. This is a chicken-and-egg problem for the first update after a bootstrapper fix.
- **Backend runs at `http://192.168.0.98:8080`**, not localhost. Update JSON URLs must use this address.
- **`minSupportedVersion` must equal `version` during MVP** — forces all operators to update.
- **Git tag resolution for version**: when multiple tags point to the same commit, `git describe` picks the lowest. Must delete old tags on the local Windows build machine before building a new version.

## Follow-ups for Next Session

- [ ] Verify v1.0.12 → v1.0.13 update works end-to-end after reboot
- [ ] If it works, establish a clean CI workflow: CI builds the "installed" version, local builds the "update" version, both from the same codebase
- [ ] Consider adding context detection to bootstrapper: check HKCU vs HKLM for existing product and match context
- [ ] Investigate if graceful shutdown fully releases JCEF file locks (JARs were still locked in earlier attempts)
- [ ] Push proper version tags (v1.0.12, v1.0.13) to remote after successful test
- [ ] Update auto-update feature doc in obsidian once the flow is verified working

## Verification

- Build: v1.0.12 and v1.0.13 MSIs built successfully on Windows
- Tests: not run this session (bootstrapper is C#, not covered by Kotlin test suite)
- Lint: N/A
- Manual review: update flow not yet verified end-to-end (pending test after reboot)
